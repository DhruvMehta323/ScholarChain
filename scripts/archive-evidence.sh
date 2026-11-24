#!/usr/bin/env bash
# scripts/archive-evidence.sh [options]
#
# Gathers the evidence of one release into reports/evidence/<sha>/ (never overwritten: an existing directory stops the script) and lists
# every file with its SHA-256 in MANIFEST.json. Copies only; it deletes nothing and changes nothing outside reports/evidence/<sha>/ (and
# the optional S3 prefix). Sources, each included only when it exists:
#   test reports        docs/agent-runs/*/test-report.md and the test-run marker .claude/.last-test-run
#   plan checksum       the newest infra/terraform/plans/tfplan-*.sha256 and its .summary.txt (the plan file itself is not copied)
#   release metadata    reports/release/<sha>/artifacts.json (jar checksums, image IDs)
#   deployment records  reports/deployments/<sha>/ (image digests, smoke-test.json, rollback records)
#   run manifests       reports/pipeline/*/*/run.json and steps.json
#   experiment outputs  reports/experiments/*/*/run.json (command.log files are NOT copied: they can be large)
#   metric exports      with --metrics-hours N: CloudWatch statistics for the embedding queue and the three ECS services (read-only calls)
# Environment (names only): AWS_PROFILE and AWS_REGION, needed only with --metrics-hours or --to-s3.
# Options: --metrics-hours <1..72>  --to-s3 (copy the directory to s3://<reports bucket>/evidence/<sha>/ with the AWS CLI)  --env <name>  --dry-run  --help
# Exit codes: 0 ok, 1 failure, 2 usage, 3 refused (evidence directory already exists), 4 outputs missing (only with AWS options).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

HOURS="" TO_S3=0 ENV_NAME=course
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --metrics-hours) HOURS="${2:?}"; shift ;;
    --to-s3) TO_S3=1 ;;
    --env) ENV_NAME="${2:?}"; shift ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done
[[ -z "${HOURS}" ]] || { [[ "${HOURS}" =~ ^[0-9]+$ ]] && ((HOURS >= 1 && HOURS <= 72)); } || { echo "--metrics-hours must be 1 to 72" >&2; exit 2; }

sc_stage preflight
sc_require_cmd git jq
SHA="$(git -C "${ROOT}" rev-parse HEAD)"
if sc_tree_dirty "${ROOT}"; then DIRTY=true; else DIRTY=false; fi
DEST="${ROOT}/reports/evidence/${SHA}"
if [[ -e "${DEST}" ]] && [[ -n "$(find "${DEST}" -mindepth 1 -maxdepth 1 2> /dev/null | head -n 1)" ]]; then
  sc_warn "reports/evidence/${SHA} already exists and is not empty: evidence is never overwritten (refused)"
  exit 3
fi

LIST=()
add() { # add <source file> <relative destination>
  [[ -f "$1" ]] || return 0
  LIST+=("$1|$2")
}
sc_stage collect
for f in "${ROOT}"/docs/agent-runs/*/test-report.md; do [[ -f "${f}" ]] && add "${f}" "test-reports/$(basename "$(dirname "${f}")")-test-report.md"; done
add "${ROOT}/.claude/.last-test-run" "test-reports/last-test-run-marker.txt"
PLAN_REC="$(sc_latest_plan_record "${ROOT}" || true)"
if [[ -n "${PLAN_REC}" ]]; then
  add "${PLAN_REC}" "plan/$(basename "${PLAN_REC}")"
  add "${PLAN_REC%.sha256}.summary.txt" "plan/$(basename "${PLAN_REC%.sha256}").summary.txt"
fi
add "${ROOT}/reports/release/${SHA}/artifacts.json" "release/artifacts.json"
if [[ -d "${ROOT}/reports/deployments/${SHA}" ]]; then
  while IFS= read -r f; do add "${f}" "deployments/$(basename "${f}")"; done < <(find "${ROOT}/reports/deployments/${SHA}" -maxdepth 1 -name '*.json' -type f | LC_ALL=C sort)
fi
while IFS= read -r f; do
  rel="${f#"${ROOT}"/reports/}"
  add "${f}" "runs/${rel//\//__}"
done < <(find "${ROOT}/reports/pipeline" -mindepth 3 -maxdepth 3 \( -name run.json -o -name steps.json \) -type f 2> /dev/null | LC_ALL=C sort)
while IFS= read -r f; do
  rel="${f#"${ROOT}"/reports/}"
  add "${f}" "experiments/${rel//\//__}"
done < <(find "${ROOT}/reports/experiments" -mindepth 3 -maxdepth 3 -name run.json -type f 2> /dev/null | LC_ALL=C sort)
sc_info "files selected: ${#LIST[@]}"

METRICS_JSON='{}'
if [[ -n "${HOURS}" ]]; then
  sc_stage metrics
  if [[ "${SC_DRY_RUN}" == 1 ]]; then
    printf 'SC-DRYRUN read-only CloudWatch exports for the last %s hours (SQS depth, ECS CPU per service)\n' "${HOURS}"
  else
    sc_require_cmd aws terraform
    : "${AWS_PROFILE:?AWS_PROFILE must be set}"
    sc_load_outputs "${ROOT}" "${ENV_NAME}"
    sc_guard_account_region
    NOW="$(date +%s)"
    iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2> /dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ; }
    END="$(iso "${NOW}")" START="$(iso $((NOW - HOURS * 3600)))"
    CLUSTER="$(sc_out ecs_cluster_name)"
    QNAME="$(basename "$(sc_out embedding_queue_url)")"
    METRICS_JSON="$(jq -n --argjson q "$(sc_retry sc_aws cloudwatch get-metric-statistics --namespace AWS/SQS --metric-name ApproximateNumberOfMessagesVisible --dimensions "Name=QueueName,Value=${QNAME}" --start-time "${START}" --end-time "${END}" --period 300 --statistics Maximum --output json)" '{embedding_queue_visible_messages: $q}')"
    for svc in "${SC_SERVICES[@]}"; do
      m="$(sc_retry sc_aws cloudwatch get-metric-statistics --namespace AWS/ECS --metric-name CPUUtilization --dimensions "Name=ClusterName,Value=${CLUSTER}" "Name=ServiceName,Value=${svc}" --start-time "${START}" --end-time "${END}" --period 300 --statistics Average Maximum --output json)" || sc_die "cannot read ECS metrics for ${svc}"
      METRICS_JSON="$(printf '%s' "${METRICS_JSON}" | jq --arg s "${svc}" --argjson m "${m}" '. + {("ecs_cpu_" + $s): $m}')"
    done
    METRICS_JSON="$(printf '%s' "${METRICS_JSON}" | jq --arg s "${START}" --arg e "${END}" '. + {window_start: $s, window_end: $e}')"
  fi
fi

if [[ "${SC_DRY_RUN}" == 1 ]]; then
  for e in "${LIST[@]+"${LIST[@]}"}"; do printf 'SC-DRYRUN copy %s -> reports/evidence/%s/%s\n' "${e%%|*}" "${SHA}" "${e#*|}"; done
  sc_ok "dry run: nothing was written"
  exit 0
fi

sc_stage write
mkdir -p "${DEST}"
ENTRIES='[]'
for e in "${LIST[@]+"${LIST[@]}"}"; do
  src="${e%%|*}" rel="${e#*|}"
  mkdir -p "${DEST}/$(dirname "${rel}")"
  cp -- "${src}" "${DEST}/${rel}"
  ENTRIES="$(printf '%s' "${ENTRIES}" | jq --arg p "${rel}" --arg h "$(sc_sha256 "${DEST}/${rel}")" '. + [{path: $p, sha256: $h}]')"
done
if [[ "${METRICS_JSON}" != '{}' ]]; then
  sc_json_write "${DEST}/metrics/cloudwatch-export.json" "${METRICS_JSON}"
  ENTRIES="$(printf '%s' "${ENTRIES}" | jq --arg h "$(sc_sha256 "${DEST}/metrics/cloudwatch-export.json")" '. + [{path: "metrics/cloudwatch-export.json", sha256: $h}]')"
fi
sc_json_write "${DEST}/MANIFEST.json" "$(jq -n --arg sha "${SHA}" --arg at "$(sc_utc_iso)" --argjson dirty "${DIRTY}" --argjson files "${ENTRIES}" '{commit: $sha, archived_at: $at, dirty_tree: $dirty, files: $files}')"
sc_ok "evidence archived: reports/evidence/${SHA}/ ($(printf '%s' "${ENTRIES}" | jq 'length') files)"

if [[ "${TO_S3}" == 1 ]]; then
  sc_stage upload-to-s3
  sc_require_cmd aws terraform
  : "${AWS_PROFILE:?AWS_PROFILE must be set}"
  sc_load_outputs "${ROOT}" "${ENV_NAME}"
  sc_guard_account_region
  RB="$(sc_out reports_bucket)"
  [[ -n "${RB}" ]] || sc_die "Terraform output reports_bucket is empty"
  sc_aws s3 cp "${DEST}" "s3://${RB}/evidence/${SHA}/" --recursive --only-show-errors
  sc_ok "copied to s3://<reports bucket>/evidence/${SHA}/ (copy only; nothing deleted)"
fi
