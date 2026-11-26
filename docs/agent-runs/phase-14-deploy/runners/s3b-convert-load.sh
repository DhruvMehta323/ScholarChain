#!/usr/bin/env bash
# Run s3, retry of convert + load only. The Spark upload of run R2 is already verified (839 objects, 18:25:11Z).
# The first convert hit OOM because a stale sbt server (started without SCHOLARCHAIN_JAVA_HOME) ran the LocalLoad fork at -Xmx2g.
# This script stops any sbt server first, so the server starts fresh with the heap wrapper in its environment.
set -Eeuo pipefail
trap 'echo "S3B-FAILED at ${STEP:-?} (line ${LINENO})"; exit 1' ERR
REPO="${HOME}/Desktop/ScholarChain"; cd "${REPO}"
export AWS_PROFILE=scholarchain AWS_REGION=us-east-2
R2=r20261125T214348Z-63958-s3
SCR=/private/tmp/claude-501/-Users-dhruv-mehta-Desktop-ScholarChain/14bf29db-4f01-4f21-a562-40c2cf7c93d5/scratchpad
export SCHOLARCHAIN_JAVA_HOME="${SCR}/jdk17-heap10g"
export SC_AWS_READ_TIMEOUT=600 AWS_MAX_ATTEMPTS=10 AWS_RETRY_MODE=adaptive
sbt shutdown > /dev/null 2>&1 < /dev/null || true

STEP=convert
bash scripts/run-bounded-pipeline.sh --scale 10k --run-id "${R2}" --stages convert > "reports/pipeline/run-10k-${R2}-convert-$(date -u +%Y%m%dT%H%M%SZ).console.log" 2>&1 < /dev/null
[[ "$(jq -r .status "reports/pipeline/10k/${R2}/convert.json")" == succeeded ]] || { echo "convert not succeeded"; exit 1; }
echo "convert succeeded: $(jq '.uploads|length' "reports/pipeline/10k/${R2}/convert.json") uploads $(date -u +%TZ)"

STEP=load
bash scripts/run-bounded-pipeline.sh --scale 10k --run-id "${R2}" --stages load > "reports/pipeline/run-10k-${R2}-load-$(date -u +%Y%m%dT%H%M%SZ).console.log" 2>&1 < /dev/null
[[ "$(jq -r .status "reports/pipeline/10k/${R2}/load.json")" == succeeded ]] || { echo "load not succeeded"; exit 1; }
echo "S3B-DONE $(date -u +%FT%TZ)"
