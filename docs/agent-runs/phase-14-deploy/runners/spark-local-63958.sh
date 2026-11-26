#!/usr/bin/env bash
# F14-8 fallback (human-authorised): run the Spark job LOCALLY on the operator Mac on the EMR Hadoop outputs of run R,
# then upload the Spark output to the run's SPARK_OUT prefix (only if empty). Convert/load run later through run-bounded-pipeline.sh.
set -Eeuo pipefail
trap 'echo "SPARK-LOCAL-FAILED (line ${LINENO})"; exit 1' ERR
REPO="${HOME}/Desktop/ScholarChain"; cd "${REPO}"
export AWS_PROFILE=scholarchain AWS_REGION=us-east-2
R=r20261125T214348Z-63958
CUR="$(terraform -chdir=infra/terraform/environments/course output -raw curated_bucket)"
OUT_ROOT="s3://${CUR}/curated/runs/10k/${R}"
SP="graph/runs/10k/${R}/spark"
SPARK_OUT="s3://${CUR}/${SP}"
LD="${HOME}/scholarchain-local/${R}"
CV="$(git rev-parse --short=12 HEAD)+spark-local-fallback"
cnt() { aws s3api list-objects-v2 --bucket "${CUR}" --prefix "$1" --query 'length(Contents || `[]`)' --output text | awk '{n+=$1} END {print n+0}'; }
echo "start $(date -u +%FT%TZ) run=${R} code=${CV}"
[[ "$(cnt "${SP}/")" == 0 ]] || { echo "SPARK_OUT not empty; refusing"; exit 1; }
[[ ! -e "${LD}/spark-out" ]] || { echo "${LD}/spark-out exists; refusing"; exit 1; }
mkdir -p "${LD}/in" "${LD}/work" "${LD}/checkpoints"
for pair in "curated:curated" "work/hj6a/entity-views:entity-views" "work/hj6d/match-candidates:match-candidates"; do
  src="${pair%%:*}" dst="${pair##*:}"
  n="$(cnt "curated/runs/10k/${R}/${src}/")"; echo "download ${src}/ (${n} objects) -> in/${dst}"
  [[ "${n}" -gt 0 ]] || { echo "no objects under ${src}"; exit 1; }
  aws s3 cp --recursive --only-show-errors "${OUT_ROOT}/${src}/" "${LD}/in/${dst}/" < /dev/null
  have="$(find "${LD}/in/${dst}" -type f | wc -l | tr -d ' ')"; [[ "${have}" == "${n}" ]] || { echo "downloaded ${have} of ${n}"; exit 1; }
done
HJAR="$(ls target/out/jvm/scala-*/scholarchain-hadoop/scholarchain-hadoop-*-assembly.jar | head -1)"
echo "FinalDecisions (policy only, no LLM cache): jar sha256 $(shasum -a 256 "${HJAR}" | cut -d' ' -f1)"
java -Xmx10g -XX:+ExitOnOutOfMemoryError -cp "${HJAR}" scholarchain.er.tools.FinalDecisions --candidates "${LD}/in/match-candidates" \
  --policy "${REPO}/modules/entity-resolution/evaluation/er-v1-frozen-policy.json" \
  --out "${LD}/work/match-decisions-final.jsonl" --summary "${LD}/work/match-decisions-final.summary.json" --code-version "${CV}"
echo "decisions: $(wc -l < "${LD}/work/match-decisions-final.jsonl" | tr -d ' ') lines sha256 $(shasum -a 256 "${LD}/work/match-decisions-final.jsonl" | cut -d' ' -f1)"
echo "Spark local start $(date -u +%FT%TZ)"
sbt "sparkAnalytics/Test/runMain scholarchain.spark.Run --master local[*] --output-root ${LD}/spark-out --code-version ${CV} --checkpoint-dir ${LD}/checkpoints --set input.root-i-dir=${LD}/in/curated --set input.hj6a-entity-views-dir=${LD}/in/entity-views --set input.match-decisions-final-path=${LD}/work/match-decisions-final.jsonl" < /dev/null
echo "Spark local end $(date -u +%FT%TZ)"
[[ -f "${LD}/spark-out/load-manifests/neptune.json" && -f "${LD}/spark-out/load-manifests/dynamodb.json" ]] || { echo "no load manifests"; exit 1; }
nf="$(find "${LD}/spark-out" -type f | wc -l | tr -d ' ')"; echo "local Spark output files: ${nf}"
[[ "$(cnt "${SP}/")" == 0 ]] || { echo "SPARK_OUT became non-empty; refusing"; exit 1; }
aws s3 cp --recursive --only-show-errors "${LD}/spark-out/" "${SPARK_OUT}/" < /dev/null
aws s3 cp --only-show-errors "${LD}/work/match-decisions-final.jsonl" "${SPARK_OUT}/provenance/match-decisions-final.jsonl" < /dev/null
aws s3 cp --only-show-errors "${LD}/work/match-decisions-final.summary.json" "${SPARK_OUT}/provenance/match-decisions-final.summary.json" < /dev/null
up="$(cnt "${SP}/")"; echo "uploaded objects under SPARK_OUT: ${up} (local ${nf} + 2 provenance)"
[[ "${up}" == "$((nf + 2))" ]] || { echo "upload count mismatch"; exit 1; }
echo "SPARK-LOCAL-DONE $(date -u +%FT%TZ)"
