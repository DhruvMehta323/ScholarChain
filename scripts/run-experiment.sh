#!/usr/bin/env bash
# scripts/run-experiment.sh <experiment-id> [options]
#
# Runs an APPROVED experiment manifest from experiments/manifests/<experiment-id>.json (Phase 15b; the directory may not exist yet on
# lane2: the script then stops with a clear error). The manifest is owned by experiment-engineer and is never edited here.
#
# Manifest fields this script reads (JSON): "id" (equals the file name), "approved" (must be true), "approved_by" (non-empty),
# "command" (a JSON array of strings: the program and arguments to run, no shell), "max_runtime_seconds" (1 to 14400),
# "resource_limits" (object, copied to the record), "metric_locations" (array of strings, copied to the record).
# It records the commit SHA, resource limits, start and end times, Region and metric locations to reports/experiments/<id>/<run>/run.json.
# The command's own output goes to reports/experiments/<id>/<run>/command.log. Nothing in reports/experiments is overwritten.
#
# Environment (names only): AWS_PROFILE and AWS_REGION when the manifest's command talks to AWS (the script only records the Region;
# it checks nothing against AWS because it makes no AWS call itself).
# Options: --manifest-dir <dir> (default experiments/manifests)  --run-id <token>  --dry-run  --help
# Exit codes: 0 ok, 1 command failed or timed out, 2 usage, 3 refused (dirty tree, not approved), 6 manifest directory or manifest missing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

EXP="" MDIR="${ROOT}/experiments/manifests" RUN_ID=""
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --manifest-dir) MDIR="${2:?}"; shift ;;
    --run-id) RUN_ID="${2:?}"; shift ;;
    -*) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
    *) [[ -z "${EXP}" ]] || { echo "one experiment id only" >&2; exit 2; }; EXP="$1" ;;
  esac
  shift
done
[[ -n "${EXP}" ]] || { echo "usage: run-experiment.sh <experiment-id> [options]" >&2; exit 2; }
sc_valid_token "${EXP}"
[[ -n "${RUN_ID}" ]] || RUN_ID="r$(sc_utc)"
sc_valid_token "${RUN_ID}"

sc_stage manifest
sc_require_cmd git jq
if [[ ! -d "${MDIR}" ]]; then
  sc_warn "the manifest directory ${MDIR#"${ROOT}"/} does not exist: experiment manifests are created in Phase 15b by experiment-engineer (not present on this branch)"
  exit 6
fi
MANIFEST="${MDIR}/${EXP}.json"
[[ -f "${MANIFEST}" ]] || { sc_warn "no manifest ${MANIFEST#"${ROOT}"/}"; exit 6; }
jq -e --arg id "${EXP}" '.id == $id and .approved == true and ((.approved_by // "") | length > 0)
  and (.command | type == "array" and length > 0 and all(type == "string"))
  and (.max_runtime_seconds | type == "number" and . >= 1 and . <= 14400)' "${MANIFEST}" > /dev/null \
  || { sc_warn "manifest is not an approved, well-formed manifest (see --help for the required fields)"; exit 3; }
sc_require_clean_tree "${ROOT}"
SHA="$(git -C "${ROOT}" rev-parse HEAD)"
MAXRT="$(jq -r '.max_runtime_seconds' "${MANIFEST}")"
mapfile_cmd=()
while IFS= read -r a; do mapfile_cmd+=("${a}"); done < <(jq -r '.command[]' "${MANIFEST}")
OUTDIR="${ROOT}/reports/experiments/${EXP}/${RUN_ID}"
[[ ! -e "${OUTDIR}" ]] || sc_die "${OUTDIR#"${ROOT}"/} already exists: results are never overwritten"

sc_stage run
printf 'SC-INFO command: %s (limit %ss)\n' "${mapfile_cmd[*]}" "${MAXRT}"
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf 'SC-DRYRUN timeout %s %s\n' "${MAXRT}" "${mapfile_cmd[*]}"
  sc_ok "dry run: nothing was run"
  exit 0
fi
mkdir -p "${OUTDIR}"
START="$(sc_utc_iso)"
RC=0
(cd "${ROOT}" && sc_timeout "${MAXRT}" "${mapfile_cmd[@]}") > "${OUTDIR}/command.log" 2>&1 || RC=$?
END="$(sc_utc_iso)"

sc_stage record
REC="$(jq -n --arg id "${EXP}" --arg run "${RUN_ID}" --arg sha "${SHA}" --arg s "${START}" --arg e "${END}" --argjson rc "${RC}" \
  --arg region "${AWS_REGION:-${AWS_DEFAULT_REGION:-}}" --slurpfile m "${MANIFEST}" \
  '{experiment: $id, run_id: $run, commit: $sha, started_at: $s, ended_at: $e, exit_code: $rc, status: (if $rc == 0 then "succeeded" elif $rc == 124 then "timed_out" else "failed" end),
    region: $region, resource_limits: ($m[0].resource_limits // {}), metric_locations: ($m[0].metric_locations // []), approved_by: $m[0].approved_by, manifest: ("experiments/manifests/" + $id + ".json")}')"
sc_json_write "${OUTDIR}/run.json" "${REC}"
if ((RC != 0)); then
  sc_warn "experiment ${EXP} ended with exit ${RC}: see reports/experiments/${EXP}/${RUN_ID}/command.log"
  exit 1
fi
sc_ok "experiment ${EXP} recorded: reports/experiments/${EXP}/${RUN_ID}/run.json"
