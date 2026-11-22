#!/usr/bin/env bash
# Phase 15a: bounded, cache-first, resumable enrichment fetch for the dblp-50k selection
# (dblp-10k is a subset of it, so one run covers both scales).
# Usage (from the repository root):  bash datasets/scripts/fetch_scale.sh [plan]
#   plan   print the --plan report for the three sources and make no network request
# Overrides (environment): SELECTION, ORCID_LIMIT, CROSSREF_MAX_REQUESTS, OPENALEX_MAX_REQUESTS,
#   ORCID_MAX_REQUESTS, PAUSE, ORCID_IDS_FILE.
# SCHOLARCHAIN_CONTACT_EMAIL (and optionally OPENALEX_API_KEY) are read by the Python scripts from
# the environment only; this script never reads, prints or logs them.
set -u -o pipefail
cd "$(dirname "$0")/../.." || exit 2

SELECTION="${SELECTION:-datasets/raw/dblp/derived/dblp_selection_50000.tsv}"
ORCID_LIMIT="${ORCID_LIMIT:-5000}"                       # cap on ORCID iDs (config); see FETCH-SCALE.md
CROSSREF_MAX_REQUESTS="${CROSSREF_MAX_REQUESTS:-2000}"   # hard caps, retries included
OPENALEX_MAX_REQUESTS="${OPENALEX_MAX_REQUESTS:-2000}"
ORCID_MAX_REQUESTS="${ORCID_MAX_REQUESTS:-5500}"
PAUSE="${PAUSE:-0.5}"
ORCID_IDS_FILE="${ORCID_IDS_FILE:-datasets/raw/orcid/orcid_ids_50k.json}"
S=datasets/scripts

MODE=""
[ "${1:-}" = "plan" ] && MODE="--plan"

N=$(($(wc -l < "$SELECTION") - 1))
START=$SECONDS
echo "fetch_scale: start $(date -u +%Y-%m-%dT%H:%M:%SZ) selection=$SELECTION works=$N mode=${MODE:-fetch}"

python3 -u "$S/fetch_crossref.py" --selection "$SELECTION" --limit "$N" --allow-more \
  --max-requests "$CROSSREF_MAX_REQUESTS" --max-run-seconds 21600 --pause "$PAUSE" --progress-every 25 $MODE \
  && python3 -u "$S/fetch_openalex.py" --selection "$SELECTION" --limit "$N" --allow-more \
  --max-requests "$OPENALEX_MAX_REQUESTS" --max-run-seconds 21600 --pause "$PAUSE" --progress-every 25 $MODE \
  && python3 -u "$S/fetch_orcid.py" --selection "$SELECTION" --limit "$ORCID_LIMIT" --allow-more \
  --max-requests "$ORCID_MAX_REQUESTS" --max-run-seconds 21600 --pause "$PAUSE" --progress-every 100 \
  --ids-file "$ORCID_IDS_FILE" $MODE
RC=$?
echo "fetch_scale: end $(date -u +%Y-%m-%dT%H:%M:%SZ) exit=$RC duration_seconds=$((SECONDS - START))"
if [ "$RC" -eq 3 ]; then echo "fetch_scale: a source stopped early (cap, time limit or repeated failures); re-run the same command to resume"; fi
exit "$RC"
