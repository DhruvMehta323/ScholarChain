#!/usr/bin/env bash
# scripts/wait-embeddings.sh [options]
#
# Read-only wait for the embedding queue to drain after the load stage enqueued its jobs. Polls the Terraform outputs
# embedding_queue_url (and embedding_dlq_url) with sqs get-queue-attributes and succeeds when visible + in-flight + delayed messages have
# been 0 continuously for --quiet-seconds. It sends, deletes and purges nothing. The dead-letter count at the end is RECORDED, and a
# non-empty DLQ is reported as exit 1 (some jobs failed for good). It counts messages, not vectors: the vector total is reported by the
# embedding worker logs (and the ops-manifest.json) and is verified on the S3 Vectors side by a human or by an approved read-only check.
# With --scale and --run-id the result is written (never overwritten) to reports/pipeline/<scale>/<run>/embeddings.json.
#
# Options: --quiet-seconds N (default 300, 1 to 3600)  --timeout-seconds N (default 7200, 1 to 14400)  --scale <s> --run-id <id>
#          --env <name> (default course)  --dry-run  --help
# Environment (names only): AWS_PROFILE, AWS_REGION, SC_POLL_SECONDS (default 30)
# Exit codes: 0 drained and the DLQ is empty, 1 drained but the DLQ is not empty, or an AWS call failed, 2 usage, 3 refused (record exists),
# 4 Terraform outputs missing, 5 timeout (queue not quiet in time), 6 no embedding queue output.
# shellcheck disable=SC2016,SC2015 # intentional: literal text in single quotes, JMESPath backticks, A && B || C status idiom
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

ENV_NAME=course QUIET=300 TIMEOUT=7200 SCALE="" RUN_ID=""
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --quiet-seconds) QUIET="${2:?}"; shift ;;
    --timeout-seconds) TIMEOUT="${2:?}"; shift ;;
    --scale) SCALE="${2:?}"; shift ;;
    --run-id) RUN_ID="${2:?}"; shift ;;
    --env) ENV_NAME="${2:?}"; shift ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done
[[ "${QUIET}" =~ ^[0-9]+$ ]] && ((QUIET >= 1 && QUIET <= 3600)) || { echo "--quiet-seconds must be 1 to 3600" >&2; exit 2; }
[[ "${TIMEOUT}" =~ ^[0-9]+$ ]] && ((TIMEOUT >= 1 && TIMEOUT <= 14400)) || { echo "--timeout-seconds must be 1 to 14400" >&2; exit 2; }
[[ -z "${SCALE}" && -z "${RUN_ID}" ]] || { [[ "${SCALE}" =~ ^(1k|10k|50k)$ && "${RUN_ID}" =~ ^[A-Za-z0-9_.-]{1,128}$ ]]; } || { echo "--scale (1k|10k|50k) and --run-id must be given together" >&2; exit 2; }

sc_stage preflight
sc_require_cmd terraform aws jq
: "${AWS_PROFILE:?AWS_PROFILE must be set}"
sc_load_outputs "${ROOT}" "${ENV_NAME}"
sc_guard_account_region
QURL="$(sc_out embedding_queue_url)" DURL="$(sc_out embedding_dlq_url)"
[[ -n "${QURL}" ]] || { sc_warn "the Terraform output embedding_queue_url is empty"; exit 6; }
REC=""
if [[ -n "${RUN_ID}" ]]; then
  REC="${ROOT}/reports/pipeline/${SCALE}/${RUN_ID}/embeddings.json"
  if [[ "${SC_DRY_RUN}" != 1 && -e "${REC}" ]]; then sc_warn "REFUSED: ${REC} exists: records are never overwritten"; exit 3; fi
fi
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf 'SC-DRYRUN aws sqs get-queue-attributes (read-only) every %ss until visible+in-flight+delayed == 0 for %ss (limit %ss); then the DLQ count\n' "${SC_POLL_SECONDS:-30}" "${QUIET}" "${TIMEOUT}"
  sc_ok "dry run: no AWS call was made"
  exit 0
fi

# depth <queue-url>: "visible inflight delayed" (approximate counts), read-only.
depth() {
  local out
  out="$(sc_retry sc_aws sqs get-queue-attributes --queue-url "$1" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible ApproximateNumberOfMessagesDelayed \
    --query '[Attributes.ApproximateNumberOfMessages, Attributes.ApproximateNumberOfMessagesNotVisible, Attributes.ApproximateNumberOfMessagesDelayed]' --output text < /dev/null)" || return 1
  printf '%s\n' "${out}"
}

sc_stage wait
start=$SECONDS quiet_since="" v=0 f=0 d=0 status=timeout
while :; do
  read -r v f d < <(depth "${QURL}") || sc_die "cannot read the queue attributes"
  [[ "${v}" =~ ^[0-9]+$ && "${f}" =~ ^[0-9]+$ && "${d}" =~ ^[0-9]+$ ]] || sc_die "unexpected queue attributes: ${v} ${f} ${d}"
  total=$((v + f + d))
  if ((total == 0)); then
    [[ -n "${quiet_since}" ]] || quiet_since=$SECONDS
    if ((SECONDS - quiet_since >= QUIET)); then status=drained; break; fi
  else
    quiet_since=""
  fi
  sc_info "queue visible=${v} in-flight=${f} delayed=${d} (waited $((SECONDS - start))s of ${TIMEOUT}s)"
  if ((SECONDS - start >= TIMEOUT)); then break; fi
  sleep "${SC_POLL_SECONDS:-30}"
done

dlq=null
if [[ -n "${DURL}" ]]; then
  read -r dv df dd < <(depth "${DURL}") || sc_die "cannot read the dead-letter queue attributes"
  [[ "${dv}" =~ ^[0-9]+$ && "${df}" =~ ^[0-9]+$ && "${dd}" =~ ^[0-9]+$ ]] || sc_die "unexpected dead-letter attributes: ${dv} ${df} ${dd}"
  dlq=$((dv + df + dd))
fi
sc_stage record
OUT="$(jq -n --arg status "${status}" --arg at "$(sc_utc_iso)" --argjson v "${v}" --argjson f "${f}" --argjson d "${d}" --argjson dlq "${dlq}" --argjson q "${QUIET}" --argjson t "${TIMEOUT}" --argjson w "$((SECONDS - start))" \
  '{status: $status, finished_at: $at, queue: {visible: $v, in_flight: $f, delayed: $d}, dlq_messages: $dlq, quiet_seconds_required: $q, timeout_seconds: $t, waited_seconds: $w, counts: "approximate message counts; vectors are not counted here"}')"
if [[ -n "${REC}" ]]; then sc_json_write "${REC}" "${OUT}"; sc_info "record: reports/pipeline/${SCALE}/${RUN_ID}/embeddings.json"; else printf '%s\n' "${OUT}"; fi
[[ "${status}" == drained ]] || { sc_warn "the embedding queue was not quiet within ${TIMEOUT}s"; exit 5; }
if [[ "${dlq}" != null && "${dlq}" -gt 0 ]]; then sc_warn "drained, but the dead-letter queue holds ${dlq} messages"; exit 1; fi
sc_ok "embedding queue drained (dead-letter messages: ${dlq})"
