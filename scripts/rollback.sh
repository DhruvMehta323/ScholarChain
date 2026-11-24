#!/usr/bin/env bash
# scripts/rollback.sh <mode> --reason "<text>" --approval-ref <file> [options]
#
# Rolls the three ECS services back to previously registered task-definition revisions, waits, verifies and records. It changes no
# data and no infrastructure definition; desired counts stay as they are. A human approval record must exist (--approval-ref points
# at the file where the human wrote the decision; the script checks that it exists and is not empty, it does not interpret it).
#
# Mode (exactly one):
#   --from-record <deployment.json>   use previous_task_definition of every service in that deployment record (undo that deployment)
#   --to-sha <git sha>                use the task definitions recorded in reports/deployments/<sha>/deployment.json
#   --revisions <svc=N,svc=N,...>     explicit revisions, for example query-api=3,retrieval=2,embedding-worker=1
# Environment (names only): AWS_PROFILE (required), AWS_REGION (must equal the Terraform output "region").
# Options: --env <name> (default course)  --timeout <seconds> (default 900)  --dry-run  --help
# Exit codes: 0 ok, 1 failure, 2 usage, 3 refused (dirty tree, missing approval), 4 Terraform outputs missing, 5 timeout.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
# shellcheck source=lib/ecs.sh
source "${SCRIPT_DIR}/lib/ecs.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

ENV_NAME=course TIMEOUT=900 MODE="" MODE_ARG="" REASON="" APPROVAL=""
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --env) ENV_NAME="${2:?}"; shift ;;
    --timeout) TIMEOUT="${2:?}"; shift ;;
    --reason) REASON="${2:?}"; shift ;;
    --approval-ref) APPROVAL="${2:?}"; shift ;;
    --from-record | --to-sha | --revisions)
      [[ -z "${MODE}" ]] || { echo "give exactly one mode" >&2; exit 2; }
      MODE="$1"; MODE_ARG="${2:?}"; shift ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done
[[ -n "${MODE}" ]] || { echo "a mode is required (see --help)" >&2; exit 2; }
[[ -n "${REASON}" ]] || { echo "--reason is required" >&2; exit 2; }
[[ "${TIMEOUT}" =~ ^[0-9]+$ ]] || { echo "--timeout must be an integer" >&2; exit 2; }

sc_stage preflight
sc_require_cmd git terraform aws jq
: "${AWS_PROFILE:?AWS_PROFILE must be set}"
sc_require_clean_tree "${ROOT}"
if [[ -z "${APPROVAL}" || ! -s "${APPROVAL}" ]]; then
  sc_warn "a recorded human approval is required: --approval-ref must name an existing, non-empty file (refused)"
  exit 3
fi
sc_info "reason recorded: ${REASON}"

sc_stage resolve-targets
case "${MODE}" in
  --from-record | --to-sha)
    if [[ "${MODE}" == "--to-sha" ]]; then
      sc_valid_token "${MODE_ARG}"
      REC="${ROOT}/reports/deployments/${MODE_ARG}/deployment.json"
      FIELD=task_definition
    else
      REC="${MODE_ARG}"
      FIELD=previous_task_definition
    fi
    [[ -f "${REC}" ]] || sc_die "deployment record not found: ${REC}"
    for svc in "${SC_SERVICES[@]}"; do
      sc_set TARGET "${svc}" "$(jq -r --arg s "${svc}" --arg f "${FIELD}" '.services[] | select(.service == $s) | .[$f]' "${REC}")"
    done
    ;;
  --revisions)
    IFS=',' read -r -a pairs <<< "${MODE_ARG}"
    for kv in "${pairs[@]}"; do
      [[ "${kv}" =~ ^(query-api|retrieval|embedding-worker)=[0-9]+$ ]] || sc_die "bad --revisions entry: ${kv}"
      sc_set TARGET "${kv%%=*}" "${kv#*=}"
    done
    ;;
esac
for svc in "${SC_SERVICES[@]}"; do
  [[ -n "$(sc_get TARGET "${svc}")" && "$(sc_get TARGET "${svc}")" != null ]] || sc_die "no rollback target for ${svc}"
done

sc_stage outputs-and-identity
sc_load_outputs "${ROOT}" "${ENV_NAME}"
sc_guard_account_region
CLUSTER="$(sc_out ecs_cluster_name)"

sc_stage rollback-services
for svc in "${SC_SERVICES[@]}"; do
  cur="$(sc_retry sc_aws ecs describe-services --cluster "${CLUSTER}" --services "${svc}" --query 'services[0].taskDefinition' --output text)" || sc_die "cannot describe ${svc}"
  sc_set BEFORE "${svc}" "${cur}"
  t="$(sc_get TARGET "${svc}")"
  if [[ "${t}" =~ ^[0-9]+$ ]]; then t="scholarchain-${svc}:${t}"; fi # a bare revision means family scholarchain-<service>
  # Resolve to a full ARN (read) so the target must exist before anything is changed.
  arn="$(sc_retry sc_aws ecs describe-task-definition --task-definition "${t}" --query 'taskDefinition.taskDefinitionArn' --output text)" || sc_die "task definition ${t} does not exist"
  sc_set AFTER "${svc}" "${arn}"
  sc_mut sc_aws ecs update-service --cluster "${CLUSTER}" --service "${svc}" --task-definition "$(sc_get AFTER "${svc}")" --output json > /dev/null
done

sc_stage wait-and-verify
RESULTS='[]'
for svc in "${SC_SERVICES[@]}"; do
  if [[ "${SC_DRY_RUN}" == 1 ]]; then
    printf 'SC-DRYRUN wait for %s on %s then verify\n' "${svc}" "$(sc_get AFTER "${svc}")"
    continue
  fi
  want="$(sc_retry sc_aws ecs describe-services --cluster "${CLUSTER}" --services "${svc}" --query 'services[0].desiredCount' --output text)"
  rc=0
  sc_wait_service "${CLUSTER}" "${svc}" "$(sc_get AFTER "${svc}")" "${want}" "${TIMEOUT}" || rc=$?
  ((rc == 0)) || { sc_warn "${svc} did not stabilise on the rollback target (rc=${rc})"; exit 5; }
  digests="$(sc_verify_service "${CLUSTER}" "${svc}" "$(sc_get AFTER "${svc}")")" || sc_die "${svc} verification failed"
  RESULTS="$(printf '%s' "${RESULTS}" | jq --arg s "${svc}" --arg b "$(sc_get BEFORE "${svc}")" --arg a "$(sc_get AFTER "${svc}")" --argjson d "${digests}" '. + [{service: $s, from: $b, to: $a, running_task_digests: $d, verified: true}]')"
  sc_ok "${svc}: rolled back and verified"
done

sc_stage record
SHA="$(git -C "${ROOT}" rev-parse HEAD)"
OUT="$(jq -n --arg sha "${SHA}" --arg at "$(sc_utc_iso)" --arg reason "${REASON}" --arg appr "$(basename "${APPROVAL}")" --arg env "${ENV_NAME}" --argjson r "${RESULTS}" \
  '{kind: "rollback", commit_at_rollback: $sha, environment: $env, rolled_back_at: $at, reason: $reason, approval_record: $appr, services: $r, data_changes: "none"}')"
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf 'SC-DRYRUN would write reports/deployments/%s/rollback-%s.json\n' "${SHA}" "<utc>"
else
  sc_json_write "${ROOT}/reports/deployments/${SHA}/rollback-$(sc_utc).json" "${OUT}"
  sc_ok "rollback record written under reports/deployments/${SHA}/"
fi
sc_ok "rollback complete (dry_run=${SC_DRY_RUN})"
