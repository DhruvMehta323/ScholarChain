#!/usr/bin/env bash
# Phase 14 SINGLE deploy for release C10 e7235971d22b: deploy.sh, image_tag, plan + rule check, apply only on an exact rule match (bash).
# Prepared by the orchestrator. Every step exits the whole script on failure, and prints PHASE14-RUN-FAILED at <step>.
# On success it prints PHASE14-RUN-DONE. It is resumable: a step whose record already shows success for release is skipped.
# Step d applies ONLY when the plan summary matches the rule the human pre-stated (add 0, change 3, destroy 0, replace 4, iam 0,
# both stateful lists empty, all 4 task-definition images at the release tag). Any mismatch prints it and exits without applying.
# deploy.sh: up to 4 attempts, 30 s apart, each under a watchdog (killed with its docker children after 20 min, or 60 s after a
# "failed to copy" without exiting). A retry happens ONLY when the attempt ended in the push-images stage and wrote no deployment
# record, which means no service was touched. Already-pushed tags are reused (deploy.sh:162-164). Any other failure stops the script.
set -Eeuo pipefail

STEP=init
trap 'echo "PHASE14-RUN-FAILED at ${STEP} (line ${LINENO})"; exit 1' ERR
fail() { echo "PHASE14-RUN-FAILED at ${STEP}${1:+: $1}"; exit 1; }
step() { STEP="$1"; echo ""; echo "==> PHASE14 STEP ${STEP} $(date -u +%H:%M:%SZ)"; }

REPO="${HOME}/Desktop/ScholarChain"
cd "${REPO}"
export AWS_PROFILE=scholarchain AWS_REGION=us-east-2

REL=e7235971d22b   # C10
FULL="$(git rev-parse HEAD)"
DAY="$(date -u +%Y-%m-%d)"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
LOG=reports/deployments
ENV_DIR=infra/terraform/environments/course
TFVARS="${ENV_DIR}/terraform.tfvars"
P1_SHA=4bd8164d14fd305b62b4e6b4155013c06303250a747116fbf5b9353f83bd33f5
P1_REC=infra/terraform/plans/tfplan-course-a556430ad7a8-20261125T011114Z.sha256
GATE=docs/agent-runs/phase-14-deploy/human-gate.md
KEY_FILE="${HOME}/.scholarchain/services-api-key"
CLUSTER=scholarchain-cluster
DEPLOY_REC="${LOG}/${FULL}/deployment.json"
DEPLOY_MAX_S=1200        # watchdog: one deploy attempt may run at most 20 minutes
FAILCOPY_GRACE_S=60      # watchdog: after "failed to copy" appears, the attempt must exit within 60 s or it is killed

# ---------------------------------------------------------------------------------------------------------------------------------
step preflight
[[ "$(git rev-parse --short=12 HEAD)" == "${REL}" ]] || fail "HEAD is not release ${REL}"
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || fail "tracked files are modified"
[[ -f "reports/release/${FULL}/artifacts.json" ]] || fail "no release artifacts.json for release (run package.sh)"
[[ -f "${P1_REC}" ]] || fail "P1 plan record missing"
[[ -f "${GATE}" ]] || fail "gate file missing"
[[ -f "${KEY_FILE}" ]] || fail "smoke key file ${KEY_FILE} missing (needed at step f; fetch it first)"
if pgrep -f 'sbt.*[[:space:]]check([[:space:];]|$)' > /dev/null 2>&1; then fail "an sbt check is running"; fi
echo "preflight ok: HEAD=${REL}"

# ---------------------------------------------------------------------------------------------------------------------------------
step a-deploy
if [[ -f "${DEPLOY_REC}" && "$(jq -r '.status' "${DEPLOY_REC}")" == succeeded ]]; then
  echo "SKIP: ${DEPLOY_REC} already shows status succeeded"
else
  [[ ! -e "${DEPLOY_REC}" ]] || fail "a release deployment record exists with status $(jq -r '.status' "${DEPLOY_REC}"); not retrying, diagnose first"
  deployed=0
  for attempt in 1 2 3 4; do
    dlog="${LOG}/${DAY}-deploy-${REL}-${TS}-attempt${attempt}.log"
    echo "deploy attempt ${attempt} -> ${dlog}"
    : > "${dlog}"
    # Watchdog: deploy.sh runs in the background in its OWN process group (job control on), so the whole group, including the
    # docker push children, can be killed. Kill when the attempt exceeds DEPLOY_MAX_S, or when the log shows "failed to copy" and
    # the attempt has not exited within FAILCOPY_GRACE_S (a hung push).
    set -m
    APPROVED_PLAN_SHA256="${P1_SHA}" bash scripts/deploy.sh --plan-record "${P1_REC}" --gate-file "${GATE}" > "${dlog}" 2>&1 &
    dpid=$!
    set +m
    tail -n +1 -f "${dlog}" &
    tpid=$!
    start=$(date +%s); failcopy_at=0; killed=""
    while kill -0 "${dpid}" 2> /dev/null; do
      sleep 5
      now=$(date +%s)
      if (( now - start > DEPLOY_MAX_S )); then killed="timeout after $(( now - start )) s"; fi
      if [[ -z "${killed}" ]] && grep -q 'failed to copy' "${dlog}"; then
        (( failcopy_at > 0 )) || failcopy_at=${now}
        if (( now - failcopy_at > FAILCOPY_GRACE_S )); then killed="'failed to copy' in the log and no exit within ${FAILCOPY_GRACE_S} s"; fi
      fi
      if [[ -n "${killed}" ]]; then
        echo ""; echo "WATCHDOG: killing deploy attempt ${attempt} (${killed})"
        kill -TERM -- "-${dpid}" 2> /dev/null || true
        sleep 5
        kill -KILL -- "-${dpid}" 2> /dev/null || true
        pkill -f 'docker push .*scholarchain/' 2> /dev/null || true
        break
      fi
    done
    rc=0; wait "${dpid}" 2> /dev/null || rc=$?
    kill "${tpid}" 2> /dev/null || true; wait "${tpid}" 2> /dev/null || true
    if [[ -z "${killed}" && "${rc}" == 0 ]]; then deployed=1; break; fi
    last="$(grep '^==> STEP' "${dlog}" | tail -1 || true)"
    echo "deploy attempt ${attempt}: exit ${rc}${killed:+, ${killed}}; last stage: ${last:-unknown}"
    if [[ "${last}" == "==> STEP push-images" && ! -e "${DEPLOY_REC}" && "${attempt}" -lt 4 ]]; then
      echo "failed in push-images with no deployment record (no service touched); retry in 30 s"
      sleep 30
      continue
    fi
    fail "deploy.sh failed (exit ${rc}${killed:+, ${killed}}; last stage: ${last:-unknown}; log ${dlog})"
  done
  [[ "${deployed}" == 1 ]] || fail "deploy.sh did not succeed"
  [[ "$(jq -r '.status' "${DEPLOY_REC}")" == succeeded ]] || fail "deployment record is not succeeded"
fi

# ---------------------------------------------------------------------------------------------------------------------------------
step b-image-tag
n="$(grep -c '^image_tag[[:space:]]*=' "${TFVARS}" || true)"
[[ "${n}" == 1 ]] || fail "expected exactly one image_tag line in ${TFVARS}, found ${n}"
sed -i '' -E "s/^image_tag[[:space:]]*=.*/image_tag = \"${REL}\"/" "${TFVARS}"
[[ "$(grep -c "^image_tag = \"${REL}\"\$" "${TFVARS}" || true)" == 1 ]] || fail "image_tag is not ${REL} after the edit"
echo "image_tag = \"${REL}\""

# ---------------------------------------------------------------------------------------------------------------------------------
# Applied already? The ops task definition is registered only by Terraform (deploy.sh never touches it), so its image tag shows
# whether the release plan has been applied.
OPS_ARN="$(terraform -chdir="${ENV_DIR}" output -raw ops_task_definition_arn)"
OPS_TAG="$(aws ecs describe-task-definition --task-definition "${OPS_ARN}" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
OPS_TAG="${OPS_TAG##*:}"

if [[ "${OPS_TAG}" == "${REL}" ]]; then
  step c-plan; echo "SKIP: the ops task definition already runs :${REL} (release plan applied)"
  step d-apply; echo "SKIP: already applied"
else
  step c-plan
  bash scripts/plan.sh 2>&1 | tee "${LOG}/${DAY}-plan-P4-${REL}-${TS}.log"
  SUM="$(ls -t infra/terraform/plans/tfplan-course-${REL}-*.summary.txt 2> /dev/null | head -1 || true)"
  [[ -n "${SUM}" && -f "${SUM}" ]] || fail "no plan summary for ${REL}"
  PLAN="${REPO}/${SUM%.summary.txt}.tfplan"
  [[ -f "${PLAN}" ]] || fail "plan file ${PLAN} missing"
  val() { sed -n "s/^$1=//p" "${SUM}" | head -1; }
  bad=()
  [[ "$(val commit_sha)" == "${FULL}" ]]          || bad+=("commit_sha=$(val commit_sha)")
  [[ "$(val account_check)" == match ]]           || bad+=("account_check=$(val account_check)")
  [[ "$(val region)" == us-east-2 ]]              || bad+=("region=$(val region)")
  [[ "$(val resources_to_add)" == 0 ]]            || bad+=("resources_to_add=$(val resources_to_add) (expected 0)")
  [[ "$(val resources_to_change)" == 3 ]]         || bad+=("resources_to_change=$(val resources_to_change) (expected 3)")
  [[ "$(val resources_to_destroy)" == 0 ]]        || bad+=("resources_to_destroy=$(val resources_to_destroy) (expected 0)")
  [[ "$(val resources_to_replace)" == 4 ]]        || bad+=("resources_to_replace=$(val resources_to_replace) (expected 4)")
  [[ "$(val iam_changes)" == 0 ]]                 || bad+=("iam_changes=$(val iam_changes) (expected 0)")
  [[ "$(val nat_gateways_in_plan)" == 0 ]]        || bad+=("nat_gateways_in_plan=$(val nat_gateways_in_plan)")
  [[ "$(val interface_endpoints_in_plan)" == 0 ]] || bad+=("interface_endpoints_in_plan=$(val interface_endpoints_in_plan)")
  s1="$(awk '/^stateful_resources_in_plan:/{f=1;next} /^stateful_destroy_or_replace:/{f=0} f' "${SUM}" | grep -c . || true)"
  s2="$(awk '/^stateful_destroy_or_replace:/{f=1;next} /^SC-SUMMARY-END/{f=0} f' "${SUM}" | grep -c . || true)"
  [[ "${s1}" == 0 ]] || bad+=("stateful_resources_in_plan has ${s1} entries")
  [[ "${s2}" == 0 ]] || bad+=("stateful_destroy_or_replace has ${s2} entries")
  [[ "$(shasum -a 256 "${PLAN}" | cut -d' ' -f1)" == "$(val plan_sha256)" ]] || bad+=("plan file hash != plan_sha256")
  IMGS="$(terraform -chdir="${ENV_DIR}" show -json "${PLAN}" \
    | jq -r '.resource_changes[] | select(.type=="aws_ecs_task_definition") | .change.after.container_definitions | fromjson | .[].image')"
  nimg="$(printf '%s\n' "${IMGS}" | grep -c . || true)"
  nc6="$(printf '%s\n' "${IMGS}" | grep -c ":${REL}\$" || true)"
  [[ "${nimg}" == 4 && "${nc6}" == 4 ]] || bad+=("task-definition images: ${nimg} total, ${nc6} at :${REL} (expected 4/4)")
  if (( ${#bad[@]} > 0 )); then
    echo "PLAN DOES NOT MATCH THE PRE-STATED RULE; NOT APPLYING:"
    for b in "${bad[@]}"; do echo "  - ${b}"; done
    fail "plan mismatch (summary ${SUM})"
  fi
  echo "plan matches the pre-stated rule: $(val plan_sha256)"
  printf 'plan_file=%s\nplan_sha256=%s\nrule=add0 change3 destroy0 replace4 iam0 stateful-empty images-4/4-at-%s\n' \
    "${PLAN}" "$(val plan_sha256)" "${REL}" > "${LOG}/${DAY}-P4-approved-by-rule-${REL}.txt"

  step d-apply
  terraform -chdir="${ENV_DIR}" apply -input=false "${PLAN}" 2>&1 | tee "${LOG}/${DAY}-apply-P4-${REL}-${TS}.log"
fi
aws ecs wait services-stable --cluster "${CLUSTER}" --services query-api retrieval
for svc in query-api retrieval; do
  td="$(aws ecs describe-services --cluster "${CLUSTER}" --services "${svc}" --query 'services[0].taskDefinition' --output text)"
  img="$(aws ecs describe-task-definition --task-definition "${td}" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
  [[ "${img}" == *":${REL}" ]] || fail "${svc} runs ${img##*:}, not :${REL}"
  echo "${svc}: ${td##*/} image tag ${img##*:}"
done


STEP=finished
echo ""
echo "PHASE14-DEPLOY-DONE (release ${REL})"
