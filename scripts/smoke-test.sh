#!/usr/bin/env bash
# scripts/smoke-test.sh [options]
#
# Smoke test of the deployed query-api: /healthz, /readyz, one bounded query (POST /v1/query, see DEFAULT_QUERY below), evidence and explain for the
# returned claim, an idempotent repeat, and one expected error (401 without a key). Writes reports/deployments/<sha>/smoke-test.json.
# The JSON holds check names, HTTP statuses and latencies only: never the key, never a response body.
#
# The services API key (Secrets Manager entry SCHOLARCHAIN_SERVICES_API_KEY) is NOT read from Secrets Manager by this script: the
# approval guard blocks any script that reads the secret store, and the key must not pass through an agent. A HUMAN fetches it into a
# mode-600 file outside the repository, with their own credentials, and points SC_SMOKE_API_KEY_FILE at it (run --print-key-help for
# the steps; the CLI command is in INFRA-CONTRACTS.md section 14, M13-7). The script reads the file into a shell variable, hands it to curl through a mode-600 header file (never on a
# command line, so it is not visible in ps), and never prints it. Delete the file afterwards.
#
# Environment (names only):
#   AWS_PROFILE, AWS_REGION        needed only to read the Terraform outputs and verify the account (not for the HTTP calls)
#   SC_SMOKE_API_KEY_FILE          path of the mode-600 file that holds the key (required unless --dry-run)
#   SC_SMOKE_BASE_URL              override of the Terraform output query_api_url (the ALB must be on: see env-window.sh up). Its host must
#                                  equal the host of the Terraform output query_api_url; a different host is accepted ONLY for a loopback
#                                  host (localhost, 127.0.0.1) together with --allow-insecure-http (local stubs).
#   SC_SMOKE_QUERY_FILE            JSON request body for POST /v1/query (default: a small bounded query, see below)
#   SC_SMOKE_AUTH_HEADER           header name for the key (default X-API-Key; API.md on main)
# Options: --env <name> (default course)  --dry-run  --print-key-help  --help
#   --allow-insecure-http   without it, an http:// base URL is REFUSED (exit 3): the API key would cross the network in clear text. The
#                           default Terraform query_api_url is http (the ALB has no TLS listener yet), so a real run against it needs this
#                           flag, prints a loud warning, and the key should be rotated afterwards. Keep it out of any normal runbook.
# Exit codes: 0 all checks passed, 1 a check failed, 2 usage, 3 refused (http without the flag, host not the Terraform output host),
# 4 Terraform outputs missing, 6 no base URL (ALB off) or key file unusable (missing, inside the repository, loose mode, empty).
# shellcheck disable=SC2016,SC2015 # intentional: literal text in single quotes, JMESPath backticks, A && B || C status idiom, trap handler
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

key_help() {
  printf '%s\n' \
    'SC-HUMAN-STEP in your own terminal, with your own credentials (never an agent, never a pasted value):' \
    '  1. umask 077, then create an empty temporary file at a path OUTSIDE the repository.' \
    '  2. Write the secret string of the Secrets Manager entry SCHOLARCHAIN_SERVICES_API_KEY into that file' \
    '     (the exact CLI command is in infra/terraform/INFRA-CONTRACTS.md, section 14, item M13-7).' \
    '  3. Run: SC_SMOKE_API_KEY_FILE=<that file> bash scripts/smoke-test.sh' \
    '  4. Delete the temporary file.'
}

ENV_NAME=course INSECURE=0
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --print-key-help) key_help; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --allow-insecure-http) INSECURE=1 ;;
    --env) ENV_NAME="${2:?}"; shift ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

AUTH_HEADER="${SC_SMOKE_AUTH_HEADER:-X-API-Key}"
[[ "${AUTH_HEADER}" =~ ^[A-Za-z0-9-]+$ ]] || { echo "SC_SMOKE_AUTH_HEADER must be a header name" >&2; exit 2; }

# The default body respects API.md (main): includeEvidence true, no bridge/community/path/team-size options (those are refused with 422), and
# no topic/venue/institution filter (each must be ONE token matching ^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$ and exist in the data).
# It is meant to select the MaxTeamSize operation (the planner keyword fallback picks it from "largest team"): a stored window summary
# read from DynamoDB, needing no researcher name and no S3 Vectors ONLY IF the planner picks max_team_size. The window must be a STORED
# one (2000-2004 ... 2020-2024; "2015-2026" is not stored).
# MODEL DEPENDENCE: the deployed query goes through the planner and the answer synthesis, which call Bedrock first
# (Composition.scala:351-361, QueryPlannerAgent.scala:115-125, AnswerSynthesisAgent.scala:97-112). The chosen operation is therefore
# model-dependent, and the answer can abstain (for example no_verified_claims). The `query` check passes only with a non-empty claims array;
# an abstention is recorded in its note (claims, abstained and the abstain reason), and `explain` runs whenever a queryId exists.
DEFAULT_QUERY='{"question":"What was the largest team size in 2020 to 2024?","constraints":{"yearFrom":2020,"yearTo":2024,"topics":[],"institutions":[],"venues":[]},"analysis":{"includeTeamSize":false,"includeBridgeMetrics":false,"includeCommunities":false,"includePaths":false},"retrieval":{"graphLimit":10,"vectorLimit":30,"maxPathDepth":3},"output":{"includeEvidence":true,"includeScores":true,"includeUncertainty":true}}'

sc_stage preflight
sc_require_cmd git terraform aws jq curl
: "${AWS_PROFILE:?AWS_PROFILE must be set}"
sc_load_outputs "${ROOT}" "${ENV_NAME}"
sc_guard_account_region
TF_URL="$(sc_out query_api_url)"
TF_URL="${TF_URL%/}"
BASE_URL="${SC_SMOKE_BASE_URL:-${TF_URL}}"
BASE_URL="${BASE_URL%/}"
if [[ -z "${BASE_URL}" ]]; then
  sc_warn "query_api_url is empty: the load balancer is off (create_alb = false). Switch it on for the test window: bash scripts/env-window.sh up"
  exit 6
fi
[[ "${BASE_URL}" =~ ^https?://[A-Za-z0-9._:-]+$ ]] || sc_die "base URL is not a plain http(s) host"
url_host() { local h="${1#*://}"; h="${h%%[/:]*}"; printf '%s' "${h}" | tr '[:upper:]' '[:lower:]'; }
BASE_HOST="$(url_host "${BASE_URL}")"
if [[ "${BASE_URL}" == http://* ]]; then
  if [[ "${INSECURE}" != 1 ]]; then
    sc_warn "REFUSED: the base URL is http:// so the API key would be sent in clear text. Use an https URL, or pass --allow-insecure-http knowingly (see --help)"
    exit 3
  fi
  sc_warn "!!! INSECURE: --allow-insecure-http given: the API key will be sent over plain HTTP to ${BASE_HOST}. Anyone on the path can read it. Rotate the key after this run. !!!"
fi
if [[ "${BASE_HOST}" != "$(url_host "${TF_URL}")" ]]; then
  case "${BASE_HOST}" in
    localhost | 127.0.0.1) [[ "${INSECURE}" == 1 ]] || { sc_warn "REFUSED: base URL host ${BASE_HOST} differs from the Terraform query_api_url host; a loopback stub needs --allow-insecure-http"; exit 3; } ;;
    *) sc_warn "REFUSED: base URL host ${BASE_HOST} is not the host of the Terraform output query_api_url: the API key is never sent to another host"; exit 3 ;;
  esac
fi
SHA="$(git -C "${ROOT}" rev-parse HEAD)"

if [[ "${SC_DRY_RUN}" == 1 ]]; then
  for c in "GET /healthz" "GET /readyz" "POST /v1/query (with key)" "GET /v1/queries/<queryId>/evidence and GET /v1/evidence/<evidenceId>" "GET /v1/explain/<queryId>" "POST /v1/query again with the same Idempotency-Key" "POST /v1/query without a key (expect 401)"; do
    printf 'SC-DRYRUN %s %s\n' "$c" "${BASE_URL}"
  done
  sc_ok "dry run: no HTTP call was made and no key was read"
  exit 0
fi

KEYFILE="${SC_SMOKE_API_KEY_FILE:-}"
[[ -n "${KEYFILE}" && -f "${KEYFILE}" ]] || { sc_warn "SC_SMOKE_API_KEY_FILE must name an existing file (see --print-key-help)"; exit 6; }
# Resolve to an absolute path with symlinks followed BEFORE the outside-the-repository test (review L6: a relative path bypassed it).
KEY_REAL="$(sc_realpath "${KEYFILE}")" || { sc_warn "the key file path is not usable"; exit 6; }
ROOT_REAL="$(cd "${ROOT}" && pwd -P)"
case "${KEY_REAL}" in "${ROOT_REAL}"/*) sc_warn "the key file must be outside the repository"; exit 6 ;; esac
PERM="$(stat -c %a "${KEY_REAL}" 2> /dev/null || stat -f %Lp "${KEY_REAL}")"
[[ "${PERM}" == 600 || "${PERM}" == 400 ]] || { sc_warn "the key file must be mode 600 or 400 (got ${PERM})"; exit 6; }
API_KEY="$(tr -d '\r\n' < "${KEY_REAL}")"
[[ -n "${API_KEY}" ]] || { sc_warn "the key file is empty"; exit 6; }

TMP="$(mktemp -d)"
trap 'rm -rf -- "${TMP:?}"; API_KEY=""' EXIT
chmod 700 "${TMP}"
( umask 077; printf '%s: %s\n' "${AUTH_HEADER}" "${API_KEY}" > "${TMP}/auth.hdr" )
if [[ -n "${SC_SMOKE_QUERY_FILE:-}" ]]; then cp -- "${SC_SMOKE_QUERY_FILE}" "${TMP}/query.json"; else printf '%s' "${DEFAULT_QUERY}" > "${TMP}/query.json"; fi
jq -e . "${TMP}/query.json" > /dev/null || sc_die "the query body is not valid JSON"

RESULTS='[]'
FAILED=0
HTTP_CODE="" LATENCY=""
# call <method> <path> <auth:yes|no> [extra curl args...]: sets HTTP_CODE, LATENCY (ms) and writes the body to ${TMP}/body.
call() {
  local method="$1" path="$2" auth="$3" out
  shift 3
  local -a hdr=()
  [[ "${auth}" == yes ]] && hdr=(-H "@${TMP}/auth.hdr")
  out="$(curl -sS --connect-timeout 10 --max-time 40 -X "${method}" ${hdr[@]+"${hdr[@]}"} "$@" -o "${TMP}/body" -w '%{http_code} %{time_total}' "${BASE_URL}${path}" 2> "${TMP}/curl.err")" || out="000 0"
  HTTP_CODE="${out%% *}"
  LATENCY="$(awk -v t="${out##* }" 'BEGIN { printf "%d", t * 1000 }')"
}
# record <name> <ok:true|false> <expected> <note>
record() {
  RESULTS="$(printf '%s' "${RESULTS}" | jq --arg n "$1" --argjson ok "$2" --arg e "$3" --arg c "${HTTP_CODE}" --argjson l "${LATENCY:-0}" --arg note "$4" '. + [{check: $n, passed: $ok, expected_status: $e, http_status: $c, latency_ms: $l, note: $note}]')"
  if [[ "$2" == true ]]; then sc_ok "$1 (http ${HTTP_CODE}, ${LATENCY} ms)"; else sc_warn "FAILED $1 (http ${HTTP_CODE}, expected ${3})"; FAILED=1; fi
}

sc_stage health
call GET /healthz no; [[ "${HTTP_CODE}" == 200 ]] && record healthz true 200 "" || record healthz false 200 ""
call GET /readyz no; [[ "${HTTP_CODE}" == 200 ]] && record readyz true 200 "" || record readyz false 200 "a 503 here usually means a dependency (for example Neptune) is off"

sc_stage query
IDEM="smoke-$(sc_utc)-$$"
call POST /v1/query yes -H 'Content-Type: application/json' -H "Idempotency-Key: ${IDEM}" --data-binary "@${TMP}/query.json"
QUERY_ID="" CLAIM_EVID=""
if [[ "${HTTP_CODE}" == 202 ]]; then
  JOB="$(jq -r '.jobId // empty' "${TMP}/body")"
  [[ "${JOB}" =~ ^[A-Za-z0-9:._-]+$ ]] || JOB=""
  deadline=$((SECONDS + ${SC_SMOKE_JOB_TIMEOUT:-150}))
  while [[ -n "${JOB}" ]] && ((SECONDS < deadline)); do
    sleep 5
    call GET "/v1/jobs/${JOB}" yes
    [[ "$(jq -r '.state // .status // empty' "${TMP}/body" 2> /dev/null)" =~ ^(SUCCEEDED|FAILED)$ ]] && break
  done
  [[ -n "${JOB}" ]] && call GET "/v1/jobs/${JOB}/result" yes
fi
if [[ "${HTTP_CODE}" == 200 ]] && jq -e '.queryId and (.claims | type == "array")' "${TMP}/body" > /dev/null 2>&1; then
  QUERY_ID="$(jq -r '.queryId' "${TMP}/body")"
  CLAIM_EVID="$(jq -r '[.claims[]?.evidenceIds[]?] | first // empty' "${TMP}/body")"
  cp -- "${TMP}/body" "${TMP}/first.body"
  NCLAIMS="$(jq '.claims | length' "${TMP}/body")"
  QNOTE="claims=${NCLAIMS} abstained=$(jq -r '.abstained' "${TMP}/body")"
  if ((NCLAIMS > 0)); then
    record "query" true 200 "${QNOTE}"
  else
    # an empty claims array is a failed check, but the reason is recorded (a token of at most 64 characters, see RestViews abstainReason)
    ABSTAIN="$(jq -r '.abstainReason // empty' "${TMP}/body" | tr -cd 'A-Za-z0-9._:-' | cut -c1-64)"
    record "query" false 200 "${QNOTE}${ABSTAIN:+ abstain_reason=${ABSTAIN}} (no claims)"
  fi
else
  record "query" false 200 "no usable synchronous answer"
fi
[[ "${QUERY_ID}" =~ ^[A-Za-z0-9:._-]+$ ]] || QUERY_ID=""
[[ "${CLAIM_EVID}" =~ ^[A-Za-z0-9:._-]+$ ]] || CLAIM_EVID=""

sc_stage evidence-and-explain
if [[ -n "${CLAIM_EVID}" ]]; then
  call GET "/v1/evidence/${CLAIM_EVID}" yes
  [[ "${HTTP_CODE}" == 200 ]] && record evidence-by-id true 200 "" || record evidence-by-id false 200 ""
else
  HTTP_CODE="n/a"; LATENCY=0
  record evidence-by-id false 200 "no claim evidence id to fetch (the query returned no claim, or abstained)"
fi
if [[ -n "${QUERY_ID}" ]]; then
  call GET "/v1/queries/${QUERY_ID}/evidence" yes
  [[ "${HTTP_CODE}" == 200 ]] && record query-evidence true 200 "items=$(jq '.items | length' "${TMP}/body" 2> /dev/null || echo 0)" || record query-evidence false 200 ""
  call GET "/v1/explain/${QUERY_ID}" yes
  [[ "${HTTP_CODE}" == 200 ]] && record explain true 200 "" || record explain false 200 ""
else
  HTTP_CODE="n/a"; LATENCY=0
  record explain false 200 "no query id (the query call returned no usable answer)"
fi

sc_stage idempotent-repeat
if [[ -n "${QUERY_ID}" ]]; then
  call POST /v1/query yes -H 'Content-Type: application/json' -H "Idempotency-Key: ${IDEM}" --data-binary "@${TMP}/query.json"
  if [[ "${HTTP_CODE}" == 200 && "$(jq -r '.queryId // empty' "${TMP}/body")" == "${QUERY_ID}" ]]; then record idempotent-repeat true 200 "same queryId"; else record idempotent-repeat false 200 "repeat did not return the stored result"; fi
else
  HTTP_CODE="n/a"; LATENCY=0
  record idempotent-repeat false 200 "no first answer to repeat"
fi

sc_stage expected-error
call POST /v1/query no -H 'Content-Type: application/json' --data-binary "@${TMP}/query.json"
[[ "${HTTP_CODE}" == 401 ]] && record unauthenticated-401 true 401 "" || record unauthenticated-401 false 401 ""

sc_stage record
OUT="$(jq -n --arg sha "${SHA}" --arg at "$(sc_utc_iso)" --arg env "${ENV_NAME}" --arg host "${BASE_URL}" --argjson r "${RESULTS}" --argjson failed "${FAILED}" \
  '{commit: $sha, environment: $env, finished_at: $at, base_url: $host, all_passed: ($failed == 0), checks: $r, secrets_in_output: false}')"
sc_json_write "${ROOT}/reports/deployments/${SHA}/smoke-test.json" "${OUT}"
sc_info "result: reports/deployments/${SHA}/smoke-test.json"
if ((FAILED != 0)); then sc_warn "smoke test FAILED"; exit 1; fi
sc_ok "smoke test passed"
