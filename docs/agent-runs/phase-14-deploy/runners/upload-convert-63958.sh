#!/usr/bin/env bash
# Waits for valid AWS credentials, then uploads the LOCAL Spark output (F14-8/F14-9 fallback, Spark ran on the operator Mac)
# to the Spark prefix of run R (only if empty, every object counted), then runs the post-only convert stage.
set -Eeuo pipefail
trap 'echo "UPLOAD-CONVERT-FAILED (line ${LINENO})"; exit 1' ERR
REPO="${HOME}/Desktop/ScholarChain"; cd "${REPO}"
export AWS_PROFILE=scholarchain AWS_REGION=us-east-2
R=r20261125T214348Z-63958
LD="${HOME}/scholarchain-local/${R}"
for i in $(seq 1 150); do aws sts get-caller-identity > /dev/null 2>&1 && break; sleep 20; done
aws sts get-caller-identity > /dev/null 2>&1 || { echo "credentials still expired after 50 min"; exit 1; }
echo "credentials ok $(date -u +%FT%TZ)"
CUR="$(terraform -chdir=infra/terraform/environments/course output -raw curated_bucket)"
SP="graph/runs/10k/${R}/spark"; SPARK_OUT="s3://${CUR}/${SP}"
cnt() { aws s3api list-objects-v2 --bucket "${CUR}" --prefix "$1" --query 'length(Contents || `[]`)' --output text | awk '{n+=$1} END {print n+0}'; }
[[ "$(cnt "${SP}/")" == 0 ]] || { echo "SPARK_OUT not empty; refusing"; exit 1; }
nf="$(find "${LD}/spark-out" -type f | wc -l | tr -d ' ')"
echo "uploading ${nf} files to ${SPARK_OUT}/"
aws s3 cp --recursive --only-show-errors "${LD}/spark-out/" "${SPARK_OUT}/" < /dev/null
aws s3 cp --only-show-errors "${LD}/work/match-decisions-final.jsonl" "${SPARK_OUT}/provenance/match-decisions-final.jsonl" < /dev/null
aws s3 cp --only-show-errors "${LD}/work/match-decisions-final.summary.json" "${SPARK_OUT}/provenance/match-decisions-final.summary.json" < /dev/null
for attempt in 1 2 3; do
  up="$(cnt "${SP}/")"; echo "S3 objects ${up}, expected $((nf + 2))"
  [[ "${up}" == "$((nf + 2))" ]] && break
  # F14-7: re-copy (idempotent overwrite of same bytes) any file that did not land
  aws s3 sync --only-show-errors "${LD}/spark-out/" "${SPARK_OUT}/" < /dev/null
done
[[ "$(cnt "${SP}/")" == "$((nf + 2))" ]] || { echo "upload count mismatch"; exit 1; }
echo "upload verified $(date -u +%FT%TZ)"
bash scripts/run-bounded-pipeline.sh --scale 10k --run-id "${R}" --stages convert 2>&1 | tee "reports/pipeline/run-10k-${R}-convert-$(date -u +%Y%m%dT%H%M%SZ).console.log"
jq -c '{status, uploads:(.uploads|length)}' "reports/pipeline/10k/${R}/convert.json"
echo "UPLOAD-CONVERT-DONE $(date -u +%FT%TZ)"
