#!/usr/bin/env bash
# scripts/run-bounded-pipeline.sh --scale 1k|10k|50k [options]      or      --smoke
#
# One bounded pipeline run on a self-terminating EMR cluster. Uploads the DBLP XML, DTD and enrichment cache to the raw bucket (a few
# large objects), uploads the job jars to the artifacts bucket, creates ONE cluster from the Phase 12 configuration (1 primary + at
# most 2 core/task nodes = 3 nodes, on-demand m5.xlarge, idle auto-termination, logs to S3) and runs, one step at a time:
#   hadoop (HJ1 to HJ7) -> spark (graph and analytics) -> load (storage loader) -> enqueue (embedding jobs)
# Every step has ActionOnFailure=TERMINATE_CLUSTER and a 1-hour limit (enforced twice: `timeout 3600` inside the step, and a watcher
# here that cancels a step still running after 1 hour plus a grace period and shortens the idle timeout). The script never calls
# terminate-clusters and never touches Terraform: EMR terminates the cluster itself.
# Records go to reports/pipeline/<scale>/<run>/ (run.json, steps.json, config).
#
# 50K RULE: refused unless a 10K run record with status "succeeded" exists under reports/pipeline/10k/ AND --approve-50k is passed.
# --smoke: a tiny MapReduce job (hadoop-mapreduce-examples pi) on the chosen release, to confirm MapReduce works before real work.
#
# Environment (names only):
#   AWS_PROFILE (required), AWS_REGION (must equal the Terraform output "region")
#   SC_EMR_INSTANCE_TYPE   default and only allowed value m5.xlarge (INFRA-CONTRACTS section 6). Any other class is refused (exit 3) unless
#                          --approve-instance-class and a --gate-file (docs/agent-runs/<phase>/human-gate.md containing the type) are given.
#   SC_EMR_IDLE_TIMEOUT    default 900, allowed 60 to 900 (seconds; Terraform uses 900)
#   SC_STEP_TIMEOUT        default and maximum 3600
#   SC_LOADER_STEP_ARGS    JSON array: the command-runner arguments of the load step (no EMR entry point exists on lane2: M13-2)
#   SC_ENQUEUE_STEP_ARGS   JSON array: the command-runner arguments of the embedding-enqueue step (M13-2)
#   SC_SPARK_SET           extra "--set key=value" pairs for the Spark job, space separated, no quotes inside
#   SC_EXTRA_TAGS          extra EMR tags "k=v k=v" (course, owner, expiration-date are not Terraform outputs; add them here)
# Options:
#   --scale 1k|10k|50k      --smoke      --approve-50k      --hadoop-fallback (emr-7.14.0 + Hadoop; MapReduce stages only)
#   --stages hadoop,spark,load,enqueue (default all)      --hadoop-mode driver (the only mode: ONE PipelineDriver step runs HJ1 to HJ7;
#                          the per-job *Tool classes have no main, modules/hadoop/JOB-CONTRACTS.md; the old "tools" mode was removed)
#   --approve-instance-class --gate-file <file>   see SC_EMR_INSTANCE_TYPE
#   --core-count 1|2 (default 1)      --run-id <token>      --env <name> (default course)
#   --dblp-xml <file> --dtd <file> --enrichment-dir <dir with crossref/ openalex/ orcid/>      --skip-upload
#   --dry-run (no AWS write, no cluster; prints the exact plan and step definitions)      --help
# Exit codes: 0 ok, 1 failure or step failed, 2 usage, 3 refused (50K rule, dirty tree), 4 outputs missing, 5 timeout, 6 precondition (Neptune off,
# no jar), 7 unconfigured step (M13-2). 3 also covers an existing --run-id (records are never overwritten) and an unapproved instance class.
# shellcheck disable=SC2015,SC2329 # intentional: literal text in single quotes, JMESPath backticks, A && B || C status idiom, trap handler
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
export TZ=UTC # the step-timing parser below also accepts any +-HH:MM offset (review M4)

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

SCALE="" SMOKE=0 APPROVE_50K=0 FALLBACK=0 STAGES="hadoop,spark,load,enqueue" HMODE=driver CORE=1 APPROVE_ITYPE=0 GATE_FILE="" RUN_ID="" ENV_NAME=course
DBLP_XML="" DTD="" ENRICH_DIR="" SKIP_UPLOAD=0
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --scale) SCALE="${2:?}"; shift ;;
    --smoke) SMOKE=1 ;;
    --approve-50k) APPROVE_50K=1 ;;
    --hadoop-fallback) FALLBACK=1 ;;
    --stages) STAGES="${2:?}"; shift ;;
    --hadoop-mode) HMODE="${2:?}"; shift ;;
    --approve-instance-class) APPROVE_ITYPE=1 ;;
    --gate-file) GATE_FILE="${2:?}"; shift ;;
    --core-count) CORE="${2:?}"; shift ;;
    --run-id) RUN_ID="${2:?}"; shift ;;
    --env) ENV_NAME="${2:?}"; shift ;;
    --dblp-xml) DBLP_XML="${2:?}"; shift ;;
    --dtd) DTD="${2:?}"; shift ;;
    --enrichment-dir) ENRICH_DIR="${2:?}"; shift ;;
    --skip-upload) SKIP_UPLOAD=1 ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done
if [[ "${SMOKE}" == 1 ]]; then
  [[ -n "${SCALE}" ]] || SCALE=smoke
  [[ "${SCALE}" == smoke ]] || { echo "--smoke runs alone: do not combine it with --scale" >&2; exit 2; }
fi
[[ "${SCALE}" =~ ^(1k|10k|50k|smoke)$ ]] || { echo "--scale must be 1k, 10k or 50k (or use --smoke)" >&2; exit 2; }
[[ "${CORE}" =~ ^[12]$ ]] || { echo "--core-count must be 1 or 2 (1 primary + at most 2 core/task = 3 nodes)" >&2; exit 2; }
[[ "${HMODE}" == driver ]] || { echo "--hadoop-mode must be driver: the per-job *Tool classes have no main (PipelineDriver is the entry point, modules/hadoop/JOB-CONTRACTS.md)" >&2; exit 2; }
ITYPE="${SC_EMR_INSTANCE_TYPE:-m5.xlarge}"
IDLE="${SC_EMR_IDLE_TIMEOUT:-900}"
[[ "${ITYPE}" =~ ^[a-z][a-z0-9-]*\.[a-z0-9]+$ ]] || { echo "SC_EMR_INSTANCE_TYPE is not an instance type name" >&2; exit 2; }
[[ "${IDLE}" =~ ^[0-9]+$ ]] && ((IDLE >= 60 && IDLE <= 900)) || { echo "SC_EMR_IDLE_TIMEOUT must be 60 to 900 seconds (Terraform uses 900; a longer idle tail costs money)" >&2; exit 2; }
if [[ "${ITYPE}" != m5.xlarge ]]; then
  if [[ "${APPROVE_ITYPE}" != 1 ]]; then
    sc_warn "REFUSED: instance class ${ITYPE} is not the contracted m5.xlarge; larger classes need human approval with a cost estimate (docs/agents.md section 4): pass --approve-instance-class and --gate-file"
    exit 3
  fi
  sc_require_gate_file "${ROOT}" "${GATE_FILE}" "${ITYPE}"
  sc_warn "instance class ${ITYPE} approved by the gate file (cost impact: recorded in the human decision)"
fi
for st in $(printf '%s' "${STAGES}" | tr ',' ' '); do [[ "${st}" =~ ^(hadoop|spark|load|enqueue)$ ]] || { echo "unknown stage: ${st}" >&2; exit 2; }; done
STEP_TIMEOUT="${SC_STEP_TIMEOUT:-3600}"
[[ "${STEP_TIMEOUT}" =~ ^[0-9]+$ ]] && ((STEP_TIMEOUT >= 60 && STEP_TIMEOUT <= 3600)) || { echo "SC_STEP_TIMEOUT must be 60 to 3600 seconds (the limit is one hour)" >&2; exit 2; }
[[ -n "${RUN_ID}" ]] || RUN_ID="r$(sc_utc)-$$"
sc_valid_token "${RUN_ID}"
has_stage() { case ",${STAGES}," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }
if [[ "${FALLBACK}" == 1 && "${SMOKE}" != 1 ]] && { has_stage spark || has_stage load || has_stage enqueue; }; then
  echo "--hadoop-fallback (emr-7.14.0, Spark 3.5.8) is for MapReduce only: use --stages hadoop (Spark jobs are built for Spark 4.1.1)" >&2
  exit 2
fi

sc_stage preflight
sc_require_cmd git terraform aws jq
: "${AWS_PROFILE:?AWS_PROFILE must be set}"

# --- The 50K rule comes first: it needs no AWS call and no outputs. -------------------------------------------------------------
sc_stage rule-50k
if [[ "${SCALE}" == 50k ]]; then
  TEN_K=""
  for f in "${ROOT}"/reports/pipeline/10k/*/run.json; do
    [[ -f "${f}" ]] || continue
    if jq -e '.scale == "10k" and .status == "succeeded"' "${f}" > /dev/null 2>&1; then TEN_K="${f}"; fi
  done
  if [[ -z "${TEN_K}" ]]; then
    sc_warn "REFUSED: no successful 10K run record under reports/pipeline/10k/. Run 10K first, measure its cost (INFRA-CONTRACTS section 5b Rules A and E)."
    exit 3
  fi
  if [[ "${APPROVE_50K}" != 1 ]]; then
    sc_warn "REFUSED: 50K needs explicit human approval: pass --approve-50k only after the human has recorded the decision."
    exit 3
  fi
  sc_ok "50K allowed: 10K record $(basename "$(dirname "${TEN_K}")") exists and --approve-50k was given"
else
  sc_info "scale ${SCALE}: the 50K rule does not apply"
fi
sc_require_clean_tree "${ROOT}"
SHA="$(git -C "${ROOT}" rev-parse HEAD)"
SHORT="$(git -C "${ROOT}" rev-parse --short=12 HEAD)"

sc_stage outputs-and-identity
sc_load_outputs "${ROOT}" "${ENV_NAME}"
sc_guard_account_region
RAW="$(sc_out raw_bucket)" CURATED="$(sc_out curated_bucket)" ARTIFACTS="$(sc_out artifacts_bucket)" LOGS="$(sc_out logs_bucket)"
for v in RAW CURATED ARTIFACTS LOGS; do [[ -n "${!v}" ]] || sc_die "Terraform output for ${v} bucket is empty"; done
SERVICE_ROLE="$(basename "$(sc_out emr_service_role_arn)")"
PROFILE_NAME="$(basename "$(sc_out emr_instance_profile_arn)")"
SUBNET="$(sc_out_f public_subnet_ids '.[0]')"
VPC="$(sc_out vpc_id)"
[[ -n "${SERVICE_ROLE}" && -n "${PROFILE_NAME}" && "${SUBNET}" != null && -n "${SUBNET}" ]] || sc_die "EMR roles or subnet missing from the Terraform outputs"
# The EMR security group is not a Terraform output (gap, reported): find it by name in the VPC (read-only).
EMR_SG="$(sc_retry sc_aws ec2 describe-security-groups --filters "Name=vpc-id,Values=${VPC}" "Name=group-name,Values=*-emr" --query 'SecurityGroups[0].GroupId' --output text)" || sc_die "cannot look up the EMR security group"
[[ "${EMR_SG}" =~ ^sg-[0-9a-f]+$ ]] || sc_die "EMR security group not found in the VPC"
if has_stage load && [[ "${SMOKE}" != 1 ]]; then
  [[ -n "$(sc_out neptune_endpoint)" ]] || { sc_warn "the load stage needs Neptune: neptune_endpoint is empty (create_neptune = false). See: bash scripts/env-window.sh up"; exit 6; }
fi

# --- Cluster configuration (matches modules/pipeline: release label, 3-node cap, idle timeout). -----------------------------------
if [[ "${FALLBACK}" == 1 ]]; then RELEASE="emr-7.14.0" APPS=(Name=Hadoop Name=Spark); else RELEASE="emr-spark-8.1.0" APPS=(Name=Spark); fi
NODES=$((1 + CORE))
((NODES <= 3)) || sc_die "refusing: more than 3 EMR nodes"
sc_info "EMR release=${RELEASE} nodes=${NODES} (1 primary + ${CORE} core; managed scaling max 2 core/task) type=${ITYPE} idle_timeout=${IDLE}s step_timeout=${STEP_TIMEOUT}s"
case "${SCALE}" in
  50k) sc_info "cost note (INFRA-CONTRACTS section 10): one 50K run is about \$2.47 of EMR; cluster about \$0.76 per hour" ;;
  10k) sc_info "cost note: a 10K run is about \$1.33 of EMR" ;;
  smoke) sc_info "cost note: a smoke run is about \$0.95 of EMR" ;;
esac

# --- Locations. -----------------------------------------------------------------------------------------------------------------------
RAW_PREFIX="s3://${RAW}/raw/${SCALE}/${RUN_ID}"
JAR_PREFIX="s3://${ARTIFACTS}/jars/${SHORT}"
OUT_ROOT="s3://${CURATED}/curated/runs/${SCALE}/${RUN_ID}"
SPARK_OUT="s3://${CURATED}/graph/runs/${SCALE}/${RUN_ID}/spark"
REPORT_DIR="${ROOT}/reports/pipeline/${SCALE}/${RUN_ID}"
if [[ "${SC_DRY_RUN}" != 1 && -e "${REPORT_DIR}" ]]; then
  sc_warn "REFUSED: run id ${RUN_ID} already has records at reports/pipeline/${SCALE}/${RUN_ID}/ (and S3 inputs/results under it): records are never overwritten, use a new --run-id"
  exit 3
fi
SNAPSHOT="dblp-${SCALE}"
[[ "${SCALE}" != smoke ]] || SNAPSHOT="dblp-smoke"

# --- Uploads. -------------------------------------------------------------------------------------------------------------------------
HJAR="scholarchain-hadoop-assembly.jar" SJAR="scholarchain-spark-assembly.jar"
HJAR_LOCAL="" SJAR_LOCAL=""
UPLOADS='[]'
sc_stage jars
ART="${ROOT}/reports/release/${SHA}/artifacts.json"
if [[ -f "${ART}" ]]; then
  HJAR_LOCAL="${ROOT}/$(jq -r '.jars[] | select(.module == "hadoop") | .path' "${ART}")"
  SJAR_LOCAL="${ROOT}/$(jq -r '.jars[] | select(.module == "spark") | .path' "${ART}")"
  HJAR="$(basename "${HJAR_LOCAL}")" SJAR="$(basename "${SJAR_LOCAL}")"
elif [[ "${SC_DRY_RUN}" == 1 ]]; then
  sc_warn "reports/release/<sha>/artifacts.json is absent (dry run continues with placeholder jar names; run scripts/package.sh before a real run)"
else
  sc_warn "run scripts/package.sh for this commit first (no reports/release/${SHA}/artifacts.json)"
  exit 6
fi
if [[ "${SKIP_UPLOAD}" == 1 ]]; then sc_info "uploads skipped (--skip-upload): the objects must already exist"; fi
upload() { # upload <local-file> <s3-uri>: one object; recorded with its SHA-256
  local f="$1" uri="$2" h=""
  if [[ "${SC_DRY_RUN}" == 1 || ! -f "${f}" ]]; then
    printf 'SC-DRYRUN aws s3 cp %s %s\n' "${f}" "${uri}"
    [[ "${SC_DRY_RUN}" == 1 ]] || sc_die "missing input file: ${f}"
  else
    h="$(sc_sha256 "${f}")"
    sc_mut sc_aws s3 cp "${f}" "${uri}" --only-show-errors --no-progress
  fi
  UPLOADS="$(printf '%s' "${UPLOADS}" | jq --arg f "$(basename "${f}")" --arg u "${uri}" --arg h "${h}" '. + [{file: $f, uri: $u, sha256: $h}]')"
}
if [[ "${SKIP_UPLOAD}" != 1 ]]; then
  [[ -z "${HJAR_LOCAL}" ]] || upload "${HJAR_LOCAL}" "${JAR_PREFIX}/${HJAR}"
  [[ -z "${SJAR_LOCAL}" ]] || upload "${SJAR_LOCAL}" "${JAR_PREFIX}/${SJAR}"
fi
if [[ "${SMOKE}" != 1 ]] && has_stage hadoop; then
  sc_stage upload-inputs
  [[ -n "${DBLP_XML}" && -n "${DTD}" ]] || { sc_warn "--dblp-xml and --dtd are required for the hadoop stage"; exit 2; }
  if [[ "${SKIP_UPLOAD}" != 1 ]]; then
    upload "${DBLP_XML}" "${RAW_PREFIX}/dblp.xml"
    upload "${DTD}" "${RAW_PREFIX}/dblp.dtd"
    if [[ -n "${ENRICH_DIR}" ]]; then
      for src in crossref openalex orcid; do
        [[ -d "${ENRICH_DIR}/${src}" ]] || continue
        n="$(find "${ENRICH_DIR}/${src}" -type f | wc -l | tr -d ' ')"
        # Rule F: a few large objects, not one per work (S3 request counts drive cost).
        ((n <= ${SC_MAX_UPLOAD_OBJECTS:-200})) || sc_die "enrichment/${src} has ${n} files: pack it into a few large archives first (INFRA-CONTRACTS Rule F)"
        while IFS= read -r f; do upload "${f}" "${RAW_PREFIX}/enrichment/${src}/$(basename "${f}")"; done < <(find "${ENRICH_DIR}/${src}" -type f | LC_ALL=C sort)
      done
    fi
  fi
fi

# --- Step definitions. -------------------------------------------------------------------------------------------------------------
STEPS='[]'
add_step() { # add_step <name> <command and args...>: always TERMINATE_CLUSTER and an in-step 1-hour timeout
  local name="$1"
  shift
  STEPS="$(printf '%s' "${STEPS}" | jq -c --arg n "${name}" --arg t "${STEP_TIMEOUT}" --argjson a "$(printf '%s\0' "$@" | jq -Rsc 'split("\u0000")[:-1]')" \
    '. + [{Type: "CUSTOM_JAR", Name: $n, ActionOnFailure: "TERMINATE_CLUSTER", Jar: "command-runner.jar", Args: (["timeout", "--kill-after=60", $t] + $a)}]')"
}
COMMON=(--dblp "${RAW_PREFIX}/dblp.xml" --dtd "${RAW_PREFIX}/dblp.dtd" --output "${OUT_ROOT}" --snapshot "${SNAPSHOT}" --code-version "${SHORT}" --attempt 1)
if [[ -n "${ENRICH_DIR}" || "${SKIP_UPLOAD}" == 1 || "${SC_DRY_RUN}" == 1 ]]; then
  COMMON+=(--crossref "${RAW_PREFIX}/enrichment/crossref" --openalex "${RAW_PREFIX}/enrichment/openalex" --orcid "${RAW_PREFIX}/enrichment/orcid")
fi
if [[ "${SMOKE}" == 1 ]]; then
  add_step "smoke-mapreduce-pi" hadoop jar /usr/lib/hadoop-mapreduce/hadoop-mapreduce-examples.jar pi 2 100
else
  if has_stage hadoop; then
    add_step "hadoop-pipeline-HJ1-HJ7" hadoop jar "${JAR_PREFIX}/${HJAR}" scholarchain.hadoop.PipelineDriver "${COMMON[@]}"
  fi
  if has_stage spark; then
    SPARK_SET=(--set "input.root-i-dir=${OUT_ROOT}/curated" --set "input.hj6a-entity-views-dir=${OUT_ROOT}/work/hj6a/entity-views" --set "input.match-decisions-final-path=${OUT_ROOT}/work/hj7/match-decisions-final.jsonl")
    # shellcheck disable=SC2206
    [[ -z "${SC_SPARK_SET:-}" ]] || { extra=(${SC_SPARK_SET}); for kv in "${extra[@]}"; do SPARK_SET+=(--set "${kv}"); done; }
    add_step "spark-graph-analytics" spark-submit --deploy-mode client --class scholarchain.spark.Run "${JAR_PREFIX}/${SJAR}" \
      --output-root "${SPARK_OUT}" --checkpoint-dir "s3://${CURATED}/tmp/spark-checkpoints/${RUN_ID}" --code-version "${SHORT}" "${SPARK_SET[@]}"
  fi
  if has_stage load; then
    if [[ -z "${SC_LOADER_STEP_ARGS:-}" ]]; then
      if [[ "${SC_DRY_RUN}" == 1 ]]; then sc_warn "load stage UNCONFIGURED (SC_LOADER_STEP_ARGS unset; merge item M13-2): dry run shows a placeholder"; LOADER_JSON='["UNCONFIGURED"]'; else sc_warn "load stage has no EMR entry point on lane2: set SC_LOADER_STEP_ARGS (JSON array) or drop the stage with --stages (merge item M13-2)"; exit 7; fi
    else LOADER_JSON="${SC_LOADER_STEP_ARGS}"; fi
    printf '%s' "${LOADER_JSON}" | jq -e 'type == "array" and all(type == "string")' > /dev/null || sc_die "SC_LOADER_STEP_ARGS is not a JSON array of strings"
    STEPS="$(printf '%s' "${STEPS}" | jq -c --arg t "${STEP_TIMEOUT}" --argjson a "${LOADER_JSON}" '. + [{Type: "CUSTOM_JAR", Name: "storage-load", ActionOnFailure: "TERMINATE_CLUSTER", Jar: "command-runner.jar", Args: (["timeout", "--kill-after=60", $t] + $a)}]')"
  fi
  if has_stage enqueue; then
    if [[ -z "${SC_ENQUEUE_STEP_ARGS:-}" ]]; then
      if [[ "${SC_DRY_RUN}" == 1 ]]; then sc_warn "enqueue stage UNCONFIGURED (SC_ENQUEUE_STEP_ARGS unset; merge item M13-2): dry run shows a placeholder"; ENQ_JSON='["UNCONFIGURED"]'; else sc_warn "enqueue stage has no EMR entry point on lane2: set SC_ENQUEUE_STEP_ARGS (JSON array) or drop the stage (merge item M13-2)"; exit 7; fi
    else ENQ_JSON="${SC_ENQUEUE_STEP_ARGS}"; fi
    printf '%s' "${ENQ_JSON}" | jq -e 'type == "array" and all(type == "string")' > /dev/null || sc_die "SC_ENQUEUE_STEP_ARGS is not a JSON array of strings"
    STEPS="$(printf '%s' "${STEPS}" | jq -c --arg t "${STEP_TIMEOUT}" --argjson a "${ENQ_JSON}" '. + [{Type: "CUSTOM_JAR", Name: "embedding-enqueue", ActionOnFailure: "TERMINATE_CLUSTER", Jar: "command-runner.jar", Args: (["timeout", "--kill-after=60", $t] + $a)}]')"
  fi
fi
N_STEPS="$(printf '%s' "${STEPS}" | jq 'length')"
((N_STEPS > 0)) || sc_die "no steps selected"
printf '%s' "${STEPS}" | jq -e 'all(.ActionOnFailure == "TERMINATE_CLUSTER")' > /dev/null || sc_die "internal error: a step lacks TERMINATE_CLUSTER"

TAGS=("for-use-with-amazon-emr-managed-policies=true" "project=scholarchain" "environment=${ENV_NAME}" "run-id=${RUN_ID}")
for t in ${SC_EXTRA_TAGS:-}; do TAGS+=("${t}"); done

sc_stage cluster-plan
printf 'SC-PLAN scale=%s run_id=%s release=%s steps=%s nodes=%s\n' "${SCALE}" "${RUN_ID}" "${RELEASE}" "${N_STEPS}" "${NODES}"
printf '%s' "${STEPS}" | jq -r '.[] | "SC-PLAN step \(.Name) ActionOnFailure=\(.ActionOnFailure) timeout=\(.Args[2])s"'
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf '%s\n' "${STEPS}" | jq -S .
  printf 'SC-DRYRUN aws emr create-cluster --release-label %s --applications %s --instance-groups MASTER x1 + CORE x%s (%s, ON_DEMAND) --managed-scaling-policy max 2 --auto-termination-policy IdleTimeout=%s --no-auto-terminate --steps file://<steps.json>\n' "${RELEASE}" "${APPS[*]}" "${CORE}" "${ITYPE}" "${IDLE}"
  sc_ok "dry run complete: no cluster was created, nothing was uploaded"
  exit 0
fi

mkdir -p "${REPORT_DIR}"
sc_json_write "${REPORT_DIR}/steps.json" "${STEPS}"
CID=""
started="$(sc_utc_iso)"
write_run() { # write_run <status> [failed-step-name] [steps-json]
  local out
  out="$(jq -n --arg scale "${SCALE}" --arg run "${RUN_ID}" --arg status "$1" --arg failed "${2:-}" --arg cid "${CID}" --arg sha "${SHA}" --arg started "${started}" \
    --arg ended "$(sc_utc_iso)" --arg rel "${RELEASE}" --arg itype "${ITYPE}" --argjson core "${CORE}" --argjson idle "${IDLE}" --argjson st "${STEP_TIMEOUT}" \
    --arg region "$(sc_out region)" --arg root "${OUT_ROOT}" --arg spark "${SPARK_OUT}" --arg raw "${RAW_PREFIX}" --arg jars "${JAR_PREFIX}" --arg stages "${STAGES}" --arg hmode "${HMODE}" \
    --argjson uploads "${UPLOADS}" --argjson stepdef "${STEPS}" --argjson timings "${3:-[]}" \
    '{scale: $scale, run_id: $run, status: $status, failed_step: (if $failed == "" then null else $failed end), cluster_id: $cid, commit: $sha, started_at: $started, updated_at: $ended, region: $region,
      configuration: {release_label: $rel, instance_type: $itype, primary_nodes: 1, core_nodes: $core, managed_scaling_max_core_task: 2, idle_timeout_seconds: $idle,
                      step_timeout_seconds: $st, action_on_failure: "TERMINATE_CLUSTER", market: "ON_DEMAND", stages: $stages, hadoop_mode: $hmode},
      outputs: {hadoop_root: $root, spark_root: $spark, raw_inputs: $raw, jars: $jars}, uploads: $uploads, steps: $stepdef, step_timings: $timings}')"
  sc_json_write "${REPORT_DIR}/run.json" "${out}"
}
write_run starting
on_exit() {
  local rc=$?
  if [[ -n "${CID}" && "${rc}" -ne 0 ]]; then
    sc_warn "script ended early (exit ${rc}). Cluster ${CID} auto-terminates when idle; a failed step terminates it. Inspect: aws emr describe-cluster --cluster-id ${CID}"
  fi
}
trap on_exit EXIT

sc_stage create-cluster
TAG_ARGS=()
for t in "${TAGS[@]}"; do TAG_ARGS+=("${t}"); done
CID="$(sc_aws emr create-cluster --name "scholarchain-${SCALE}-${RUN_ID}" --release-label "${RELEASE}" --applications "${APPS[@]}" \
  --service-role "${SERVICE_ROLE}" \
  --ec2-attributes "SubnetId=${SUBNET},InstanceProfile=${PROFILE_NAME},EmrManagedMasterSecurityGroup=${EMR_SG},EmrManagedSlaveSecurityGroup=${EMR_SG}" \
  --instance-groups "InstanceGroupType=MASTER,InstanceCount=1,InstanceType=${ITYPE}" "InstanceGroupType=CORE,InstanceCount=${CORE},InstanceType=${ITYPE}" \
  --managed-scaling-policy "ComputeLimits={UnitType=Instances,MinimumCapacityUnits=1,MaximumCapacityUnits=2,MaximumOnDemandCapacityUnits=2,MaximumCoreCapacityUnits=2}" \
  --auto-termination-policy "IdleTimeout=${IDLE}" --no-auto-terminate --visible-to-all-users \
  --log-uri "s3://${LOGS}/emr/" --tags "${TAG_ARGS[@]}" --steps "file://${REPORT_DIR}/steps.json" \
  --query ClusterId --output text)" || { write_run failed "create-cluster"; sc_die "create-cluster failed"; }
[[ "${CID}" =~ ^j-[A-Z0-9]+$ ]] || { write_run failed "create-cluster"; sc_die "unexpected cluster id"; }
sc_ok "cluster ${CID} created (auto-terminates when idle after ${IDLE}s; failed steps terminate it)"
write_run running

sc_stage wait-steps
short_idle() { sc_aws emr put-auto-termination-policy --cluster-id "${CID}" --auto-termination-policy "IdleTimeout=60" > /dev/null 2>&1 || sc_warn "could not shorten the idle timeout (the original ${IDLE}s still applies)"; }
deadline=$((SECONDS + STEP_TIMEOUT * N_STEPS + 1800))
SHORTENED=0 TIMED_OUT=0 FINAL_STEPS='[]'
while :; do
  FINAL_STEPS="$(sc_retry sc_aws emr list-steps --cluster-id "${CID}" --query 'Steps[].{id:Id,name:Name,state:Status.State,start:Status.Timeline.StartDateTime,end:Status.Timeline.EndDateTime}' --output json)" || sc_die "cannot list steps"
  CSTATE="$(sc_retry sc_aws emr describe-cluster --cluster-id "${CID}" --query 'Cluster.Status.State' --output text)" || sc_die "cannot describe the cluster"
  # Hard limit per step, enforced here in addition to `timeout` inside the step: cancel and let the idle policy end the cluster.
  # The CLI prints timestamps with the caller's UTC offset (for example 2026-11-24T05:00:00.123000-05:00): convert any Z or +-HH:MM form.
  OVER="$(printf '%s' "${FINAL_STEPS}" | jq -r --argjson lim $((STEP_TIMEOUT + 180)) '
    def epoch: if test("Z$") then sub("\\.[0-9]+"; "") | fromdateiso8601
      else capture("^(?<t>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(\\.[0-9]+)?(?<sg>[+-])(?<h>[0-9]{2}):?(?<m>[0-9]{2})$")
        | ((.t + "Z") | fromdateiso8601) - ((if .sg == "-" then -1 else 1 end) * (((.h | tonumber) * 3600) + ((.m | tonumber) * 60))) end;
    [.[] | select(.state == "RUNNING" and .start != null) | select((now - (.start | epoch)) > $lim) | .id] | join(" ")')" \
    || { sc_warn "cannot parse a step start time; relying on the in-step timeout for this poll"; OVER=""; }
  if [[ -n "${OVER}" && "${TIMED_OUT}" == 0 ]]; then
    sc_warn "a step ran past the ${STEP_TIMEOUT}s limit: cancelling it (${OVER}) and shortening the idle timeout"
    # shellcheck disable=SC2086
    sc_aws emr cancel-steps --cluster-id "${CID}" --step-ids ${OVER} --step-cancellation-option SEND_INTERRUPT > /dev/null || sc_warn "cancel-steps failed"
    short_idle
    TIMED_OUT=1 SHORTENED=1
  fi
  case "${CSTATE}" in TERMINATED | TERMINATED_WITH_ERRORS) break ;; esac
  DONE="$(printf '%s' "${FINAL_STEPS}" | jq --argjson n "${N_STEPS}" '[.[] | select(.state == "COMPLETED")] | length == $n')"
  if [[ "${DONE}" == true && "${SHORTENED}" == 0 ]]; then
    sc_info "all steps completed: shortening the idle timeout to 60s to cut the idle tail"
    short_idle
    SHORTENED=1
  fi
  if ((SECONDS >= deadline)); then
    sc_warn "overall wait limit reached; the cluster ${CID} still auto-terminates when idle. Not waiting further."
    write_run timeout "" "${FINAL_STEPS}"
    exit 5
  fi
  sleep "${SC_POLL_SECONDS:-30}"
done
FAILED_STEP="$(printf '%s' "${FINAL_STEPS}" | jq -r '[.[] | select(.state == "FAILED" or .state == "CANCELLED" or .state == "INTERRUPTED")] | first | .name // empty')"
OK="$(printf '%s' "${FINAL_STEPS}" | jq --argjson n "${N_STEPS}" '[.[] | select(.state == "COMPLETED")] | length == $n')"
sc_stage record
if [[ "${OK}" == true && -z "${FAILED_STEP}" ]]; then
  write_run succeeded "" "${FINAL_STEPS}"
  sc_ok "pipeline ${SCALE}/${RUN_ID} succeeded: reports/pipeline/${SCALE}/${RUN_ID}/run.json"
  exit 0
fi
write_run failed "${FAILED_STEP:-unknown}" "${FINAL_STEPS}"
sc_warn "pipeline ${SCALE}/${RUN_ID} FAILED at step: ${FAILED_STEP:-unknown} (cluster ${CID} state ${CSTATE})"
exit 1
