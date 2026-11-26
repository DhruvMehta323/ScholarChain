#!/usr/bin/env bash
# Run s4 (post-only, C11 release 38f2160a6e95): upload the LOCAL Spark output (Spark ran on the operator Mac on the EMR Hadoop
# outputs of run SRC; disclosed fallback) to R's Spark prefix with full verification, then convert, load, and wait for embeddings.
# New run id because s2/s3 records are never overwritten. Stops at the first failure.
set -Eeuo pipefail
trap 'echo "S4-FAILED at ${STEP:-?} (line ${LINENO})"; exit 1' ERR
REPO="${HOME}/Desktop/ScholarChain"; cd "${REPO}"
export AWS_PROFILE=scholarchain AWS_REGION=us-east-2
SRC=r20261125T214348Z-63958
R=r20261125T214348Z-63958-s4
REL=38f2160a6e95
LD="${HOME}/scholarchain-local/${SRC}"
SCR=/private/tmp/claude-501/-Users-dhruv-mehta-Desktop-ScholarChain/14bf29db-4f01-4f21-a562-40c2cf7c93d5/scratchpad
export SCHOLARCHAIN_JAVA_HOME="${SCR}/jdk17-heap10g"   # build.sbt hook; F14-10 heap override for the LocalLoad fork
export SC_AWS_READ_TIMEOUT=600 AWS_MAX_ATTEMPTS=10 AWS_RETRY_MODE=adaptive
[[ "$(git rev-parse --short=12 HEAD)" == "${REL}" ]] || { echo "HEAD is not ${REL}"; exit 1; }
CUR="$(terraform -chdir=infra/terraform/environments/course output -raw curated_bucket)"
SP="graph/runs/10k/${R}/spark"; SPARK_OUT="s3://${CUR}/${SP}"
cnt() { aws s3api list-objects-v2 --bucket "${CUR}" --prefix "$1" --query 'length(Contents || `[]`)' --output text | awk '{n+=$1} END {print n+0}'; }

STEP=upload
[[ "$(cnt "${SP}/")" == 0 ]] || { echo "Spark prefix of ${R} not empty; refusing"; exit 1; }
nf="$(find "${LD}/spark-out" -type f | wc -l | tr -d ' ')"
echo "upload ${nf} local Spark files (source run ${SRC}) -> ${SPARK_OUT}/ $(date -u +%TZ)"
aws s3 cp --recursive --only-show-errors "${LD}/spark-out/" "${SPARK_OUT}/" < /dev/null || true
for a in 1 2 3 4; do
  up="$(cnt "${SP}/")"; echo "attempt ${a}: S3 ${up} of ${nf}"
  [[ "${up}" == "${nf}" ]] && break
  aws s3 sync --only-show-errors "${LD}/spark-out/" "${SPARK_OUT}/" < /dev/null || true
done
[[ "$(cnt "${SP}/")" == "${nf}" ]] || { echo "Spark upload incomplete"; exit 1; }
for f in match-decisions-final.jsonl.gz match-decisions-final.summary.json; do
  want="$(wc -c < "${LD}/work/${f}" | tr -d ' ')"; got=""
  for a in 1 2 3; do
    aws s3api put-object --bucket "${CUR}" --key "${SP}/provenance/${f}" --body "${LD}/work/${f}" --query ETag --output text < /dev/null > /dev/null || true
    got="$(aws s3api head-object --bucket "${CUR}" --key "${SP}/provenance/${f}" --query ContentLength --output text 2> /dev/null || true)"
    [[ "${got}" == "${want}" ]] && break; sleep 5
  done
  [[ "${got}" == "${want}" ]] || { echo "provenance ${f} not uploaded"; exit 1; }
done
echo "upload verified: $(cnt "${SP}/") objects $(date -u +%TZ)"

STEP=convert
sbt shutdown > /dev/null 2>&1 < /dev/null || true   # s3 lesson: a stale server lacks SCHOLARCHAIN_JAVA_HOME
bash scripts/run-bounded-pipeline.sh --scale 10k --run-id "${R}" --stages convert > "reports/pipeline/run-10k-${R}-convert-$(date -u +%Y%m%dT%H%M%SZ).console.log" 2>&1 < /dev/null
[[ "$(jq -r .status "reports/pipeline/10k/${R}/convert.json")" == succeeded ]] || { echo "convert not succeeded"; exit 1; }
echo "convert succeeded: $(jq '.uploads|length' "reports/pipeline/10k/${R}/convert.json") uploads $(date -u +%TZ)"

STEP=load
bash scripts/run-bounded-pipeline.sh --scale 10k --run-id "${R}" --stages load > "reports/pipeline/run-10k-${R}-load-$(date -u +%Y%m%dT%H%M%SZ).console.log" 2>&1 < /dev/null
[[ "$(jq -r .status "reports/pipeline/10k/${R}/load.json")" == succeeded ]] || { echo "load not succeeded"; exit 1; }
echo "load succeeded $(date -u +%TZ)"

STEP=wait-embeddings
bash scripts/wait-embeddings.sh --scale 10k --run-id "${R}" > "reports/pipeline/run-10k-${R}-wait-embeddings-$(date -u +%Y%m%dT%H%M%SZ).console.log" 2>&1 < /dev/null
echo "embeddings: $(jq -c . "reports/pipeline/10k/${R}/embeddings.json")"
echo "S4-DONE $(date -u +%FT%TZ)"
