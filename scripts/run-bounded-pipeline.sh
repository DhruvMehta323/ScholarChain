#!/usr/bin/env bash
# scripts/run-bounded-pipeline.sh --scale 1k|10k|50k [options]      or      --smoke
#
# One bounded pipeline run. Stages, in order:
#   hadoop (HJ1 to HJ7) -> spark (graph and analytics)      on ONE self-terminating EMR cluster (1 primary + at most 2 core/task = 3 nodes,
#                                                           on-demand m5.xlarge, idle auto-termination, logs to S3)
#   convert   (operator machine) sync the Spark output from S3, run the unchanged LocalLoad through sbt (Test classpath), upload the Neptune
#             CSV (vertices/, edges/ only) to s3://<curated>/graph/neptune-load/<scale>/<run>/ and the DynamoDB item files, load-manifest.json and checksums.sha256
#             to s3://<curated>/curated/loads/<scale>/<run>/ (never overwrites), record the SHA-256 of every uploaded file
#   load      (AWS, one ECS Fargate ops task, run-task once, never retried) Neptune bulk load, DynamoDB puts, render and enqueue at most
#             10000 embedding jobs (OpsLoadMain); the script waits for STOPPED and records the exit code and the log location
# DISCLOSURE: the Parquet-to-load-file conversion runs on the OPERATOR MACHINE, not in AWS, because the service images exclude Spark by
# design (build.sbt servicesClasspathGuard) and LocalLoad reads Parquet through Spark SQL (Test classpath only).
# DISCLOSURE: Spark runs in local mode on the EMR primary node (F14-5 workaround); distributed Spark I/O is deferred to 17b. The Spark step
# copies the jars and the HJ outputs to local disk, runs FinalDecisions (policy only) to make match-decisions-final.jsonl, runs spark-submit
# --master local[*] on local paths, and copies the output to s3://<curated>/graph/runs/<scale>/<run>/spark/ (provenance/ holds the decisions file).
# Every EMR step has ActionOnFailure=TERMINATE_CLUSTER and a 1-hour limit (enforced twice: `timeout 3600` inside the step, and a watcher
# here that cancels a step still running after 1 hour plus a grace period and shortens the idle timeout). The script never calls
# terminate-clusters and never touches Terraform: EMR terminates the cluster itself.
# Records go to reports/pipeline/<scale>/<run>/ (run.json, steps.json, convert.json, load.json, load-manifest.json, checksums.sha256).
# Post-only run: --stages convert,load (or either one) with --run-id <the Spark run id already in S3> skips EMR; existing records are kept,
# convert.json and load.json are never overwritten.
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
#   SC_CONVERT_TIMEOUT     default 3600 seconds (60 to 7200): the sbt LocalLoad run
#   SC_CONVERT_EXTRA_CMD   optional hook, unset by default: path of an executable run as <cmd> <run-root> <out-dir> after LocalLoad (a possible
#   SC_CONVERT_EXTRA_PREFIX  local render step). Its output must be files under <out-dir>/<SC_CONVERT_EXTRA_PREFIX>/ (one path segment, for example
#                          embedding-docs); they are uploaded to s3://<curated>/curated/loads/<scale>/<run>/<prefix>/ and checksummed
#   SC_LOAD_TIMEOUT        default 3600 seconds (1 to 7200): how long to wait for the ops task to stop (then it is stopped, exit 5)
#   SC_OPS_MAIN            default scholarchain.services.OpsLoadMain (SERVICE_MAIN of the ops task; one place to adjust)
#   SC_OPS_JAVA_OPTS       optional JAVA_OPTS override of the ops task (heap sizing)
#   SC_SPARK_SET           extra "--set key=value" pairs for the Spark job, space separated, no quotes inside
#   SC_EXTRA_TAGS          extra EMR tags "k=v k=v" (course, owner, expiration-date are not Terraform outputs; add them here)
# Options:
#   --scale 1k|10k|50k      --smoke      --approve-50k      --hadoop-fallback (emr-7.14.0 + Hadoop; MapReduce stages only)
#   --stages hadoop,spark,convert,load (default all)      --hadoop-mode driver (the only mode: ONE PipelineDriver step runs HJ1 to HJ7;
#                          the per-job *Tool classes have no main, modules/hadoop/JOB-CONTRACTS.md; the old "tools" mode was removed)
#   --approve-instance-class --gate-file <file>   see SC_EMR_INSTANCE_TYPE
#   --max-vectors N (default 10000, hard cap 10000; more is refused, exit 3)      --neptune-timeout-seconds N (default 1800, 60 to 3600)
#   --ops-task-dry-run (the ops task runs with SCHOLARCHAIN_OPS_DRY_RUN=true: no write)
#   --core-count 1|2 (default 1)      --run-id <token>      --env <name> (default course)
#   --dblp-xml <file> --dtd <file> --enrichment-dir <dir with crossref/ openalex/ orcid/>      --skip-upload
#   --dry-run (no AWS write, no cluster; prints the exact plan and step definitions)      --help
# Exit codes: 0 ok, 1 failure or step failed, 2 usage, 3 refused (50K rule, dirty tree), 4 outputs missing, 5 timeout, 6 precondition (Neptune off,
# no jar, ops task definition not applied, another `sbt check` running), 7 unused (was: unconfigured step). 3 also covers an existing --run-id (records are
# never overwritten), a non-empty S3 target prefix, --max-vectors above 10000, an ops task that exited 3 (its own refusal) and an unapproved instance class.
# shellcheck disable=SC2015,SC2016,SC2329 # intentional: literal text in single quotes, JMESPath backticks, A && B || C status idiom, trap handler
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
export TZ=UTC # the step-timing parser below also accepts any +-HH:MM offset (review M4)

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

SCALE="" SMOKE=0 APPROVE_50K=0 FALLBACK=0 STAGES="hadoop,spark,convert,load" HMODE=driver CORE=1 APPROVE_ITYPE=0 GATE_FILE="" RUN_ID="" ENV_NAME=course
DBLP_XML="" DTD="" ENRICH_DIR="" SKIP_UPLOAD=0 MAX_VECTORS=10000 NEPTUNE_TIMEOUT=1800 OPS_TASK_DRY=0 RUN_ID_GIVEN=0
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
    --run-id) RUN_ID="${2:?}"; RUN_ID_GIVEN=1; shift ;;
    --max-vectors) MAX_VECTORS="${2:?}"; shift ;;
    --neptune-timeout-seconds) NEPTUNE_TIMEOUT="${2:?}"; shift ;;
    --ops-task-dry-run) OPS_TASK_DRY=1 ;;
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
for st in $(printf '%s' "${STAGES}" | tr ',' ' '); do [[ "${st}" =~ ^(hadoop|spark|convert|load)$ ]] || { echo "unknown stage: ${st} (stages: hadoop, spark, convert, load)" >&2; exit 2; }; done
[[ "${MAX_VECTORS}" =~ ^[0-9]+$ ]] && ((MAX_VECTORS >= 1)) || { echo "--max-vectors must be a positive integer" >&2; exit 2; }
if ((MAX_VECTORS > 10000)); then
  printf 'SC-WARN REFUSED: --max-vectors %s is above the hard cap of 10000 embedding jobs per run (bounded runs only)\n' "${MAX_VECTORS}" >&2
  exit 3
fi
[[ "${NEPTUNE_TIMEOUT}" =~ ^[0-9]+$ ]] && ((NEPTUNE_TIMEOUT >= 60 && NEPTUNE_TIMEOUT <= 3600)) || { echo "--neptune-timeout-seconds must be 60 to 3600" >&2; exit 2; }
CONV_TIMEOUT="${SC_CONVERT_TIMEOUT:-3600}"
[[ "${CONV_TIMEOUT}" =~ ^[0-9]+$ ]] && ((CONV_TIMEOUT >= 60 && CONV_TIMEOUT <= 7200)) || { echo "SC_CONVERT_TIMEOUT must be 60 to 7200 seconds" >&2; exit 2; }
LOAD_TIMEOUT="${SC_LOAD_TIMEOUT:-3600}"
[[ "${LOAD_TIMEOUT}" =~ ^[0-9]+$ ]] && ((LOAD_TIMEOUT >= 1 && LOAD_TIMEOUT <= 7200)) || { echo "SC_LOAD_TIMEOUT must be 1 to 7200 seconds" >&2; exit 2; }
OPS_MAIN="${SC_OPS_MAIN:-scholarchain.services.OpsLoadMain}"
[[ "${OPS_MAIN}" =~ ^[A-Za-z0-9_.]+$ ]] || { echo "SC_OPS_MAIN is not a class name" >&2; exit 2; }
EXTRA_CMD="${SC_CONVERT_EXTRA_CMD:-}" EXTRA_PREFIX="${SC_CONVERT_EXTRA_PREFIX:-}"
if [[ -n "${EXTRA_CMD}" || -n "${EXTRA_PREFIX}" ]]; then
  [[ -x "${EXTRA_CMD}" && -f "${EXTRA_CMD}" ]] || { echo "SC_CONVERT_EXTRA_CMD must be an executable file (set together with SC_CONVERT_EXTRA_PREFIX)" >&2; exit 2; }
  [[ "${EXTRA_PREFIX}" =~ ^[a-z0-9][a-z0-9._-]*$ ]] || { echo "SC_CONVERT_EXTRA_PREFIX must be one path segment such as embedding-docs" >&2; exit 2; }
fi
STEP_TIMEOUT="${SC_STEP_TIMEOUT:-3600}"
[[ "${STEP_TIMEOUT}" =~ ^[0-9]+$ ]] && ((STEP_TIMEOUT >= 60 && STEP_TIMEOUT <= 3600)) || { echo "SC_STEP_TIMEOUT must be 60 to 3600 seconds (the limit is one hour)" >&2; exit 2; }
[[ -n "${RUN_ID}" ]] || RUN_ID="r$(sc_utc)-$$"
sc_valid_token "${RUN_ID}"
((${#RUN_ID} <= 128)) || { echo "--run-id is longer than 128 characters (the ops task accepts ^[A-Za-z0-9_.-]{1,128}$)" >&2; exit 2; }
has_stage() { case ",${STAGES}," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }
# EMR_MODE: an EMR cluster is created (smoke, hadoop or spark). Without it only convert and/or load run (post-only, needs the Spark run id).
[[ "${SMOKE}" != 1 ]] || STAGES="smoke" # a smoke run has no pipeline stages
EMR_MODE=0
if [[ "${SMOKE}" == 1 ]] || has_stage hadoop || has_stage spark; then EMR_MODE=1; fi
if [[ "${SMOKE}" != 1 && "${EMR_MODE}" != 1 && "${RUN_ID_GIVEN}" != 1 ]]; then
  echo "--stages without hadoop and spark (post-only run) needs --run-id <the Spark run id that is already in S3>" >&2
  exit 2
fi
if [[ "${FALLBACK}" == 1 && "${SMOKE}" != 1 ]] && { has_stage spark || has_stage convert || has_stage load; }; then
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
SUBNET="$(sc_out_f public_subnet_ids '.[0]')"
VPC="$(sc_out vpc_id)"
SERVICE_ROLE="" PROFILE_NAME="" EMR_SG=""
if [[ "${EMR_MODE}" == 1 ]]; then
  SERVICE_ROLE="$(basename "$(sc_out emr_service_role_arn)")"
  PROFILE_NAME="$(basename "$(sc_out emr_instance_profile_arn)")"
  [[ -n "${SERVICE_ROLE}" && -n "${PROFILE_NAME}" && "${SUBNET}" != null && -n "${SUBNET}" ]] || sc_die "EMR roles or subnet missing from the Terraform outputs"
  # The EMR security group is not a Terraform output (gap, reported): find it by name in the VPC (read-only).
  EMR_SG="$(sc_retry sc_aws ec2 describe-security-groups --filters "Name=vpc-id,Values=${VPC}" "Name=group-name,Values=*-emr" --query 'SecurityGroups[0].GroupId' --output text)" || sc_die "cannot look up the EMR security group"
  [[ "${EMR_SG}" =~ ^sg-[0-9a-f]+$ ]] || sc_die "EMR security group not found in the VPC"
fi
OPS_FAMILY="" OPS_SG="" ECS_CLUSTER="" OPS_LOG_GROUP=""
if has_stage load; then
  [[ -n "$(sc_out neptune_endpoint)" ]] || { sc_warn "the load stage needs Neptune: neptune_endpoint is empty (create_neptune = false). See: bash scripts/env-window.sh up"; exit 6; }
  OPS_FAMILY="$(sc_out ops_task_definition_family)" OPS_SG="$(sc_out ops_security_group_id)" ECS_CLUSTER="$(sc_out ecs_cluster_name)" OPS_LOG_GROUP="$(sc_out ops_log_group_name)"
  if [[ -z "${OPS_FAMILY}" || -z "${OPS_SG}" || -z "${ECS_CLUSTER}" || -z "${OPS_LOG_GROUP}" || "${SUBNET}" == null || -z "${SUBNET}" ]]; then
    sc_warn "the load stage needs the ops task outputs (ops_task_definition_family, ops_security_group_id, ops_log_group_name): the Terraform change for the ops task is not applied from this checkout"
    exit 6
  fi
fi

# --- Cluster configuration (matches modules/pipeline: release label, 3-node cap, idle timeout). -----------------------------------
if [[ "${FALLBACK}" == 1 ]]; then RELEASE="emr-7.14.0" APPS=(Name=Hadoop Name=Spark); else RELEASE="emr-spark-8.1.0" APPS=(Name=Spark); fi
NODES=$((1 + CORE))
((NODES <= 3)) || sc_die "refusing: more than 3 EMR nodes"
[[ "${EMR_MODE}" != 1 ]] || sc_info "EMR release=${RELEASE} nodes=${NODES} (1 primary + ${CORE} core; managed scaling max 2 core/task) type=${ITYPE} idle_timeout=${IDLE}s step_timeout=${STEP_TIMEOUT}s"
[[ "${EMR_MODE}" != 1 ]] || case "${SCALE}" in
  50k) sc_info "cost note (INFRA-CONTRACTS section 10): one 50K run is about \$2.47 of EMR; cluster about \$0.76 per hour" ;;
  10k) sc_info "cost note: a 10K run is about \$1.33 of EMR" ;;
  smoke) sc_info "cost note: a smoke run is about \$0.95 of EMR" ;;
esac

# --- Locations. -----------------------------------------------------------------------------------------------------------------------
RAW_PREFIX="s3://${RAW}/raw/${SCALE}/${RUN_ID}"
JAR_PREFIX="s3://${ARTIFACTS}/jars/${SHORT}"
OUT_ROOT="s3://${CURATED}/curated/runs/${SCALE}/${RUN_ID}"
SPARK_OUT="s3://${CURATED}/graph/runs/${SCALE}/${RUN_ID}/spark"
POLICY_URI="${RAW_PREFIX}/er-v1-frozen-policy.json"
REPORT_DIR="${ROOT}/reports/pipeline/${SCALE}/${RUN_ID}"
if [[ "${EMR_MODE}" == 1 && "${SC_DRY_RUN}" != 1 && -e "${REPORT_DIR}" ]]; then
  sc_warn "REFUSED: run id ${RUN_ID} already has records at reports/pipeline/${SCALE}/${RUN_ID}/ (and S3 inputs/results under it): records are never overwritten, use a new --run-id"
  exit 3
fi
SNAPSHOT="dblp-${SCALE}"
[[ "${SCALE}" != smoke ]] || SNAPSHOT="dblp-smoke"

# --- Uploads. -------------------------------------------------------------------------------------------------------------------------
HJAR="scholarchain-hadoop-assembly.jar" SJAR="scholarchain-spark-assembly.jar"
HJAR_LOCAL="" SJAR_LOCAL=""
UPLOADS='[]'
if [[ "${EMR_MODE}" != 1 ]]; then sc_info "post-only run (no EMR stage): jars and raw inputs are not needed"; fi
if [[ "${EMR_MODE}" == 1 ]]; then
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
POLICY_LOCAL="${ROOT}/modules/entity-resolution/evaluation/er-v1-frozen-policy.json"
if [[ "${SKIP_UPLOAD}" != 1 ]]; then
  [[ -z "${HJAR_LOCAL}" ]] || upload "${HJAR_LOCAL}" "${JAR_PREFIX}/${HJAR}"
  [[ -z "${SJAR_LOCAL}" ]] || upload "${SJAR_LOCAL}" "${JAR_PREFIX}/${SJAR}"
  # the frozen ER policy (read by FinalDecisions inside the Spark step), recorded with its SHA-256 like every upload
  if [[ "${SMOKE}" != 1 ]] && has_stage spark; then upload "${POLICY_LOCAL}" "${POLICY_URI}"; fi
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
fi

# --- Step definitions. -------------------------------------------------------------------------------------------------------------
STEPS='[]'
add_step() { # add_step <name> <command and args...>: always TERMINATE_CLUSTER and an in-step 1-hour timeout
  local name="$1"
  shift
  STEPS="$(printf '%s' "${STEPS}" | jq -c --arg n "${name}" --arg t "${STEP_TIMEOUT}" --argjson a "$(printf '%s\0' "$@" | jq -Rsc 'split("\u0000")[:-1]')" \
    '. + [{Type: "CUSTOM_JAR", Name: $n, ActionOnFailure: "TERMINATE_CLUSTER", Jar: "command-runner.jar", Args: (["timeout", "--kill-after=60", $t] + $a)}]')"
}
# F14-5: `hadoop jar` and `spark-submit` need a LOCAL jar. add_jar_step copies the S3 jar to the primary node (EMRFS, instance profile) and
# then execs the command, all inside the one `timeout` step, so ActionOnFailure=TERMINATE_CLUSTER and the 1-hour kill still cover copy and run.
LOCAL_JARS="/tmp/scholarchain-${RUN_ID}"
add_jar_step() { # add_jar_step <name> <s3 jar uri> <command and args...> (the command refers to ${LOCAL_JARS}/<jar basename>)
  local name="$1" uri="$2"
  shift 2
  add_step "${name}" bash -c 'set -euo pipefail; mkdir -p "$1"; hadoop fs -copyToLocal "$2" "$1/"; shift 2; exec "$@"' copy-then-run "${LOCAL_JARS}" "${uri}" "$@"
}
COMMON=(--dblp "${RAW_PREFIX}/dblp.xml" --dtd "${RAW_PREFIX}/dblp.dtd" --output "${OUT_ROOT}" --snapshot "${SNAPSHOT}" --code-version "${SHORT}" --attempt 1)
if [[ -n "${ENRICH_DIR}" || "${SKIP_UPLOAD}" == 1 || "${SC_DRY_RUN}" == 1 ]]; then
  COMMON+=(--crossref "${RAW_PREFIX}/enrichment/crossref" --openalex "${RAW_PREFIX}/enrichment/openalex" --orcid "${RAW_PREFIX}/enrichment/orcid")
fi
if [[ "${SMOKE}" == 1 ]]; then
  add_step "smoke-mapreduce-pi" hadoop jar /usr/lib/hadoop-mapreduce/hadoop-mapreduce-examples.jar pi 2 100
else
  if has_stage hadoop; then
    # F14-6: no main-class argument. The assembly MANIFEST sets Main-Class: scholarchain.hadoop.PipelineDriver and RunJar would pass a class
    # name through as args[0] (odd argument count). The args start directly with --dblp.
    add_jar_step "hadoop-pipeline-HJ1-HJ7" "${JAR_PREFIX}/${HJAR}" hadoop jar "${LOCAL_JARS}/${HJAR}" "${COMMON[@]}"
  fi
  if has_stage spark; then
    # F14-5 WORKAROUND: the Spark job reads and writes the LOCAL filesystem only (java.nio in modules/spark Inputs, Export, LoadManifests,
    # RunMetrics), so ONE step stages everything on the primary node and copies the result back. The decisions file is produced here
    # from the HJ6d candidates by FinalDecisions (policy only: no --views/--cache, deterministic). No s3:// path reaches FinalDecisions
    # or spark-submit. Refuses (exit 3) when the S3 Spark output prefix already exists. Extra SC_SPARK_SET pairs follow as --set args.
    SPARK_LOCAL_SH='set -euo pipefail
id=$1 hj=$2 sj=$3 out=$4 pol=$5 so=$6 cv=$7 hn=$8 sn=$9
shift 9
if hadoop fs -test -e "${so}"; then echo "REFUSED: ${so} already exists (the Spark output is never overwritten)" >&2; exit 3; fi
L=""
for b in /mnt /tmp; do if mkdir -p "${b}/scholarchain-${id}" 2> /dev/null; then L="${b}/scholarchain-${id}"; break; fi; done
[ -n "${L}" ] || { echo "no writable local directory" >&2; exit 1; }
echo "staging in ${L}"
mkdir -p "${L}/jars" "${L}/in" "${L}/work" "${L}/spark-tmp"
hadoop fs -copyToLocal "${hj}" "${sj}" "${L}/jars/"
hadoop fs -copyToLocal "${out}/curated" "${L}/in/curated"
hadoop fs -copyToLocal "${out}/work/hj6a/entity-views" "${L}/in/entity-views"
hadoop fs -copyToLocal "${out}/work/hj6d/match-candidates" "${L}/in/match-candidates"
hadoop fs -copyToLocal "${pol}" "${L}/in/er-v1-frozen-policy.json"
java -cp "${L}/jars/${hn}" scholarchain.er.tools.FinalDecisions --candidates "${L}/in/match-candidates" --policy "${L}/in/er-v1-frozen-policy.json" --out "${L}/work/match-decisions-final.jsonl" --summary "${L}/work/match-decisions-final.summary.json" --code-version "${cv}"
spark-submit --master "local[*]" --driver-memory 8g --conf spark.hadoop.fs.defaultFS=file:/// --conf "spark.local.dir=${L}/spark-tmp" --conf spark.eventLog.enabled=false --conf spark.dynamicAllocation.enabled=false --conf spark.shuffle.service.enabled=false --class scholarchain.spark.Run "${L}/jars/${sn}" --output-root "${L}/spark-out" --checkpoint-dir "${L}/checkpoints" --code-version "${cv}" --set "input.root-i-dir=${L}/in/curated" --set "input.hj6a-entity-views-dir=${L}/in/entity-views" --set "input.match-decisions-final-path=${L}/work/match-decisions-final.jsonl" "$@"
hadoop fs -copyFromLocal -d "${L}/spark-out" "${so}"
hadoop fs -mkdir -p "${so}/provenance"
hadoop fs -copyFromLocal -d "${L}/work/match-decisions-final.jsonl" "${L}/work/match-decisions-final.summary.json" "${so}/provenance/"'
    SPARK_EXTRA=()
    # shellcheck disable=SC2206
    [[ -z "${SC_SPARK_SET:-}" ]] || { extra=(${SC_SPARK_SET}); for kv in "${extra[@]}"; do SPARK_EXTRA+=(--set "${kv}"); done; }
    add_step "spark-graph-analytics" bash -c "${SPARK_LOCAL_SH}" spark-local-run "${RUN_ID}" "${JAR_PREFIX}/${HJAR}" "${JAR_PREFIX}/${SJAR}" "${OUT_ROOT}" "${POLICY_URI}" "${SPARK_OUT}" "${SHORT}" "${HJAR}" "${SJAR}" ${SPARK_EXTRA[@]+"${SPARK_EXTRA[@]}"}
  fi
fi
N_STEPS="$(printf '%s' "${STEPS}" | jq 'length')"
if [[ "${EMR_MODE}" == 1 ]]; then
  ((N_STEPS > 0)) || sc_die "no steps selected"
  printf '%s' "${STEPS}" | jq -e 'all(.ActionOnFailure == "TERMINATE_CLUSTER")' > /dev/null || sc_die "internal error: a step lacks TERMINATE_CLUSTER"
fi
# --- Post-Spark stages: convert (operator machine) and load (one ECS ops task). Never retried: run-task and the uploads are mutating. ----
NEPTUNE_LOAD_URI="s3://${CURATED}/graph/neptune-load/${SCALE}/${RUN_ID}"
ITEMS_URI="s3://${CURATED}/curated/loads/${SCALE}/${RUN_ID}"
POST_STEPS='[]'
POST_UPLOADS='[]'

post_fail() { # post_fail <stage> <exit code> <message>: record and exit (an integrated run's run.json is finalised as failed)
  sc_warn "$3"
  if [[ "${EMR_MODE}" == 1 && "${SC_DRY_RUN}" != 1 ]]; then write_run failed "$1" "${FINAL_STEPS}"; fi
  exit "$2"
}
steps_json_append() { # steps_json_append <json object>: adds a summary of a post stage to steps.json (the EMR step definitions stay as they are)
  local f="${REPORT_DIR}/steps.json" cur='[]'
  POST_STEPS="$(printf '%s' "${POST_STEPS}" | jq -c --argjson s "$1" '. + [$s]')"
  [[ ! -f "${f}" ]] || cur="$(cat "${f}")"
  sc_json_write "${f}" "$(printf '%s' "${cur}" | jq -c --argjson s "$1" '. + [$s]')"
}
s3_prefix_empty() { # s3_prefix_empty <prefix>: success when nothing exists under the prefix in the curated bucket (read-only call)
  local n
  n="$(sc_retry sc_aws s3api list-objects-v2 --bucket "${CURATED}" --prefix "$1" --max-items 1 --query 'length(Contents || `[]`)' --output text)" || sc_die "cannot list s3://${CURATED}/$1"
  [[ "${n}" == 0 ]]
}
upload_load_file() { # upload_load_file <local file> <relative name> <target uri prefix>: one object, SHA-256 recorded (never overwrites: the prefix was empty)
  local f="$1" rel="$2" uri="$3/$2" h
  h="$(sc_sha256 "${f}")"
  sc_mut sc_aws s3 cp "${f}" "${uri}" --only-show-errors --no-progress || post_fail convert 1 "upload failed: ${uri}"
  POST_UPLOADS="$(printf '%s' "${POST_UPLOADS}" | jq -c --arg f "${rel}" --arg u "${uri}" --arg h "${h}" --argjson b "$(wc -c < "${f}" | tr -d ' ')" '. + [{file: $f, uri: $u, sha256: $h, bytes: $b}]')"
}

stage_convert() {
  sc_stage convert
  local rec="${REPORT_DIR}/convert.json" started tmpd rr od rc=0 f rel n_v n_e n_i line want have path extra_json
  started="$(sc_utc_iso)"
  if [[ "${SC_DRY_RUN}" != 1 && -e "${rec}" ]]; then post_fail convert 3 "REFUSED: ${rec} exists: records are never overwritten"; fi
  # sbt must not run while lane 2 runs `sbt check` (two sbt processes on the same target directories corrupt each other).
  if pgrep -f 'sbt.*[[:space:]]check([[:space:];]|$)' > /dev/null 2>&1; then
    if [[ "${SC_DRY_RUN}" == 1 ]]; then
      sc_warn "another 'sbt check' is running: a real run would be refused (exit 6) because two sbt processes must not share the target directories"
    else
      post_fail convert 6 "REFUSED: another 'sbt check' is running (lane 2 owns sbt while it runs; a second sbt on the same target directories corrupts both). Wait until it ends, then run: bash scripts/run-bounded-pipeline.sh --scale ${SCALE} --run-id ${RUN_ID} --stages convert,load"
    fi
  fi
  # Never overwrite: both target prefixes must be empty (read-only listing).
  s3_prefix_empty "graph/neptune-load/${SCALE}/${RUN_ID}/" || post_fail convert 3 "REFUSED: ${NEPTUNE_LOAD_URI}/ is not empty: load files are never overwritten, use a new --run-id"
  s3_prefix_empty "curated/loads/${SCALE}/${RUN_ID}/" || post_fail convert 3 "REFUSED: ${ITEMS_URI}/ is not empty: load files are never overwritten, use a new --run-id"
  if [[ "${SC_DRY_RUN}" == 1 ]]; then
    printf 'SC-DRYRUN aws s3 sync %s/ <tmp>/run-root/\n' "${SPARK_OUT}"
    printf 'SC-DRYRUN (cd repo && sbt "storage/Test/runMain scholarchain.storage.LocalLoad --run-root <tmp>/run-root --out-dir <tmp>/load-out --code-version %s")\n' "${SHORT}"
    [[ -z "${EXTRA_CMD}" ]] || printf 'SC-DRYRUN %s <tmp>/run-root <tmp>/load-out  (extra hook; output under <out-dir>/%s/)\n' "${EXTRA_CMD}" "${EXTRA_PREFIX}"
    printf 'SC-DRYRUN aws s3 cp <out-dir>/neptune/{vertices,edges}/*.csv %s/{vertices,edges}/ (only these files under this prefix)\n' "${NEPTUNE_LOAD_URI}"
    printf 'SC-DRYRUN aws s3 cp <out-dir>/dynamodb/items/*.jsonl, load-manifest.json, checksums.sha256 (and embedding-inputs/ if present) %s/\n' "${ITEMS_URI}"
    [[ -z "${EXTRA_PREFIX}" ]] || printf 'SC-DRYRUN aws s3 cp <out-dir>/%s/* %s/%s/\n' "${EXTRA_PREFIX}" "${ITEMS_URI}" "${EXTRA_PREFIX}"
    printf 'SC-DRYRUN record %s: source run id %s, SHA-256 of every uploaded file, copy of load-manifest.json and checksums.sha256\n' "reports/pipeline/${SCALE}/${RUN_ID}/convert.json" "${RUN_ID}"
    return 0
  fi
  [[ -n "$(sc_retry sc_aws s3api list-objects-v2 --bucket "${CURATED}" --prefix "graph/runs/${SCALE}/${RUN_ID}/spark/" --max-items 1 --query 'length(Contents || `[]`)' --output text | grep -v '^0$' || true)" ]] \
    || post_fail convert 4 "the Spark output ${SPARK_OUT}/ does not exist in S3 (was the spark stage run for this run id?)"
  tmpd="$(mktemp -d)"
  [[ "${tmpd}" != *[[:space:]]* ]] || post_fail convert 1 "the temporary directory path contains whitespace"
  rr="${tmpd}/run-root" od="${tmpd}/load-out"
  mkdir -p "${rr}"
  sc_info "downloading ${SPARK_OUT}/ to ${rr} (read only)"
  sc_aws s3 sync "${SPARK_OUT}/" "${rr}/" --only-show-errors --no-progress || post_fail convert 1 "s3 sync of the Spark output failed (temporary files kept in ${tmpd})"
  [[ -f "${rr}/load-manifests/neptune.json" && -f "${rr}/load-manifests/dynamodb.json" ]] || post_fail convert 4 "the Spark output lacks load-manifests/neptune.json or dynamodb.json (temporary files kept in ${tmpd})"
  sc_info "running LocalLoad on the operator machine (sbt Test classpath; the service images exclude Spark)"
  (cd "${ROOT}" && sc_timeout "${CONV_TIMEOUT}" sbt "storage/Test/runMain scholarchain.storage.LocalLoad --run-root ${rr} --out-dir ${od} --code-version ${SHORT}") || rc=$?
  ((rc == 0)) || post_fail convert "$([[ "${rc}" == 124 ]] && echo 5 || echo 1)" "LocalLoad failed or timed out (exit ${rc}); nothing was uploaded (temporary files kept in ${tmpd})"
  n_v="$(find "${od}/neptune/vertices" -name '*.csv' -type f 2> /dev/null | wc -l | tr -d ' ')"
  n_e="$(find "${od}/neptune/edges" -name '*.csv' -type f 2> /dev/null | wc -l | tr -d ' ')"
  n_i="$(find "${od}/dynamodb/items" -name '*.jsonl' -type f 2> /dev/null | wc -l | tr -d ' ')"
  { [[ "${n_v}" -ge 1 && "${n_e}" -ge 1 && "${n_i}" -ge 1 && -f "${od}/load-manifest.json" && -f "${od}/checksums.sha256" ]]; } \
    || post_fail convert 4 "LocalLoad output incomplete (vertex files ${n_v}, edge files ${n_e}, item files ${n_i}, load-manifest.json, checksums.sha256): nothing was uploaded (kept in ${tmpd})"
  # Verify LocalLoad's own checksums before anything leaves this machine: lines are "<sha256>  <path relative to the out dir>".
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -n "${line}" ]] || continue
    [[ "${line}" =~ ^([0-9a-f]{64})[[:space:]]+\*?(.+)$ ]] || post_fail convert 1 "unrecognised line in checksums.sha256 (kept in ${tmpd})"
    want="${BASH_REMATCH[1]}" path="${BASH_REMATCH[2]}"
    [[ "${path}" != /* && "${path}" != *..* && -f "${od}/${path}" ]] || post_fail convert 1 "checksums.sha256 names a file that is not in the output: ${path}"
    have="$(sc_sha256 "${od}/${path}")"
    [[ "${have}" == "${want}" ]] || post_fail convert 1 "checksum mismatch for ${path}: nothing was uploaded (kept in ${tmpd})"
  done < "${od}/checksums.sha256"
  if [[ -n "${EXTRA_CMD}" ]]; then
    sc_info "running the extra local hook ${EXTRA_CMD}"
    "${EXTRA_CMD}" "${rr}" "${od}" || post_fail convert 1 "the extra local hook failed: nothing was uploaded (kept in ${tmpd})"
    [[ -d "${od}/${EXTRA_PREFIX}" ]] || post_fail convert 4 "the extra hook produced no ${od}/${EXTRA_PREFIX}/ directory"
  fi
  # LiveLoad's layout: the Neptune prefix holds ONLY vertices/<Label>.csv and edges/<TYPE>.csv ("neptune/" stripped; LiveLoad refuses extra
  # objects there). The items prefix keeps LocalLoad's relative paths (dynamodb/items/<table>.jsonl, embedding-inputs/<table>.jsonl,
  # load-manifest.json, checksums.sha256 unmodified).
  while IFS= read -r f; do rel="${f#"${od}"/neptune/}"; upload_load_file "${f}" "${rel}" "${NEPTUNE_LOAD_URI}"; done < <(find "${od}/neptune/vertices" "${od}/neptune/edges" -name '*.csv' -type f | LC_ALL=C sort)
  while IFS= read -r f; do rel="${f#"${od}"/}"; upload_load_file "${f}" "${rel}" "${ITEMS_URI}"; done < <({ find "${od}/dynamodb/items" -name '*.jsonl' -type f; printf '%s\n%s\n' "${od}/load-manifest.json" "${od}/checksums.sha256"; \
    [[ ! -d "${od}/embedding-inputs" ]] || find "${od}/embedding-inputs" -type f; [[ -z "${EXTRA_PREFIX}" ]] || find "${od}/${EXTRA_PREFIX}" -type f; } | LC_ALL=C sort)
  mkdir -p "${REPORT_DIR}"
  cp -n "${od}/load-manifest.json" "${REPORT_DIR}/load-manifest.json" && cp -n "${od}/checksums.sha256" "${REPORT_DIR}/checksums.sha256" \
    || post_fail convert 3 "REFUSED: load-manifest.json or checksums.sha256 already exists in ${REPORT_DIR}"
  extra_json="$(jq -n --arg c "${EXTRA_CMD}" --arg p "${EXTRA_PREFIX}" 'if $p == "" then null else {command: $c, prefix: $p} end')"
  sc_json_write "${rec}" "$(jq -n --arg scale "${SCALE}" --arg run "${RUN_ID}" --arg src "${SPARK_OUT}/" --arg cv "${SHORT}" --arg sha "${SHA}" --arg started "${started}" --arg ended "$(sc_utc_iso)" \
    --arg nu "${NEPTUNE_LOAD_URI}/" --arg iu "${ITEMS_URI}/" --arg lm "$(sc_sha256 "${od}/load-manifest.json")" --arg ck "$(sc_sha256 "${od}/checksums.sha256")" \
    --arg spark "$(jq -r '.input.sparkRunId // empty' "${od}/load-manifest.json")" --argjson up "${POST_UPLOADS}" --argjson extra "${extra_json}" \
    '{scale: $scale, run_id: $run, status: "succeeded", runs_on: "operator machine (sbt Test classpath: the service images exclude Spark by design)", tool: "sbt storage/Test/runMain scholarchain.storage.LocalLoad",
      source_s3_run: {run_id: $run, uri: $src, spark_run_id_in_load_manifest: (if $spark == "" then null else $spark end)}, code_version: $cv, commit: $sha,
      targets: {neptune_load_prefix: $nu, items_prefix: $iu}, load_manifest_sha256: $lm, checksums_file_sha256: $ck, local_copies: ["load-manifest.json", "checksums.sha256"],
      extra_hook: $extra, uploads: $up, started_at: $started, ended_at: $ended}')"
  steps_json_append "$(jq -n --arg n "convert-local-load" --arg r "reports/pipeline/${SCALE}/${RUN_ID}/convert.json" --argjson c "$(printf '%s' "${POST_UPLOADS}" | jq 'length')" \
    '{Type: "LOCAL_CONVERT", Name: $n, status: "succeeded", uploaded_files: $c, record: $r}')"
  sc_ok "convert done: $(printf '%s' "${POST_UPLOADS}" | jq 'length') files uploaded; record reports/pipeline/${SCALE}/${RUN_ID}/convert.json"
  rm -rf -- "${tmpd:?}"
}

ops_overrides_json() { # the run-time arguments of the ops task (one place to adjust; entrypoint.sh forwards no arguments, so they are environment variables)
  local dry=false
  [[ "${OPS_TASK_DRY}" != 1 ]] || dry=true
  jq -n --arg main "${OPS_MAIN}" --arg ns "${NEPTUNE_LOAD_URI}/" --arg is "${ITEMS_URI}/" --arg run "${RUN_ID}" --arg cv "${SHORT}" --arg mv "${MAX_VECTORS}" --arg nt "${NEPTUNE_TIMEOUT}" \
    --arg dry "${dry}" --arg jo "${SC_OPS_JAVA_OPTS:-}" \
    '{containerOverrides: [{name: "ops", environment: ([
      {name: "SERVICE_MAIN", value: $main}, {name: "SCHOLARCHAIN_OPS_NEPTUNE_SOURCE", value: $ns}, {name: "SCHOLARCHAIN_OPS_ITEMS_SOURCE", value: $is},
      {name: "SCHOLARCHAIN_OPS_RUN_ID", value: $run}, {name: "SCHOLARCHAIN_OPS_CODE_VERSION", value: $cv}, {name: "SCHOLARCHAIN_OPS_MAX_VECTORS", value: $mv},
      {name: "SCHOLARCHAIN_OPS_NEPTUNE_TIMEOUT_SECONDS", value: $nt}, {name: "SCHOLARCHAIN_OPS_DRY_RUN", value: $dry}]
      + (if $jo == "" then [] else [{name: "JAVA_OPTS", value: $jo}] end))}]}'
}

stage_load() {
  sc_stage load
  local rec="${REPORT_DIR}/load.json" started td img tarn task_id st stopped="" code="" reason="" scode="" log_stream start_s=$SECONDS desc ov netcfg status=failed rc=0
  started="$(sc_utc_iso)"
  if [[ "${SC_DRY_RUN}" != 1 && -e "${rec}" ]]; then post_fail load 3 "REFUSED: ${rec} exists: records are never overwritten (one ops task per run id)"; fi
  # The task definition must carry this commit's image: the tag is the 12-hex commit (scripts/lib/images.sh sc_release_tag).
  td="$(sc_retry sc_aws ecs describe-task-definition --task-definition "${OPS_FAMILY}" --query 'taskDefinition.taskDefinitionArn' --output text)" || post_fail load 6 "cannot read the ops task definition ${OPS_FAMILY}: apply the Terraform change that adds it"
  img="$(sc_retry sc_aws ecs describe-task-definition --task-definition "${td}" --query 'taskDefinition.containerDefinitions[0].image' --output text)" || post_fail load 6 "cannot read the image of ${td}"
  if [[ "${img##*:}" != "${SHORT}" ]]; then
    if [[ "${SC_DRY_RUN}" == 1 ]]; then sc_warn "the ops task definition image tag (${img##*:}) is not this commit (${SHORT}): a real run would be refused (exit 6)"
    else post_fail load 6 "REFUSED: the ops task definition ${td##*/} runs image tag ${img##*:}, not this commit ${SHORT}. Set Terraform image_tag to ${SHORT} and have the human apply it (the ops task uses the embedding-worker image and image_tag)"; fi
  fi
  ov="$(ops_overrides_json)"
  netcfg="awsvpcConfiguration={subnets=[${SUBNET}],securityGroups=[${OPS_SG}],assignPublicIp=ENABLED}"
  if [[ "${SC_DRY_RUN}" == 1 ]]; then
    printf 'SC-DRYRUN aws ecs run-task --cluster %s --task-definition %s --launch-type FARGATE --count 1 --network-configuration %s --started-by sc-%s --overrides <below>\n' "${ECS_CLUSTER}" "${td}" "${netcfg}" "${RUN_ID:0:30}"
    printf '%s\n' "${ov}" | jq -S .
    printf 'SC-DRYRUN wait for STOPPED (at most %ss), record exit code, stop reason and log stream %s/ops/ops/<task-id> in steps.json and load.json; non-zero exit fails the run\n' "${LOAD_TIMEOUT}" "${OPS_LOG_GROUP}"
    return 0
  fi
  mkdir -p "${REPORT_DIR}"
  sc_json_write "${REPORT_DIR}/ops-overrides.json" "${ov}"
  # ONE call, never retried (a second run-task would start a second load).
  tarn="$(sc_aws ecs run-task --cluster "${ECS_CLUSTER}" --task-definition "${td}" --launch-type FARGATE --count 1 --network-configuration "${netcfg}" \
    --started-by "sc-${RUN_ID:0:30}" --overrides "file://${REPORT_DIR}/ops-overrides.json" --query 'tasks[0].taskArn' --output text)" || post_fail load 1 "ecs run-task failed (not retried)"
  [[ "${tarn}" =~ ^arn:aws:ecs:[a-z0-9-]+:[0-9]{12}:task/.+ ]] || post_fail load 1 "run-task started no task (the response had no task ARN; check capacity and the subnet)"
  task_id="${tarn##*/}"
  sc_ok "ops task ${task_id} started (cluster ${ECS_CLUSTER}); waiting for STOPPED (limit ${LOAD_TIMEOUT}s)"
  while :; do
    desc="$(sc_retry sc_aws ecs describe-tasks --cluster "${ECS_CLUSTER}" --tasks "${tarn}" \
      --query 'tasks[0].{status:lastStatus,stoppedReason:stoppedReason,stopCode:stopCode,exitCode:containers[0].exitCode,reason:containers[0].reason}' --output json)" || post_fail load 1 "cannot describe the ops task ${task_id}"
    st="$(printf '%s' "${desc}" | jq -r '.status // "UNKNOWN"')"
    [[ "${st}" != STOPPED ]] || break
    if ((SECONDS - start_s >= LOAD_TIMEOUT)); then
      sc_aws ecs stop-task --cluster "${ECS_CLUSTER}" --task "${tarn}" --reason "sc pipeline wait limit ${LOAD_TIMEOUT}s" > /dev/null 2>&1 || sc_warn "stop-task failed: stop the task by hand: ${tarn}"
      steps_json_append "$(jq -n --arg t "${tarn}" '{Type: "ECS_RUN_TASK", Name: "ops-load", status: "timeout", task_arn: $t}')"
      post_fail load 5 "the ops task ${task_id} did not stop within ${LOAD_TIMEOUT}s: a stop was requested; check ${OPS_LOG_GROUP} and the Neptune loader status"
    fi
    sleep "${SC_POLL_SECONDS:-15}"
  done
  code="$(printf '%s' "${desc}" | jq -r '.exitCode // empty')" stopped="$(printf '%s' "${desc}" | jq -r '.stoppedReason // empty')"
  scode="$(printf '%s' "${desc}" | jq -r '.stopCode // empty')" reason="$(printf '%s' "${desc}" | jq -r '.reason // empty')"
  log_stream="ops/ops/${task_id}"
  [[ "${code}" == 0 ]] && status=succeeded
  sc_json_write "${rec}" "$(jq -n --arg scale "${SCALE}" --arg run "${RUN_ID}" --arg status "${status}" --arg td "${td}" --arg img "${img}" --arg t "${tarn}" --arg code "${code}" --arg sr "${stopped}" --arg sc "${scode}" --arg cr "${reason}" \
    --arg lg "${OPS_LOG_GROUP}" --arg ls "${log_stream}" --arg ns "${NEPTUNE_LOAD_URI}/" --arg is "${ITEMS_URI}/" --arg mv "${MAX_VECTORS}" --arg cv "${SHORT}" --arg started "${started}" --arg ended "$(sc_utc_iso)" --arg region "$(sc_out region)" \
    '{scale: $scale, run_id: $run, status: $status, task_definition: $td, image: $img, task_arn: $t, exit_code: (if $code == "" then null else ($code | tonumber) end), stopped_reason: $sr, stop_code: $sc, container_reason: $cr,
      log: {region: $region, group: $lg, stream: $ls}, sources: {neptune: $ns, items: $is}, max_vectors: ($mv | tonumber), code_version: $cv,
      manifest_expected_at: ($is + "ops-manifest.json"), started_at: $started, ended_at: $ended}')"
  steps_json_append "$(jq -n --arg st "${status}" --arg t "${tarn}" --arg code "${code}" --arg sr "${stopped}" --arg lg "${OPS_LOG_GROUP}" --arg ls "${log_stream}" \
    '{Type: "ECS_RUN_TASK", Name: "ops-load", status: $st, task_arn: $t, exit_code: (if $code == "" then null else ($code | tonumber) end), stopped_reason: $sr, log_group: $lg, log_stream: $ls}')"
  if [[ "${status}" != succeeded ]]; then
    # The ops entry point: 3 = its own cap or invariant refusal (mapped to this script's refusal code); anything else, or no exit code, is a failure.
    rc=1
    [[ "${code}" != 3 ]] || rc=3
    post_fail load "${rc}" "the ops task exited with code ${code:-none} (stopped reason: ${stopped:-none}); log ${OPS_LOG_GROUP} stream ${log_stream}; record reports/pipeline/${SCALE}/${RUN_ID}/load.json"
  fi
  sc_ok "ops task succeeded (exit 0); log ${OPS_LOG_GROUP} stream ${log_stream}"
}

run_post_stages() {
  if has_stage convert; then stage_convert; fi
  if has_stage load; then stage_load; fi
  return 0
}

TAGS=("for-use-with-amazon-emr-managed-policies=true" "project=scholarchain" "environment=${ENV_NAME}" "run-id=${RUN_ID}")
for t in ${SC_EXTRA_TAGS:-}; do TAGS+=("${t}"); done

sc_stage cluster-plan
if [[ "${EMR_MODE}" != 1 ]]; then
  printf 'SC-PLAN scale=%s run_id=%s post-only stages=%s (no EMR cluster)\n' "${SCALE}" "${RUN_ID}" "${STAGES}"
  run_post_stages
  if [[ "${SC_DRY_RUN}" == 1 ]]; then sc_ok "dry run complete: nothing was uploaded and no task was started"; else sc_ok "post-only run ${SCALE}/${RUN_ID} succeeded: reports/pipeline/${SCALE}/${RUN_ID}/"; fi
  exit 0
fi
printf 'SC-PLAN scale=%s run_id=%s release=%s steps=%s nodes=%s\n' "${SCALE}" "${RUN_ID}" "${RELEASE}" "${N_STEPS}" "${NODES}"
printf '%s' "${STEPS}" | jq -r '.[] | "SC-PLAN step \(.Name) ActionOnFailure=\(.ActionOnFailure) timeout=\(.Args[2])s"'
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf '%s\n' "${STEPS}" | jq -S .
  printf 'SC-DRYRUN aws emr create-cluster --release-label %s --applications %s --instance-groups MASTER x1 + CORE x%s (%s, ON_DEMAND) --managed-scaling-policy max 2 --auto-termination-policy IdleTimeout=%s --no-auto-terminate --steps file://<steps.json>\n' "${RELEASE}" "${APPS[*]}" "${CORE}" "${ITYPE}" "${IDLE}"
  run_post_stages
  sc_ok "dry run complete: no cluster was created, nothing was uploaded, no task was started"
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
    --argjson uploads "${UPLOADS}" --argjson stepdef "${STEPS}" --argjson timings "${3:-[]}" --argjson post "${POST_STEPS}" \
    '{scale: $scale, run_id: $run, status: $status, failed_step: (if $failed == "" then null else $failed end), cluster_id: $cid, commit: $sha, started_at: $started, updated_at: $ended, region: $region,
      configuration: {release_label: $rel, instance_type: $itype, primary_nodes: 1, core_nodes: $core, managed_scaling_max_core_task: 2, idle_timeout_seconds: $idle,
                      step_timeout_seconds: $st, action_on_failure: "TERMINATE_CLUSTER", market: "ON_DEMAND", stages: $stages, hadoop_mode: $hmode},
      outputs: {hadoop_root: $root, spark_root: $spark, raw_inputs: $raw, jars: $jars}, uploads: $uploads, steps: $stepdef, step_timings: $timings, post_steps: $post}')"
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
  run_post_stages
  write_run succeeded "" "${FINAL_STEPS}"
  sc_ok "pipeline ${SCALE}/${RUN_ID} succeeded: reports/pipeline/${SCALE}/${RUN_ID}/run.json"
  exit 0
fi
write_run failed "${FAILED_STEP:-unknown}" "${FINAL_STEPS}"
sc_warn "pipeline ${SCALE}/${RUN_ID} FAILED at step: ${FAILED_STEP:-unknown} (cluster ${CID} state ${CSTATE})"
exit 1
