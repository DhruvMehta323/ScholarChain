#!/usr/bin/env bash
# Phase 14 steps e (10K run with automatic local-Spark fallback), f (smoke test), g (evidence archive) for release C7 8ce398029bf7.
# Run by the orchestrator under the human's delegated authority. Stops at the first failure: PHASE14-RUN-FAILED at <step>.
set -Eeuo pipefail

STEP=init
trap 'echo "PHASE14-RUN-FAILED at ${STEP} (line ${LINENO})"; exit 1' ERR
fail() { echo "PHASE14-RUN-FAILED at ${STEP}${1:+: $1}"; exit 1; }
step() { STEP="$1"; echo ""; echo "==> PHASE14 STEP ${STEP} $(date -u +%H:%M:%SZ)"; }

REPO="${HOME}/Desktop/ScholarChain"
cd "${REPO}"
export AWS_PROFILE=scholarchain AWS_REGION=us-east-2

REL=8ce398029bf7   # C7
FULL="$(git rev-parse HEAD)"
DAY="$(date -u +%Y-%m-%d)"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
LOG=reports/deployments
ENV_DIR=infra/terraform/environments/course
KEY_FILE="${HOME}/.scholarchain/services-api-key"
step preflight
[[ "$(git rev-parse --short=12 HEAD)" == "${REL}" ]] || fail "HEAD is not ${REL}"
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || fail "tracked files are modified"
[[ -f "${KEY_FILE}" ]] || fail "smoke key file missing"
step e-10k-run
# The release's 10K run (at most one EMR run per release; it is never rerun automatically).
find_run() { local rj; RJ=""; for rj in reports/pipeline/10k/*/run.json; do
  [[ -f "${rj}" ]] || continue; if [[ "$(jq -r '.commit // empty' "${rj}")" == "${FULL}" ]]; then RJ="${rj}"; fi; done; return 0; }
loaded() { [[ -f "$1/load.json" && "$(jq -r '.status // empty' "$1/load.json")" == succeeded ]]; }
find_run
if [[ -z "${RJ}" ]]; then
  echo "starting the EMR 10K run (Hadoop HJ1-HJ7 and Spark on EMR, then convert and load)"
  if bash scripts/run-bounded-pipeline.sh --scale 10k \
      --dblp-xml datasets/raw/dblp/derived/dblp_selection_10000.xml \
      --dtd datasets/raw/dblp/dblp.dtd \
      --enrichment-dir datasets/raw/dblp/derived/enrichment-10k 2>&1 \
      | tee "reports/pipeline/run-10k-${TS}.console.log"; then
    echo "EMR 10K run script exited 0"
  else
    echo "EMR 10K run script exited non-zero; checking whether the local-Spark fallback applies"
  fi
  find_run
  [[ -n "${RJ}" ]] || fail "the 10K run wrote no run.json for this release"
fi
RD="$(dirname "${RJ}")"
RUN_ID="$(jq -r '.run_id' "${RJ}")"
echo "release 10K run: ${RUN_ID} (status $(jq -r .status "${RJ}"))"

if loaded "${RD}"; then
  echo "SKIP: ${RD}/load.json already shows status succeeded (graph loaded)"
else
  # ---- e2: automatic fallback (decided by the human): Spark LOCALLY on this Mac on the EMR Hadoop outputs, then convert and load.
  STEP=e2-fallback-check
  CID="$(jq -r '.cluster_id' "${RJ}")"
  ST_H="$(aws emr list-steps --cluster-id "${CID}" --query "Steps[?Name=='hadoop-pipeline-HJ1-HJ7'].Status.State | [0]" --output text)"
  ST_S="$(aws emr list-steps --cluster-id "${CID}" --query "Steps[?Name=='spark-graph-analytics'].Status.State | [0]" --output text)"
  echo "EMR steps on ${CID}: hadoop=${ST_H} spark=${ST_S}"
  [[ "${ST_H}" == COMPLETED ]] || fail "the Hadoop step on EMR is ${ST_H}: no Hadoop outputs, no fallback possible (diagnose)"
  CURATED="$(terraform -chdir="${ENV_DIR}" output -raw curated_bucket)"
  OUT_ROOT="s3://${CURATED}/curated/runs/10k/${RUN_ID}"
  SPARK_PREFIX="graph/runs/10k/${RUN_ID}/spark"
  SPARK_OUT="s3://${CURATED}/${SPARK_PREFIX}"
  have_spark_out() { aws s3api head-object --bucket "${CURATED}" --key "${SPARK_PREFIX}/load-manifests/neptune.json" > /dev/null 2>&1 \
    && aws s3api head-object --bucket "${CURATED}" --key "${SPARK_PREFIX}/load-manifests/dynamodb.json" > /dev/null 2>&1; }
  any_spark_out() { [[ "$(aws s3api list-objects-v2 --bucket "${CURATED}" --prefix "${SPARK_PREFIX}/" --max-items 1 --query 'length(Contents || `[]`)' --output text)" != 0 ]]; }

  if [[ "${ST_S}" == COMPLETED ]] || have_spark_out; then
    echo "Spark output already complete in ${SPARK_OUT}/ (no local Spark needed)"
  else
    any_spark_out && fail "${SPARK_OUT}/ is partially written (no load manifests): not overwriting, diagnose"
    if pgrep -f 'sbt.*[[:space:]]check([[:space:];]|$)' > /dev/null 2>&1; then fail "an sbt check is running"; fi
    STEP=e2-spark-local
    echo "FALLBACK: the Spark step on EMR is ${ST_S}; running Spark LOCALLY on this Mac on the EMR Hadoop outputs (disclosed in agent-report.md)"
    LD="${HOME}/scholarchain-local/${RUN_ID}"
    [[ ! -e "${LD}/spark-out" ]] || fail "${LD}/spark-out exists from an earlier attempt: move it aside, then rerun"
    mkdir -p "${LD}/in" "${LD}/work" "${LD}/checkpoints"
    FB_LOG="${RD}/spark-local-fallback.log"
    {
      echo "fallback started $(date -u +%Y-%m-%dT%H:%M:%SZ) run=${RUN_ID} cluster=${CID} hadoop=${ST_H} spark=${ST_S} commit=${FULL}"
      for d in curated work/hj6a/entity-views work/hj6d/match-candidates; do
        name="$(basename "${d}")"; [[ "${d}" == curated ]] && name=curated
        echo "download ${OUT_ROOT}/${d}/ -> ${LD}/in/${name}"
        [[ -d "${LD}/in/${name}" ]] || aws s3 cp --recursive --only-show-errors "${OUT_ROOT}/${d}/" "${LD}/in/${name}/"
      done
      HJAR="$(ls target/out/jvm/scala-*/scholarchain-hadoop/scholarchain-hadoop-*-assembly.jar | head -1)"
      echo "FinalDecisions (policy-only, no LLM cache) with ${HJAR} sha256 $(shasum -a 256 "${HJAR}" | cut -d' ' -f1)"
      [[ -f "${LD}/work/match-decisions-final.jsonl" ]] || java -cp "${HJAR}" scholarchain.er.tools.FinalDecisions \
        --candidates "${LD}/in/match-candidates" \
        --policy "${REPO}/modules/entity-resolution/evaluation/er-v1-frozen-policy.json" \
        --out "${LD}/work/match-decisions-final.jsonl" --summary "${LD}/work/match-decisions-final.summary.json" --code-version "${REL}"
      echo "decisions sha256 $(shasum -a 256 "${LD}/work/match-decisions-final.jsonl" | cut -d' ' -f1) lines $(wc -l < "${LD}/work/match-decisions-final.jsonl" | tr -d ' ')"
      echo "Spark local (sbt Test classpath, SPARK-CONTRACTS.md section 1)"
      sbt "sparkAnalytics/Test/runMain scholarchain.spark.Run --master local[*] --output-root ${LD}/spark-out --code-version ${REL}+spark-local-fallback --checkpoint-dir ${LD}/checkpoints --set input.root-i-dir=${LD}/in/curated --set input.hj6a-entity-views-dir=${LD}/in/entity-views --set input.match-decisions-final-path=${LD}/work/match-decisions-final.jsonl"
      [[ -f "${LD}/spark-out/load-manifests/neptune.json" && -f "${LD}/spark-out/load-manifests/dynamodb.json" ]] || { echo "local Spark output lacks load manifests"; exit 1; }
      echo "local Spark output: $(find "${LD}/spark-out" -type f | wc -l | tr -d ' ') files"
      any_spark_out && { echo "${SPARK_OUT}/ became non-empty; not overwriting"; exit 1; }
      echo "upload ${LD}/spark-out/ -> ${SPARK_OUT}/"
      aws s3 cp --recursive --only-show-errors "${LD}/spark-out/" "${SPARK_OUT}/"
      aws s3 cp --only-show-errors "${LD}/work/match-decisions-final.jsonl" "${SPARK_OUT}/provenance/match-decisions-final.jsonl"
      aws s3 cp --only-show-errors "${LD}/work/match-decisions-final.summary.json" "${SPARK_OUT}/provenance/match-decisions-final.summary.json"
      echo "fallback Spark done $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } 2>&1 | tee -a "${FB_LOG}"
    have_spark_out || fail "after the local Spark upload, ${SPARK_OUT}/load-manifests are missing"
  fi

  STEP=e3-convert-load
  if [[ -f "${RD}/convert.json" ]]; then POST=load; else POST=convert,load; fi
  echo "post-only run: --stages ${POST} --run-id ${RUN_ID}"
  bash scripts/run-bounded-pipeline.sh --scale 10k --run-id "${RUN_ID}" --stages "${POST}" 2>&1 \
    | tee "reports/pipeline/run-10k-${RUN_ID}-post-${TS}.console.log"
  loaded "${RD}" || fail "load.json for ${RUN_ID} is not succeeded"
fi
echo "10K graph loaded: ${RD}/load.json status succeeded"

# ---------------------------------------------------------------------------------------------------------------------------------
step f-smoke
SMOKE_REC="${LOG}/${FULL}/smoke-test.json"
if [[ -f "${SMOKE_REC}" && "$(jq -r '.all_passed' "${SMOKE_REC}")" == true ]]; then
  echo "SKIP: ${SMOKE_REC} already shows all_passed true"
else
  SC_SMOKE_API_KEY_FILE="${KEY_FILE}" bash scripts/smoke-test.sh --allow-insecure-http 2>&1 | tee "${LOG}/${DAY}-smoke-${REL}-${TS}.log"
fi

# ---------------------------------------------------------------------------------------------------------------------------------
step g-archive
if [[ -d "reports/evidence/${FULL}" ]]; then
  echo "SKIP: reports/evidence/${FULL} already exists"
else
  bash scripts/archive-evidence.sh --metrics-hours 6 2>&1 | tee "${LOG}/${DAY}-archive-${REL}-${TS}.log"
fi

STEP=finished
echo ""
echo "PHASE14-RUN-DONE"
