#!/usr/bin/env bash
# scripts/deploy.sh [options]
#
# Run by deploy-engineer AFTER a human has applied the approved plan. Pushes the packaged images to ECR (tag = Git commit), registers a
# new task-definition revision per service, updates the three ECS services, waits for them to stabilise, verifies what is running
# and writes reports/deployments/<sha>/deployment.json. It NEVER applies or destroys infrastructure: when the Terraform outputs are
# missing it prints the exact human command and exits 4.
#
# Environment (names only):
#   AWS_PROFILE            required (never a literal key)
#   AWS_REGION             must equal the Terraform output "region"
#   APPROVED_PLAN_SHA256   required: the SHA-256 of the human-approved plan. It must (1) equal the checksum plan.sh recorded, (2) equal
#                          the SHA-256 of the saved .tfplan next to the record, which must exist, and (3) appear in the gate file.
#   package.sh must have run for this commit as a RELEASE run (reports/release/<sha>/artifacts.json with release:true, dirty:false);
#   a local run (reports/release-local/) is never accepted.
# Options:
#   --env <name>                 Terraform environment (default course)
#   --query-api-count <n>        desired count (default 1)      --retrieval-count <n>  (default 1)      --worker-count <n>  (default 0)
#   --timeout <seconds>          stabilisation timeout per service (default 900)
#   --plan-record <file>         the plan checksum file written by plan.sh (default: the newest in infra/terraform/plans/). It must resolve,
#                                symlinks included, to infra/terraform/plans/tfplan-*.sha256; any other path is refused.
#   --gate-file <file>           REQUIRED. docs/agent-runs/<phase>/human-gate.md (the file the human writes), which must contain
#                                APPROVED_PLAN_SHA256. Read only; no script writes it. It must resolve into docs/agent-runs/<phase>/.
#   --dry-run                    read-only calls only; mutating calls are printed (SC-DRYRUN) and not run
#   --help
# Record: reports/deployments/<sha>/deployment.json is written as status "in-progress" (previous and new task definition of every
# service) BEFORE any service is updated, and rewritten as "failed" (with the reason) or "succeeded" on every exit path.
# Exit codes: 0 ok, 1 failure, 2 usage, 3 refused (dirty tree, plan record, .tfplan, gate file, non-release artifacts), 4 Terraform outputs missing, 5 timeout.
# shellcheck disable=SC2016,SC2329 # intentional: literal text in single quotes, JMESPath backticks, A && B || C status idiom, trap handler
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
# shellcheck source=lib/images.sh
source "${SCRIPT_DIR}/lib/images.sh"
# shellcheck source=lib/ecs.sh
source "${SCRIPT_DIR}/lib/ecs.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

ENV_NAME=course
N_API=1 N_RET=1 N_WRK=0
TIMEOUT=900
PLAN_RECORD="" GATE_FILE=""
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --env) ENV_NAME="${2:?--env needs a value}"; shift ;;
    --query-api-count) N_API="${2:?}"; shift ;;
    --retrieval-count) N_RET="${2:?}"; shift ;;
    --worker-count) N_WRK="${2:?}"; shift ;;
    --timeout) TIMEOUT="${2:?}"; shift ;;
    --plan-record) PLAN_RECORD="${2:?}"; shift ;;
    --gate-file) GATE_FILE="${2:?}"; shift ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done
for n in "${N_API}" "${N_RET}" "${N_WRK}"; do [[ "${n}" =~ ^[0-4]$ ]] || { echo "desired counts must be 0 to 4" >&2; exit 2; }; done
[[ "${TIMEOUT}" =~ ^[0-9]+$ ]] || { echo "--timeout must be an integer" >&2; exit 2; }

desired_for() {
  case "$1" in
    query-api) echo "${N_API}" ;;
    retrieval) echo "${N_RET}" ;;
    *) echo "${N_WRK}" ;;
  esac
}

# ---------------------------------------------------------------------------------------------------------------------
sc_stage preflight
sc_require_cmd git terraform aws jq docker
: "${AWS_PROFILE:?AWS_PROFILE must be set}"
sc_require_clean_tree "${ROOT}"
sc_ok "working tree is clean"
SHA="$(git -C "${ROOT}" rev-parse HEAD)"
TAG="$(sc_release_tag "${ROOT}")"

sc_stage plan-checksum
if [[ -z "${APPROVED_PLAN_SHA256:-}" ]]; then
  sc_warn "APPROVED_PLAN_SHA256 must be set to the human-approved plan checksum"
  exit 3
fi
[[ "${APPROVED_PLAN_SHA256}" =~ ^[0-9a-fA-F]{64}$ ]] || { sc_warn "APPROVED_PLAN_SHA256 is not 64 hex characters"; exit 3; }
APPROVED_LC="$(printf '%s' "${APPROVED_PLAN_SHA256}" | tr 'A-F' 'a-f')"
PLANS_DIR="$(cd "${ROOT}/infra/terraform/plans" 2> /dev/null && pwd -P)" || { sc_warn "REFUSED: infra/terraform/plans/ does not exist (no plan was produced on this machine)"; exit 3; }
if [[ -z "${PLAN_RECORD}" ]]; then
  PLAN_RECORD="$(sc_latest_plan_record "${ROOT}")" || { sc_warn "no recorded plan checksum (infra/terraform/plans/tfplan-*.sha256): run scripts/plan.sh and have a human apply it"; exit 3; }
fi
REC_REAL="$(sc_realpath "${PLAN_RECORD}")" || { sc_warn "REFUSED: plan record path is not usable: ${PLAN_RECORD}"; exit 3; }
if [[ "${REC_REAL}" != "${PLANS_DIR}"/tfplan-*.sha256 || "${REC_REAL#"${PLANS_DIR}"/}" == */* ]]; then
  sc_warn "REFUSED: the plan record must resolve to infra/terraform/plans/tfplan-*.sha256 (symlinks resolved); got a path outside it"
  exit 3
fi
[[ -f "${REC_REAL}" ]] || { sc_warn "plan record not found: ${PLAN_RECORD}"; exit 3; }
RECORDED_SHA="$(cut -d' ' -f1 "${REC_REAL}" | head -n 1)"
RECORDED_NAME="$(awk 'NR == 1 { print $2 }' "${REC_REAL}")"
[[ "${RECORDED_SHA}" =~ ^[0-9a-f]{64}$ ]] || { sc_warn "REFUSED: the plan record does not start with a lowercase SHA-256"; exit 3; }
if [[ "${RECORDED_SHA}" != "${APPROVED_LC}" ]]; then
  sc_warn "PLAN CHECKSUM MISMATCH: APPROVED_PLAN_SHA256 does not equal the checksum recorded in $(basename "${REC_REAL}"); refusing"
  exit 3
fi
PLAN_FILE="${REC_REAL%.sha256}.tfplan"
PLAN_REAL="$(sc_realpath "${PLAN_FILE}")" || { sc_warn "REFUSED: the saved .tfplan is missing"; exit 3; }
if [[ ! -f "${PLAN_REAL}" || "${PLAN_REAL}" != "${PLANS_DIR}"/tfplan-*.tfplan || "${PLAN_REAL#"${PLANS_DIR}"/}" == */* ]]; then
  sc_warn "REFUSED: the saved .tfplan next to the record is missing (or resolves outside infra/terraform/plans/): $(basename "${PLAN_FILE}")"
  exit 3
fi
[[ -z "${RECORDED_NAME}" || "${RECORDED_NAME}" == "$(basename "${PLAN_FILE}")" ]] || { sc_warn "REFUSED: the plan record names ${RECORDED_NAME}, not $(basename "${PLAN_FILE}")"; exit 3; }
if [[ "$(sc_sha256 "${PLAN_REAL}")" != "${RECORDED_SHA}" ]]; then
  sc_warn "REFUSED: the saved .tfplan no longer matches its recorded checksum (it was changed after planning)"
  exit 3
fi
sc_require_gate_file "${ROOT}" "${GATE_FILE}" "${APPROVED_LC}"
sc_ok "plan checksum matches the approved value (record $(basename "${REC_REAL}"), .tfplan hash verified, gate file contains it)"

sc_stage outputs-and-identity
sc_load_outputs "${ROOT}" "${ENV_NAME}"
sc_guard_account_region
CLUSTER="$(sc_out ecs_cluster_name)"
[[ -n "${CLUSTER}" ]] || sc_die "Terraform output ecs_cluster_name is empty"

sc_stage secrets-present
# Existence of a stored value is read from metadata only (VersionIdsToStages); no secret value is read.
while IFS= read -r arn; do
  n="$(sc_retry sc_aws secretsmanager describe-secret --secret-id "${arn}" --query 'length(VersionIdsToStages || `{}`)' --output text)" || sc_die "cannot describe a secret"
  [[ "${n}" =~ ^[1-9][0-9]*$ ]] || sc_die "a service secret has no value yet (a human sets all four secret values before deploy)"
done < <(printf '%s' "${TF_OUT}" | jq -r '.secret_arns.value | to_entries[] | .value')
sc_ok "all secrets have a stored value"

sc_stage release-artifacts
ARTIFACTS="${ROOT}/reports/release/${SHA}/artifacts.json"
[[ -f "${ARTIFACTS}" ]] || sc_die "missing ${ARTIFACTS}: run scripts/package.sh (a release run) for this commit first"
jq -e --arg sha "${SHA}" --arg tag "${TAG}" '.release == true and .dirty == false and .commit == $sha and .image_tag == $tag' "${ARTIFACTS}" > /dev/null 2>&1 \
  || { sc_warn "REFUSED: artifacts.json is not a release record for this commit (needs release:true, dirty:false, commit and tag matching): rebuild with a clean-tree scripts/package.sh"; exit 3; }
REGISTRY=""
for svc in "${SC_SERVICES[@]}"; do
  sc_set REPO_URL "${svc}" "$(sc_out_f ecr_repository_urls ".\"${svc}\" // empty")"
  [[ -n "$(sc_get REPO_URL "${svc}")" ]] || sc_die "no ECR repository URL for ${svc} in the Terraform outputs"
  url="$(sc_get REPO_URL "${svc}")"
  REGISTRY="${url%%/*}"
  want="$(jq -r --arg s "${svc}" --arg t "${TAG}" '.images[] | select(.service == $s and .tag == $t) | .image_id' "${ARTIFACTS}")"
  [[ -n "${want}" ]] || sc_die "artifacts.json has no image for ${svc} tagged ${TAG}"
  have="$(docker image inspect --format '{{.Id}}' "$(sc_local_tag "${svc}" "${TAG}")" 2> /dev/null || true)"
  [[ "${have}" == "${want}" ]] || sc_die "local image for ${svc} does not match artifacts.json (rebuild with scripts/package.sh)"
done
sc_ok "local images match reports/release/<sha>/artifacts.json"

sc_stage push-images
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf 'SC-DRYRUN aws ecr get-login-password | docker login --username AWS --password-stdin %s\n' "${REGISTRY}"
else
  # The password goes through a pipe, never a command line.
  sc_aws ecr get-login-password | docker login --username AWS --password-stdin "${REGISTRY}" > /dev/null || sc_die "docker login to ECR failed"
fi
for svc in "${SC_SERVICES[@]}"; do
  repo="scholarchain/${svc}"
  d="$(sc_aws ecr describe-images --repository-name "${repo}" --image-ids "imageTag=${TAG}" --query 'imageDetails[0].imageDigest' --output text 2> /dev/null || true)"
  if [[ "${d}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    sc_info "${svc}: tag ${TAG} already in ECR (immutable); reusing its digest"
  else
    sc_mut docker tag "$(sc_local_tag "${svc}" "${TAG}")" "$(sc_get REPO_URL "${svc}"):${TAG}"
    sc_mut sc_timeout "${SC_PUSH_TIMEOUT:-1200}" docker push "$(sc_get REPO_URL "${svc}"):${TAG}"
    d="$(sc_retry sc_aws ecr describe-images --repository-name "${repo}" --image-ids "imageTag=${TAG}" --query 'imageDetails[0].imageDigest' --output text 2> /dev/null || true)"
    [[ "${SC_DRY_RUN}" == 1 ]] && d="sha256:$(printf '%064d' 0)"
  fi
  [[ "${d}" =~ ^sha256:[0-9a-f]{64}$ ]] || sc_die "cannot read the pushed image digest for ${svc}"
  sc_set DIGEST "${svc}" "${d}"
done

# ---------------------------------------------------------------------------------------------------------------------
# Deployment record (review M6). Written as "in-progress" before any service is updated, and rewritten as "failed" or "succeeded" on
# every exit path (the EXIT trap covers sc_die, a timeout and an unexpected error). Rollback reads previous_task_definition from it.
# ---------------------------------------------------------------------------------------------------------------------
REC_PATH="${ROOT}/reports/deployments/${SHA}/deployment.json"
REC_REL="reports/deployments/${SHA}/deployment.json"
DEPLOY_STATUS="not-started" DEPLOY_REASON="" VERIFY='[]'
build_record() { # build_record <status> <reason>
  local rec svc
  rec="$(jq -n --arg sha "${SHA}" --arg tag "${TAG}" --arg env "${ENV_NAME}" --arg region "$(sc_out region)" --arg cluster "${CLUSTER}" \
    --arg plan "${RECORDED_SHA}" --arg at "$(sc_utc_iso)" --argjson verify "${VERIFY}" --arg status "$1" --arg reason "$2" \
    --arg a "${N_API}" --arg r "${N_RET}" --arg w "${N_WRK}" \
    '{status: $status, failure_reason: (if $reason == "" then null else $reason end), commit: $sha, image_tag: $tag, environment: $env, region: $region, ecs_cluster: $cluster,
      approved_plan_sha256: $plan, updated_at: $at,
      desired_counts: {"query-api": ($a|tonumber), retrieval: ($r|tonumber), "embedding-worker": ($w|tonumber)}, verification: $verify, account_check: "match", services: []}')"
  for svc in "${SC_SERVICES[@]}"; do
    rec="$(printf '%s' "${rec}" | jq --arg s "${svc}" --arg o "$(sc_get OLD_TD "${svc}")" --arg n "$(sc_get NEW_TD "${svc}")" --arg d "$(sc_get DIGEST "${svc}")" '.services += [{service: $s, previous_task_definition: $o, task_definition: $n, image_digest: $d}]')"
  done
  printf '%s' "${rec}"
}
write_record() { sc_json_write "${REC_PATH}" "$(build_record "$1" "$2")"; }
on_exit() {
  local rc=$?
  if [[ "${DEPLOY_STATUS}" == in-progress ]]; then
    write_record failed "${DEPLOY_REASON:-${SC_LAST_ERROR:-exit ${rc}}}" || true
    sc_warn "deployment FAILED after the services were changed: the record ${REC_REL} (status failed) holds the previous task definitions. Roll back with: scripts/rollback.sh --from-record ${REC_REL} --reason <text> --approval-ref <file> (human approval required)"
  fi
  [[ -z "${TMP:-}" ]] || rm -rf -- "${TMP:?}"
}
trap on_exit EXIT

sc_stage register-and-update
TMP="$(mktemp -d)"
for svc in "${SC_SERVICES[@]}"; do
  td="$(sc_retry sc_aws ecs describe-services --cluster "${CLUSTER}" --services "${svc}" --query 'services[0].taskDefinition' --output text)" || sc_die "cannot describe service ${svc}"
  sc_set OLD_TD "${svc}" "${td}"
  [[ "$(sc_get OLD_TD "${svc}")" != "None" && -n "$(sc_get OLD_TD "${svc}")" ]] || sc_die "service ${svc} not found in cluster ${CLUSTER}"
  sc_retry sc_aws ecs describe-task-definition --task-definition "$(sc_get OLD_TD "${svc}")" --query taskDefinition --output json > "${TMP}/${svc}.old.json" || sc_die "cannot read the task definition of ${svc}"
  # Keep the registrable fields only and point every container of this service at the new image.
  jq --arg img "$(sc_get REPO_URL "${svc}"):${TAG}" '
    {family, taskRoleArn, executionRoleArn, networkMode, containerDefinitions, volumes, placementConstraints,
     requiresCompatibilities, cpu, memory, runtimePlatform, ephemeralStorage}
    | with_entries(select(.value != null))
    | .containerDefinitions |= map(.image = $img)' "${TMP}/${svc}.old.json" > "${TMP}/${svc}.new.json"
  if [[ "${SC_DRY_RUN}" == 1 ]]; then
    printf 'SC-DRYRUN aws ecs register-task-definition --cli-input-json file://<generated for %s> (image %s:%s)\n' "${svc}" "$(sc_get REPO_URL "${svc}")" "${TAG}"
    sc_set NEW_TD "${svc}" "(new revision of $(sc_get OLD_TD "${svc}"))"
  else
    td="$(sc_aws ecs register-task-definition --cli-input-json "file://${TMP}/${svc}.new.json" --query taskDefinition.taskDefinitionArn --output text)" || sc_die "register-task-definition failed for ${svc}"
    sc_set NEW_TD "${svc}" "${td}"
  fi
done
# Every previous and new task definition is known now; write the record BEFORE the first service is touched.
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf 'SC-DRYRUN would write %s as in-progress before updating any service\n' "${REC_REL}"
else
  if [[ -f "${REC_PATH}" ]]; then cp -- "${REC_PATH}" "${REC_PATH%.json}.before-$(sc_utc).json"; fi # an earlier record of this commit is kept
  write_record in-progress ""
  DEPLOY_STATUS=in-progress
  sc_ok "in-progress deployment record written: ${REC_REL}"
fi
for svc in "${SC_SERVICES[@]}"; do
  sc_mut sc_aws ecs update-service --cluster "${CLUSTER}" --service "${svc}" --task-definition "$(sc_get NEW_TD "${svc}")" --desired-count "$(desired_for "${svc}")" --output json > /dev/null
done

sc_stage wait-stable
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf 'SC-DRYRUN wait up to %ss per service for rolloutState COMPLETED and runningCount == desiredCount\n' "${TIMEOUT}"
else
  for svc in "${SC_SERVICES[@]}"; do
    rc=0
    sc_wait_service "${CLUSTER}" "${svc}" "$(sc_get NEW_TD "${svc}")" "$(desired_for "${svc}")" "${TIMEOUT}" || rc=$?
    case "${rc}" in
      0) sc_ok "${svc} stable (desired $(desired_for "${svc}"))" ;;
      5)
        DEPLOY_REASON="${svc} did not stabilise within ${TIMEOUT}s"
        sc_warn "${svc} did not stabilise in ${TIMEOUT}s. Previous task definition: $(sc_get OLD_TD "${svc}")."
        exit 5
        ;;
      *) sc_die "cannot poll ${svc}" ;;
    esac
  done
fi

sc_stage verify
for svc in "${SC_SERVICES[@]}"; do
  if [[ "${SC_DRY_RUN}" == 1 ]]; then
    printf 'SC-DRYRUN verify %s: service task definition == new revision; running task image digests == %s\n' "${svc}" "$(sc_get DIGEST "${svc}")"
    continue
  fi
  td_img="$(sc_retry sc_aws ecs describe-task-definition --task-definition "$(sc_get NEW_TD "${svc}")" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
  [[ "${td_img}" == "$(sc_get REPO_URL "${svc}"):${TAG}" ]] || sc_die "${svc} task definition image is not the release tag"
  running_digests="$(sc_verify_service "${CLUSTER}" "${svc}" "$(sc_get NEW_TD "${svc}")" "$(sc_get DIGEST "${svc}")")" || sc_die "${svc} verification failed"
  VERIFY="$(printf '%s' "${VERIFY}" | jq --arg s "${svc}" --arg d "$(sc_get DIGEST "${svc}")" --argjson r "${running_digests}" '. + [{service: $s, digest: $d, running_task_digests: $r, verified: true}]')"
  sc_ok "${svc}: task definition and image digest verified"
done

sc_stage record
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf 'SC-DRYRUN would write %s:\n%s\n' "${REC_REL}" "$(build_record succeeded "" | jq -S .)"
else
  write_record succeeded ""
  DEPLOY_STATUS=succeeded
  sc_ok "deployment record: ${REC_REL} (status succeeded)"
fi
sc_ok "deploy complete (dry_run=${SC_DRY_RUN})"
