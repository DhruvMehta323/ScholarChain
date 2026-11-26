#!/usr/bin/env bash
# Post-only run R2: the local Spark output (Spark ran on the operator Mac on the EMR Hadoop outputs of run SRC) is uploaded to R2's Spark
# prefix, then run-bounded-pipeline.sh --stages convert, then --stages load. A new run id is needed because SRC's neptune-load prefix holds
# one partial object (AUTHORED.csv) from a failed convert upload and the script never overwrites; deleting it is human-only.
set -Eeuo pipefail
trap 'echo "S2-FAILED at ${STEP:-?} (line ${LINENO})"; exit 1' ERR
REPO="${HOME}/Desktop/ScholarChain"; cd "${REPO}"
export AWS_PROFILE=scholarchain AWS_REGION=us-east-2
SRC=r20261125T214348Z-63958
R2=r20261125T214348Z-63958-s2
LD="${HOME}/scholarchain-local/${SRC}"
SCR=/private/tmp/claude-501/-Users-dhruv-mehta-Desktop-ScholarChain/14bf29db-4f01-4f21-a562-40c2cf7c93d5/scratchpad
export SCHOLARCHAIN_JAVA_HOME="${SCR}/jdk17-heap10g"   # build.sbt:85 hook; F14-10 heap override for the LocalLoad fork
export SC_AWS_READ_TIMEOUT=600 AWS_MAX_ATTEMPTS=10 AWS_RETRY_MODE=adaptive   # F14-11 upload headroom (existing env knobs)
CUR="$(terraform -chdir=infra/terraform/environments/course output -raw curated_bucket)"
SP="graph/runs/10k/${R2}/spark"; SPARK_OUT="s3://${CUR}/${SP}"
cnt() { aws s3api list-objects-v2 --bucket "${CUR}" --prefix "$1" --query 'length(Contents || `[]`)' --output text | awk '{n+=$1} END {print n+0}'; }

STEP=upload
[[ "$(cnt "${SP}/")" == 0 ]] || { echo "R2 Spark prefix not empty; refusing"; exit 1; }
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
  want="$(wc -c < "${LD}/work/${f}" | tr -d ' ')"
  for a in 1 2 3; do
    aws s3api put-object --bucket "${CUR}" --key "${SP}/provenance/${f}" --body "${LD}/work/${f}" --query ETag --output text < /dev/null > /dev/null || true
    got="$(aws s3api head-object --bucket "${CUR}" --key "${SP}/provenance/${f}" --query ContentLength --output text 2> /dev/null || true)"
    [[ "${got}" == "${want}" ]] && break; sleep 5
  done
  [[ "${got}" == "${want}" ]] || { echo "provenance ${f} not uploaded"; exit 1; }
done
echo "upload verified: $(cnt "${SP}/") objects $(date -u +%TZ)"

STEP=convert
sbt shutdown > /dev/null 2>&1 || true
bash scripts/run-bounded-pipeline.sh --scale 10k --run-id "${R2}" --stages convert 2>&1 | tee "reports/pipeline/run-10k-${R2}-convert-$(date -u +%Y%m%dT%H%M%SZ).console.log"
[[ "$(jq -r .status "reports/pipeline/10k/${R2}/convert.json")" == succeeded ]] || { echo "convert not succeeded"; exit 1; }
echo "convert succeeded: $(jq '.uploads|length' "reports/pipeline/10k/${R2}/convert.json") uploads $(date -u +%TZ)"

STEP=load
bash scripts/run-bounded-pipeline.sh --scale 10k --run-id "${R2}" --stages load 2>&1 | tee "reports/pipeline/run-10k-${R2}-load-$(date -u +%Y%m%dT%H%M%SZ).console.log"
[[ "$(jq -r .status "reports/pipeline/10k/${R2}/load.json")" == succeeded ]] || { echo "load not succeeded"; exit 1; }
echo "S2-DONE $(date -u +%FT%TZ)"
