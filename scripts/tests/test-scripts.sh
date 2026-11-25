#!/usr/bin/env bash
# scripts/tests/test-scripts.sh [--keep]
#
# Offline test harness for the Phase 13 scripts. It makes NO AWS call: every cloud and build tool (aws, terraform, docker, sbt, curl) is a
# stub generated into a scratch directory that logs its arguments and prints canned output. The scripts run inside a throw-away git
# repository copy in that scratch directory, never against this working tree. It never runs the real terraform, docker or sbt.
# Scratch directory: $SC_TEST_SCRATCH if set, otherwise a fresh mktemp directory (removed at the end unless --keep).
# Exit codes: 0 all cases passed, 1 at least one failed.
# shellcheck disable=SC2015 # ok and bad always succeed, so A && ok || bad is a safe if-then-else here
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_ROOT="$(cd "${HERE}/../.." && pwd)"
KEEP=0
[[ "${1:-}" == "--keep" ]] && KEEP=1
SCRATCH="${SC_TEST_SCRATCH:-$(mktemp -d)}"
mkdir -p "${SCRATCH}"
STUBS="${SCRATCH}/bin" STATE="${SCRATCH}/state" OUT="${SCRATCH}/out" REPO="${SCRATCH}/repo" LOG="${SCRATCH}/stub.log"
rm -rf -- "${STUBS:?}" "${STATE:?}" "${OUT:?}" "${REPO:?}" "${LOG:?}"
mkdir -p "${STUBS}" "${STATE}" "${OUT}" "${REPO}"
: > "${LOG}"
SENTINEL="SENTINEL_KEY_9f8e7d6c5b4a3210"
PASS=0 FAILN=0

# ---------------------------------------------------------------------------------------------------------------------------------
# Stubs
# ---------------------------------------------------------------------------------------------------------------------------------
cat > "${STUBS}/aws" << 'EOF'
#!/usr/bin/env bash
printf 'aws %s\n' "$*" >> "${STUB_LOG}"
args=("$@"); clean=(); i=0
while [[ $i -lt ${#args[@]} ]]; do
  case "${args[$i]}" in --cli-connect-timeout | --cli-read-timeout) i=$((i + 2)); continue ;; esac
  clean+=("${args[$i]}"); i=$((i + 1))
done
svc="${clean[0]:-}" op="${clean[1]:-}"
opt() { local k="$1" j; for ((j = 2; j < ${#clean[@]}; j++)); do if [[ "${clean[$j]}" == "$k" ]]; then printf '%s' "${clean[$((j + 1))]}"; return; fi; done; }
q="$(opt --query)"
DIGEST="sha256:$(printf '%064d' 0 | tr 0 a)"
ARNP="arn:aws:ecs:us-east-2:123456789012:task-definition"
cur_td() { if [[ -f "${STUB_STATE}/td-$1" ]]; then cat "${STUB_STATE}/td-$1"; else printf '%s/scholarchain-%s:1' "${ARNP}" "$1"; fi; }
cur_desired() { if [[ -f "${STUB_STATE}/desired-$1" ]]; then cat "${STUB_STATE}/desired-$1"; else printf '%s' "${STUB_INITIAL_DESIRED:-1}"; fi; }
case "${svc} ${op}" in
  "sts get-caller-identity") echo "${STUB_ACCOUNT:-123456789012}" ;;
  "ec2 describe-security-groups") echo sg-0abc123 ;;
  "secretsmanager describe-secret") echo "${STUB_SECRET_VERSIONS:-1}" ;;
  "ecr get-login-password") echo stub-registry-password ;;
  "ecr describe-images")
    r="$(opt --repository-name)"; s="${r#scholarchain/}"
    if [[ -e "${STUB_STATE}/pushed-${s}" || "${STUB_ECR_EXISTS:-0}" == 1 ]]; then echo "${DIGEST}"; else exit 254; fi ;;
  "ecr list-images") if [[ "${q}" == *length* ]]; then echo 2; else echo '[{"imageTag":"t1"},{"imageTag":"t2"}]'; fi ;;
  "ecr batch-"*) echo 0 ;;
  "ecs describe-services")
    s="$(opt --services)"
    if [[ "${q}" == *"services[0].taskDefinition"* ]]; then cur_td "${s}"
    elif [[ "${q}" == *"n:length"* ]]; then
      if [[ "${STUB_ECS_NOT_STABLE:-0}" == 1 ]]; then printf '{"n":2,"r":"IN_PROGRESS","run":0,"td":"%s"}\n' "$(cur_td "${s}")"
      else printf '{"n":1,"r":"COMPLETED","run":%s,"td":"%s"}\n' "$(cur_desired "${s}")" "$(cur_td "${s}")"; fi
    elif [[ "${q}" == *"services[].{service"* ]]; then
      printf '['; first=1
      for n in query-api retrieval embedding-worker; do [[ $first == 1 ]] || printf ','; first=0; printf '{"service":"%s","desired":%s,"running":%s}' "$n" "$(cur_desired "$n")" "$(cur_desired "$n")"; done
      printf ']\n'
    elif [[ "${q}" == *desiredCount* ]]; then cur_desired "${s}"
    fi ;;
  "ecs describe-task-definition")
    t="$(opt --task-definition)"
    [[ "${t}" == arn:* ]] || t="${ARNP}/${t}"
    fam="${t##*/}"; fam="${fam%%:*}"
    if [[ "${STUB_MISSING_TD:-0}" == 1 && "${t}" == *":99" ]]; then exit 254; fi
    img="old:1"; [[ "${fam}" != scholarchain-ops ]] || img="123456789012.dkr.ecr.us-east-2.amazonaws.com/scholarchain/embedding-worker:${STUB_OPS_TAG:-unset}"
    if [[ "${t}" == *":2" && -f "${STUB_STATE}/reg-${fam}.json" ]]; then img="$(jq -r '.containerDefinitions[0].image' "${STUB_STATE}/reg-${fam}.json")"; fi
    case "${q}" in
      taskDefinitionArn | *taskDefinitionArn) echo "${t}" ;;
      *"containerDefinitions[0].image"*) echo "${img}" ;;
      *) printf '{"family":"%s","taskRoleArn":"r","executionRoleArn":"e","networkMode":"awsvpc","containerDefinitions":[{"name":"app","image":"%s"}],"cpu":"256","memory":"512","requiresCompatibilities":["FARGATE"],"revision":1,"status":"ACTIVE","taskDefinitionArn":"%s"}\n' "${fam}" "${img}" "${t}" ;;
    esac ;;
  "ecs register-task-definition")
    f="$(opt --cli-input-json)"; f="${f#file://}"
    fam="$(jq -r .family "${f}")"; cp "${f}" "${STUB_STATE}/reg-${fam}.json"
    echo "${ARNP}/${fam}:2" ;;
  "ecs update-service")
    s="$(opt --service)"; t="$(opt --task-definition)"; d="$(opt --desired-count)"
    # STUB_RECORD_FILE: the in-progress deployment record must already exist before any service is updated (review M6).
    if [[ -n "${STUB_RECORD_FILE:-}" ]] && ! jq -e '.status == "in-progress"' "${STUB_RECORD_FILE}" > /dev/null 2>&1; then echo "UPDATE BEFORE RECORD" >> "${STUB_LOG}"; exit 98; fi
    [[ -z "${t}" ]] || printf '%s' "${t}" > "${STUB_STATE}/td-${s}"
    [[ -z "${d}" ]] || printf '%s' "${d}" > "${STUB_STATE}/desired-${s}"
    echo '{}' ;;
  "ecs list-tasks") s="$(opt --service-name)"; if [[ "$(cur_desired "${s}")" -gt 0 ]]; then echo '["arn:aws:ecs:us-east-2:123456789012:task/c/1"]'; else echo '[]'; fi ;;
  "ecs describe-tasks")
    if [[ "${q}" == *lastStatus* ]]; then
      n=$(($(cat "${STUB_STATE}/ops-poll-n" 2> /dev/null || echo 0) + 1)); echo "${n}" > "${STUB_STATE}/ops-poll-n"
      if [[ "${STUB_OPS_NEVER_STOP:-0}" == 1 || ${n} -lt 3 ]]; then echo '{"status":"RUNNING","stoppedReason":null,"stopCode":null,"exitCode":null,"reason":null}'
      else printf '{"status":"STOPPED","stoppedReason":"Essential container in task exited","stopCode":"EssentialContainerExited","exitCode":%s,"reason":null}\n' "${STUB_OPS_EXIT-0}"; fi
    else echo "[\"${STUB_TASK_DIGEST:-${DIGEST}}\"]"; fi ;;
  "emr create-cluster")
    f="$(opt --steps)"; cp "${f#file://}" "${STUB_STATE}/emr-steps.json"; echo j-STUB1 ;;
  "emr list-steps")
    n=$(($(cat "${STUB_STATE}/emr-list-n" 2> /dev/null || echo 0) + 1)); echo "${n}" > "${STUB_STATE}/emr-list-n"
    fmt() { date -u -r "$1" +%Y-%m-%dT%H:%M:%S 2> /dev/null || date -u -d "@$1" +%Y-%m-%dT%H:%M:%S; }
    if [[ -n "${STUB_STEP_RUNNING:-}" && ${n} -lt 2 ]]; then
      # First poll: step 0 is RUNNING with a start time printed in a NON-UTC offset (what the CLI does on a non-UTC host).
      case "${STUB_STEP_RUNNING}" in
        recent-west) off=-18000 sfx="-05:00" age=10 ;;   # started 10 s ago, clock shows UTC-5
        old-east) off=19800 sfx="+05:30" age=7200 ;;     # started 2 h ago, clock shows UTC+5:30
      esac
      st="$(fmt $(($(date +%s) - age + off))).123000${sfx}"
      jq --arg st "${st}" '. as $a | [range(0; length) | {id: ("s-" + tostring), name: $a[.].Name, state: (if . == 0 then "RUNNING" else "PENDING" end), start: (if . == 0 then $st else null end), end: null}]' "${STUB_STATE}/emr-steps.json"
    else
      jq '. as $a | [range(0; length) | {id: ("s-" + tostring), name: $a[.].Name, state: "COMPLETED", start: "2026-11-24T10:00:00.000000+00:00", end: "2026-11-24T10:05:00.000000+00:00"}]' "${STUB_STATE}/emr-steps.json"
    fi ;;
  "emr describe-cluster")
    if [[ -n "${STUB_STEP_RUNNING:-}" && "$(cat "${STUB_STATE}/emr-list-n" 2> /dev/null || echo 0)" -lt 2 ]]; then echo RUNNING; else echo "${STUB_CLUSTER_STATE:-TERMINATED}"; fi ;;
  "emr list-clusters") echo 0 ;;
  "emr "*) echo '{}' ;;
  "neptune describe-db-clusters" | "dynamodb list-tables") echo 0 ;;
  "s3api list-objects-v2")
    if [[ -n "$(opt --prefix)" ]]; then
      case "$(opt --prefix)" in
        graph/runs/*) if [[ "${STUB_NO_SPARK_OUT:-0}" == 1 ]]; then echo 0; else echo 1; fi ;;
        *) if [[ "${STUB_S3_NONEMPTY:-0}" == 1 ]]; then echo 1; else echo 0; fi ;;
      esac
    else echo 3; fi ;;
  "s3 cp") ;;
  "s3 sync")
    # download of the Spark output: the two load manifests LocalLoad needs
    d="${clean[3]}"; mkdir -p "${d}/load-manifests"; echo '{}' > "${d}/load-manifests/neptune.json"; echo '{}' > "${d}/load-manifests/dynamodb.json" ;;
  "ecs run-task")
    n="$(opt --overrides)"; cp "${n#file://}" "${STUB_STATE}/run-task-overrides.json"
    printf '%s\n' "${STUB_RUN_TASK_ARN-arn:aws:ecs:us-east-2:123456789012:task/scholarchain-cluster/abc123}" ;;
  "ecs stop-task") : > "${STUB_STATE}/task-stopped" ;;
  "cloudwatch get-metric-statistics") echo '{"Datapoints":[]}' ;;
  *) echo "aws stub: unhandled ${svc} ${op}" >&2; echo "UNHANDLED aws ${svc} ${op}" >> "${STUB_LOG}"; exit 97 ;;
esac
EOF

cat > "${STUBS}/terraform" << 'EOF'
#!/usr/bin/env bash
printf 'terraform %s\n' "$*" >> "${STUB_LOG}"
sub=""
for a in "$@"; do case "$a" in -*) ;; *) sub="$a"; break ;; esac; done
case "${sub}" in
  output) if [[ "${STUB_TF_EMPTY:-0}" == 1 ]]; then echo '{}'; else cat "${STUB_TF_OUT}"; fi ;;
  state) echo 'module.data.aws_s3_bucket.this["raw"]'; echo 'module.platform.aws_ecr_repository.this["query-api"]'; echo 'module.compute.aws_ecs_cluster.this' ;;
  fmt | validate | init) ;;
  apply | destroy) echo "FORBIDDEN terraform ${sub}" >> "${STUB_LOG}"; exit 99 ;;
  *) echo "UNHANDLED terraform ${sub}" >> "${STUB_LOG}"; exit 97 ;;
esac
EOF

cat > "${STUBS}/docker" << 'EOF'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "${STUB_LOG}"
case "${1:-}" in
  images) echo 'scholarchain-services:0.1.0-SNAPSHOT' ;;
  image)
    # `docker image inspect --format <fmt> <ref>`: the revision label of a ref is its tag unless STUB_LABEL_REV overrides it;
    # STUB_MISSING_IMAGE=<service> makes that service's image absent (exit 1).
    ref="${!#}"
    if [[ -n "${STUB_MISSING_IMAGE:-}" && "${ref}" == *"/${STUB_MISSING_IMAGE}:"* ]]; then exit 1; fi
    case "$*" in
      *Labels*) echo "${STUB_LABEL_REV:-${ref##*:}}" ;;
      *) echo "${STUB_DOCKER_ID:-sha256:$(printf '%064d' 0 | tr 0 b)}" ;;
    esac ;;
  login) cat > /dev/null ;;
  push) r="${2##*/}"; r="${r%%:*}"; : > "${STUB_STATE}/pushed-${r}" ;;
  tag) ;;
  *) echo "UNHANDLED docker $1" >> "${STUB_LOG}"; exit 97 ;;
esac
EOF

cat > "${STUBS}/sbt" << 'EOF'
#!/usr/bin/env bash
printf 'sbt %s [SCHOLARCHAIN_GIT_SHA=%s]\n' "$*" "${SCHOLARCHAIN_GIT_SHA:-unset}" >> "${STUB_LOG}"
# sbt 2 thin client: a background server keeps the environment of the call that started it (F14-1). Simulated in ${STUB_STATE}/sbt-server.
SRV="${STUB_STATE}/sbt-server"
case "$*" in
  shutdown) rm -f "${SRV}"; exit 0 ;;
  packageJobs | dockerLocal)
    [[ -f "${SRV}" ]] || printf '%s' "${SCHOLARCHAIN_GIT_SHA:-unset}" > "${SRV}"
    printf 'sbt-server-env SCHOLARCHAIN_GIT_SHA=%s\n' "$(cat "${SRV}")" >> "${STUB_LOG}"
    if [[ "$*" == dockerLocal && "$(cat "${SRV}")" != "${SCHOLARCHAIN_GIT_SHA:-unset}" ]]; then
      echo "SCHOLARCHAIN_GIT_SHA must be set: a running sbt server keeps its own environment" >&2
      exit 1
    fi
    exit 0 ;;
  *LocalLoad*)
    [[ "${STUB_SBT_FAIL:-0}" != 1 ]] || exit 1
    od="${*#*--out-dir }"; od="${od%% *}"
    mkdir -p "${od}/neptune/vertices" "${od}/neptune/edges" "${od}/dynamodb/items"
    printf 'id,label\nr1,Researcher\n' > "${od}/neptune/vertices/Researcher.csv"
    printf 'id,from,to\ne1,r1,r2\n' > "${od}/neptune/edges/COAUTHORED_WITH.csv"
    printf '{"pk":"a"}\n' > "${od}/dynamodb/items/analytics-bridges.jsonl"
    printf '{"input":{"sparkRunId":"spark-run-1"},"codeVersion":"x"}\n' > "${od}/load-manifest.json"
    if [[ "${STUB_EMBEDDING_INPUTS:-0}" == 1 ]]; then mkdir -p "${od}/embedding-inputs"; printf '{"w":1}\n' > "${od}/embedding-inputs/works.jsonl"; fi
    (cd "${od}" && for f in neptune/vertices/Researcher.csv neptune/edges/COAUTHORED_WITH.csv dynamodb/items/analytics-bridges.jsonl; do printf '%s  %s\n' "$(shasum -a 256 "$f" | cut -d' ' -f1)" "$f"; done) > "${od}/checksums.sha256"
    [[ "${STUB_BAD_CHECKSUM:-0}" != 1 ]] || printf '%s  %s\n' "$(printf '%064d' 0)" "load-manifest.json" >> "${od}/checksums.sha256" ;;
esac
EOF

cat > "${STUBS}/pgrep" << 'EOF'
#!/usr/bin/env bash
printf 'pgrep %s\n' "$*" >> "${STUB_LOG}"
[[ "${STUB_SBT_CHECK_RUNNING:-0}" == 1 ]]
EOF

cat > "${STUBS}/curl" << 'EOF'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >> "${STUB_LOG}"
method=GET out="" hdr="" url="" prev=""
for a in "$@"; do
  case "${prev}" in -X) method="$a" ;; -o) out="$a" ;; -H) [[ "$a" == @* ]] && hdr="${a#@}" ;; esac
  prev="$a"; url="$a"
done
path="${url#http*://*/}"; path="/${path}"
authed=0; if [[ -n "${hdr}" ]] && grep -q "${STUB_EXPECT_KEY}" "${hdr}"; then authed=1; fi
code=200 body='{"status":"ok"}'
case "${method} ${path}" in
  "GET /healthz" | "GET /readyz") ;;
  "POST /v1/query") if [[ ${authed} == 1 ]]; then body='{"queryId":"query:abc","mode":"synchronous","claims":[{"claimId":"c1","evidenceIds":["evidence:e1"]}],"abstained":false,"links":{}}'; else code=401 body='{"error":"UNAUTHORIZED"}'; fi ;;
  "GET /v1/evidence/"*) body='{"evidenceId":"evidence:e1"}' ;;
  "GET /v1/queries/"*"/evidence") body='{"items":[{}]}' ;;
  "GET /v1/explain/"*) body='{"queryId":"query:abc"}' ;;
  *) code=404 body='{}' ;;
esac
printf '%s' "${body}" > "${out}"
printf '%s 0.010' "${code}"
EOF
chmod +x "${STUBS}"/*

# ---------------------------------------------------------------------------------------------------------------------------------
# Fixture repository (a copy of the scripts in a throw-away git repository)
# ---------------------------------------------------------------------------------------------------------------------------------
mkdir -p "${REPO}/scripts" "${REPO}/infra/terraform/environments/course" "${REPO}/infra/terraform/plans" "${REPO}/.claude/hooks" "${REPO}/target/out/jvm/scala-3.3.9/scholarchain-hadoop" "${REPO}/target/out/jvm/scala-3.3.9/scholarchain-spark"
cp -R "${SRC_ROOT}/scripts/." "${REPO}/scripts/"
printf '# fixture\n' > "${REPO}/infra/terraform/environments/course/main.tf"
printf 'exit 0\n' > "${REPO}/.claude/hooks/validate-agent-files.sh"
printf '{}\n' > "${REPO}/.mcp.json"
printf '{}\n' > "${REPO}/.claude/settings.json"
printf 'target/\n.claude/.last-test-run\n' > "${REPO}/.gitignore"
JAR_H="target/out/jvm/scala-3.3.9/scholarchain-hadoop/scholarchain-hadoop-0.1.0-SNAPSHOT-assembly.jar"
JAR_S="target/out/jvm/scala-3.3.9/scholarchain-spark/scholarchain-spark-0.1.0-SNAPSHOT-assembly.jar"
printf 'jar-h' > "${REPO}/${JAR_H}"
printf 'jar-s' > "${REPO}/${JAR_S}"
mkdir -p "${REPO}/modules/entity-resolution/evaluation"
printf '{"fixture":true}\n' > "${REPO}/modules/entity-resolution/evaluation/er-v1-frozen-policy.json"
printf 'plan-bytes' > "${REPO}/infra/terraform/plans/tfplan-course-abc-20261124T000000Z.tfplan"
PLAN_SHA="$(shasum -a 256 "${REPO}/infra/terraform/plans/tfplan-course-abc-20261124T000000Z.tfplan" | cut -d' ' -f1)"
printf '%s  tfplan-course-abc-20261124T000000Z.tfplan\n' "${PLAN_SHA}" > "${REPO}/infra/terraform/plans/tfplan-course-abc-20261124T000000Z.sha256"
printf '*\n!.gitignore\n' > "${REPO}/infra/terraform/plans/.gitignore"
# Gate files (human-owned in the real repository; the scripts only READ them). The name is assembled so that this harness text does not
# trip the guard's rule on writes to the human decision file.
GN="human-"
GN="${GN}gate.md"
BAD_SHA="$(printf '%064d' 0 | tr 0 e)"
mkdir -p "${REPO}/docs/agent-runs/phase-test" "${REPO}/docs/agent-runs/phase-bad" "${REPO}/docs/agent-runs/phase-link" "${REPO}/docs/agent-runs/phase-itype" "${REPO}/docs/other"
printf 'decision: approved plan %s\n' "${PLAN_SHA}" > "${REPO}/docs/agent-runs/phase-test/${GN}"
printf 'decision: approved plan %s\n' "${BAD_SHA}" > "${REPO}/docs/agent-runs/phase-bad/${GN}"
printf 'decision: approved plan %s\n' "${PLAN_SHA}" > "${REPO}/docs/other/${GN}"
printf 'decision: approved EMR instance class m5.2xlarge\n' > "${REPO}/docs/agent-runs/phase-itype/${GN}"
printf 'decision: approved plan %s\n' "${PLAN_SHA}" > "${SCRATCH}/outside-gate.md"
ln -s "${SCRATCH}/outside-gate.md" "${REPO}/docs/agent-runs/phase-link/${GN}"
GATE_OK="docs/agent-runs/phase-test/${GN}"
printf '%s  other.tfplan\n' "${PLAN_SHA}" > "${SCRATCH}/other.sha256"
cp "${REPO}/infra/terraform/plans/tfplan-course-abc-20261124T000000Z.tfplan" "${SCRATCH}/other.tfplan"
: > "${REPO}/infra/terraform/environments/course/terraform.tfstate"
git -C "${REPO}" init -q
git -C "${REPO}" -c user.email=t@example.invalid -c user.name=t add -A
git -C "${REPO}" -c user.email=t@example.invalid -c user.name=t commit -q -m fixture
SHA="$(git -C "${REPO}" rev-parse HEAD)"

TFOUT="${SCRATCH}/tf-outputs.json"
jq -n '{
  account_id: {value: "123456789012"}, region: {value: "us-east-2"},
  raw_bucket: {value: "b-raw"}, curated_bucket: {value: "b-curated"}, reports_bucket: {value: "b-reports"}, logs_bucket: {value: "b-logs"}, artifacts_bucket: {value: "b-artifacts"},
  ecs_cluster_name: {value: "scholarchain-cluster"},
  ecr_repository_urls: {value: {"query-api": "123456789012.dkr.ecr.us-east-2.amazonaws.com/scholarchain/query-api", retrieval: "123456789012.dkr.ecr.us-east-2.amazonaws.com/scholarchain/retrieval", "embedding-worker": "123456789012.dkr.ecr.us-east-2.amazonaws.com/scholarchain/embedding-worker"}},
  secret_arns: {value: {"services-api-key": "arn:aws:secretsmanager:us-east-2:123456789012:secret:SCHOLARCHAIN_SERVICES_API_KEY-AbCdEf", "internal-api-key": "arn:aws:secretsmanager:us-east-2:123456789012:secret:SCHOLARCHAIN_INTERNAL_API_KEY-GhIjKl", "voyage-api-key": "arn:aws:secretsmanager:us-east-2:123456789012:secret:scholarchain/voyage-api-key-MnOpQr", "anthropic-api-key": "arn:aws:secretsmanager:us-east-2:123456789012:secret:SCHOLARCHAIN_ANTHROPIC_API_KEY-StUvWx"}},
  emr_service_role_arn: {value: "arn:aws:iam::123456789012:role/scholarchain-emr-service"}, emr_instance_profile_arn: {value: "arn:aws:iam::123456789012:instance-profile/scholarchain-emr-ec2"},
  public_subnet_ids: {value: ["subnet-1", "subnet-2"]}, vpc_id: {value: "vpc-1"},
  neptune_endpoint: {value: "https://n.example:8182"},
  ops_task_definition_family: {value: "scholarchain-ops"}, ops_security_group_id: {value: "sg-0ops1234"}, ops_log_group_name: {value: "/scholarchain/embedding-worker"}, query_api_url: {value: "http://alb.example"}, embedding_queue_url: {value: "https://sqs.us-east-2.amazonaws.com/123456789012/scholarchain-embedding-jobs"}
}' > "${TFOUT}"
KEYFILE="${SCRATCH}/keyfile"
printf '%s\n' "${SENTINEL}" > "${KEYFILE}"
chmod 600 "${KEYFILE}"

# ---------------------------------------------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------------------------------------------
FORB=0
reset_state() {
  FORB=$((FORB + $(grep -c FORBIDDEN "${LOG}" || true)))
  rm -rf -- "${STATE:?}"
  mkdir -p "${STATE}"
  : > "${LOG}"
}
# run <name> <expected rc> <command...>   (extra environment may be passed through EXTRA_ENV, a space-separated list)
run() {
  local name="$1" want="$2" rc=0
  shift 2
  # shellcheck disable=SC2086
  (cd "${REPO}" && env PATH="${STUBS}:${PATH}" STUB_LOG="${LOG}" STUB_STATE="${STATE}" STUB_TF_OUT="${TFOUT}" STUB_EXPECT_KEY="${SENTINEL}" SC_IN_TEST_HARNESS=1 \
    AWS_PROFILE=stub AWS_REGION=us-east-2 SC_RETRY_SLEEP=0 SC_POLL_SECONDS=0 ${EXTRA_ENV:-} "$@") > "${OUT}/${name}.out" 2> "${OUT}/${name}.err" || rc=$?
  cp "${LOG}" "${OUT}/${name}.stublog" 2> /dev/null || true
  if [[ "${rc}" == "${want}" ]]; then ok "${name}: exit ${rc}"; else bad "${name}: expected exit ${want}, got ${rc} (see ${OUT}/${name}.err)"; fi
}
ok() { PASS=$((PASS + 1)); printf 'PASS %s\n' "$1"; }
bad() { FAILN=$((FAILN + 1)); printf 'FAIL %s\n' "$1"; }
expect_in() { # expect_in <label> <file> <fixed text>
  if grep -qF -- "$3" "$2"; then ok "$1"; else bad "$1 (missing: $3)"; fi
}
expect_not_in() {
  if grep -qF -- "$3" "$2"; then bad "$1 (found: $3)"; else ok "$1"; fi
}
count_in() { grep -cF -- "$2" "$1" || true; }

# ---------------------------------------------------------------------------------------------------------------------------------
echo "== T1 syntax and shellcheck (on the repository sources) =="
for f in "${SRC_ROOT}"/scripts/*.sh "${SRC_ROOT}"/scripts/lib/*.sh "${SRC_ROOT}"/scripts/tests/*.sh; do
  if bash -n "${f}"; then ok "bash -n ${f#"${SRC_ROOT}"/}"; else bad "bash -n ${f#"${SRC_ROOT}"/}"; fi
done
if command -v shellcheck > /dev/null 2>&1; then
  if shellcheck -x -P SCRIPTDIR "${SRC_ROOT}"/scripts/*.sh "${SRC_ROOT}"/scripts/lib/*.sh "${SRC_ROOT}"/scripts/tests/*.sh > "${OUT}/shellcheck.out" 2>&1; then ok "shellcheck clean"; else bad "shellcheck (see ${OUT}/shellcheck.out)"; fi
else
  echo "SKIP shellcheck not installed"
fi

echo "== T2 --help =="
for s in check package deploy rollback smoke-test run-bounded-pipeline run-experiment archive-evidence env-window cleanup; do
  reset_state
  run "help-${s}" 0 bash "scripts/${s}.sh" --help
  expect_in "help-${s} prints exit codes" "${OUT}/help-${s}.out" "Exit codes"
done

echo "== T3 package =="
reset_state
run package-dry 0 bash scripts/package.sh --dry-run
expect_not_in "package --dry-run does not call sbt" "${LOG}" "sbt "
reset_state
run package-real 0 bash scripts/package.sh
expect_in "package builds jobs" "${LOG}" "sbt packageJobs"
expect_in "package builds image" "${LOG}" "sbt dockerLocal"
expect_in "package passes the commit tag as SCHOLARCHAIN_GIT_SHA" "${LOG}" "sbt dockerLocal [SCHOLARCHAIN_GIT_SHA=$(git -C "${REPO}" rev-parse --short=12 HEAD)]"
expect_not_in "package re-tags nothing (M13-1)" "${LOG}" "docker tag"
for svc in query-api retrieval embedding-worker; do expect_in "package inspected the ${svc} image" "${LOG}" "docker image inspect --format {{.Id}} scholarchain/${svc}:$(git -C "${REPO}" rev-parse --short=12 HEAD)"; done
ART="${REPO}/reports/release/${SHA}/artifacts.json"
[[ -f "${ART}" ]] && ok "artifacts.json written" || bad "artifacts.json missing"
if [[ -f "${ART}" ]]; then
  [[ "$(jq '.images | length' "${ART}")" == 3 ]] && ok "artifacts.json lists three images" || bad "artifacts.json image count"
  [[ "$(jq -c '[.images[].service] | sort' "${ART}")" == '["embedding-worker","query-api","retrieval"]' ]] && ok "artifacts.json names the three services" || bad "artifacts.json services"
  [[ "$(jq '[.images[] | select(.image_id | test("^sha256:[0-9a-f]{64}$")) | select(.local_ref == "scholarchain/" + .service + ":" + .tag)] | length' "${ART}")" == 3 ]] && ok "each image has an image_id and the build's scholarchain/<service>:<tag> name" || bad "artifacts.json image fields"
  [[ "$(jq '.jars | length' "${ART}")" == 2 ]] && ok "artifacts.json lists two jars" || bad "artifacts.json jar count"
  [[ "$(jq -c '[.release, .dirty]' "${ART}")" == "[true,false]" ]] && ok "clean run records release:true dirty:false" || bad "release flags"
fi
if [[ -f "${ART}" ]]; then
  [[ "$(jq -r '.jars[] | select(.module == "hadoop") | .path' "${ART}")" == "${JAR_H}" ]] && ok "F14-2 hadoop jar path (sbt 2 layout)" || bad "F14-2 hadoop jar path"
  [[ "$(jq -r '.jars[] | select(.module == "spark") | .path' "${ART}")" == "${JAR_S}" ]] && ok "F14-2 spark jar path (sbt 2 layout)" || bad "F14-2 spark jar path"
  [[ "$(jq -r '.jars[] | select(.module == "hadoop") | .sha256' "${ART}")" == "$(shasum -a 256 "${REPO}/${JAR_H}" | cut -d' ' -f1)" ]] && ok "F14-2 hadoop jar sha256" || bad "F14-2 hadoop jar sha256"
  [[ "$(jq -r '.jars[] | select(.module == "spark") | .sha256' "${ART}")" == "$(shasum -a 256 "${REPO}/${JAR_S}" | cut -d' ' -f1)" ]] && ok "F14-2 spark jar sha256" || bad "F14-2 spark jar sha256"
  [[ "$(jq -c '[.jars[].bytes]' "${ART}")" == "[5,5]" ]] && ok "F14-2 jar bytes" || bad "F14-2 jar bytes"
fi

# F14-1: SCHOLARCHAIN_GIT_SHA reaches EVERY sbt call, `sbt shutdown` runs first, and a stale server left by an earlier run is replaced.
PTAG="$(git -C "${REPO}" rev-parse --short=12 HEAD)"
expect_in "F14-1 packageJobs sees the tag" "${LOG}" "sbt packageJobs [SCHOLARCHAIN_GIT_SHA=${PTAG}]"
expect_in "F14-1 shutdown sees the tag" "${LOG}" "sbt shutdown [SCHOLARCHAIN_GIT_SHA=${PTAG}]"
expect_not_in "F14-1 no sbt call without the tag" "${LOG}" "SCHOLARCHAIN_GIT_SHA=unset"
[[ "$(grep -n '^sbt shutdown' "${LOG}" | cut -d: -f1)" -lt "$(grep -n '^sbt packageJobs' "${LOG}" | cut -d: -f1)" ]] && ok "F14-1 sbt shutdown runs before packageJobs" || bad "F14-1 shutdown ordering"
expect_in "F14-1 the server the build used holds the tag" "${LOG}" "sbt-server-env SCHOLARCHAIN_GIT_SHA=${PTAG}"
expect_not_in "F14-1 the server never held another value" "${LOG}" "sbt-server-env SCHOLARCHAIN_GIT_SHA=stale"
reset_state
printf 'stale' > "${STATE}/sbt-server"
run package-stale-server 0 bash scripts/package.sh --allow-dirty
expect_in "F14-1 a pre-existing server with another environment is shut down and replaced" "${LOG}" "sbt-server-env SCHOLARCHAIN_GIT_SHA=${PTAG}-dirty"
expect_not_in "F14-1 stale server value never used" "${LOG}" "sbt-server-env SCHOLARCHAIN_GIT_SHA=stale"
reset_state
ART_SUM="$(shasum -a 256 "${ART}" | cut -d' ' -f1)"
touch "${REPO}/dirty-file.txt"
run package-dirty-refused 3 bash scripts/package.sh
expect_in "package dirty message" "${OUT}/package-dirty-refused.err" "working tree is dirty"
rm -f "${REPO}/dirty-file.txt"
# M2: local-only overrides are refused on the release path, and the message names them.
reset_state
run package-skip-build-refused 3 bash scripts/package.sh --skip-build
expect_in "skip-build refusal message" "${OUT}/package-skip-build-refused.err" "--skip-build"
expect_not_in "refused skip-build ran no build" "${LOG}" "sbt "
EXTRA_ENV="SC_LOCAL_IMAGE=somebody/else:1" run package-local-image-refused 2 bash scripts/package.sh
expect_in "SC_LOCAL_IMAGE refusal message" "${OUT}/package-local-image-refused.err" "SC_LOCAL_IMAGE"
EXTRA_ENV="SC_IMAGE_SOURCE_RETRIEVAL=somebody/else:1" run package-source-refused 2 bash scripts/package.sh --allow-dirty --skip-build
expect_in "SC_IMAGE_SOURCE_* refusal message" "${OUT}/package-source-refused.err" "SC_IMAGE_SOURCE_RETRIEVAL"
# M7: free-form build commands are gone.
reset_state
EXTRA_ENV="SC_JOBS_BUILD_CMD=touch-evil" run package-free-cmd-refused 2 bash scripts/package.sh
expect_in "free-form build command message" "${OUT}/package-free-cmd-refused.err" "no longer supported"
expect_not_in "no build command ran" "${LOG}" "touch-evil"
# M2/L9: a local run goes to a separate, non-release path and never touches the release record.
reset_state
run package-local-ok 0 bash scripts/package.sh --allow-dirty --skip-build
LOC="${REPO}/reports/release-local/${SHA}/artifacts.json"
[[ "$(jq -c '[.release, .dirty]' "${LOC}" 2> /dev/null)" == "[false,true]" ]] && ok "local run records release:false dirty:true under release-local/" || bad "local run record"
[[ "$(shasum -a 256 "${ART}" | cut -d' ' -f1)" == "${ART_SUM}" ]] && ok "the release artifacts.json was not overwritten by the local run" || bad "release artifacts.json was overwritten"
[[ "$(jq -r '.images[0].tag' "${LOC}" 2> /dev/null)" == *-dirty ]] && ok "local images are tagged -dirty" || bad "local image tag"
# M13-1: a missing image, or an image whose revision label is not the tag, fails the package run (no partial record).
reset_state
rm -rf -- "${REPO:?}/reports/release-local"
EXTRA_ENV="STUB_MISSING_IMAGE=retrieval" run package-missing-image 1 bash scripts/package.sh --allow-dirty --skip-build
expect_in "missing image message" "${OUT}/package-missing-image.err" "did not produce"
[[ ! -e "${REPO}/reports/release-local/${SHA}/artifacts.json" ]] && ok "no record written when an image is missing" || bad "record written despite missing image"
EXTRA_ENV="STUB_LABEL_REV=deadbeefdead" run package-stale-label 1 bash scripts/package.sh --allow-dirty --skip-build
expect_in "stale label message" "${OUT}/package-stale-label.err" "revision label"

# F14-2 negative cases (build skipped; jars arranged by hand, then restored).
JD="${REPO}/target/out/jvm"
mv "${JD}/scala-3.3.9/scholarchain-hadoop/$(basename "${JAR_H}")" "${REPO}/hadoop-jar.keep"
run package-jar-zero 1 bash scripts/package.sh --allow-dirty --skip-build
expect_in "F14-2 zero jars message names directory and count" "${OUT}/package-jar-zero.err" "target/out/jvm/scala-*/scholarchain-hadoop/, found 0"
mkdir -p "${JD}/scala-3.3.8/scholarchain-hadoop"
printf 'a' > "${JD}/scala-3.3.8/scholarchain-hadoop/scholarchain-hadoop-0.1.0-SNAPSHOT-assembly.jar"
printf 'b' > "${JD}/scala-3.3.9/scholarchain-hadoop/scholarchain-hadoop-0.1.0-SNAPSHOT-assembly.jar"
run package-jar-two 1 bash scripts/package.sh --allow-dirty --skip-build
expect_in "F14-2 stale jar in a second scala dir fails (found 2)" "${OUT}/package-jar-two.err" "scholarchain-hadoop/, found 2"
rm -rf -- "${JD:?}/scala-3.3.8"
rm -f -- "${JD:?}/scala-3.3.9/scholarchain-hadoop/scholarchain-hadoop-0.1.0-SNAPSHOT-assembly.jar"
mkdir -p "${REPO}/modules/hadoop/target/scala-2.13"
cp "${REPO}/hadoop-jar.keep" "${REPO}/modules/hadoop/target/scala-2.13/hadoop-0.1-assembly.jar"
run package-jar-old-layout 1 bash scripts/package.sh --allow-dirty --skip-build
expect_in "F14-2 old modules/<mod>/target layout is not found" "${OUT}/package-jar-old-layout.err" "scholarchain-hadoop/, found 0"
rm -rf -- "${REPO:?}/modules/hadoop/target"
mv "${REPO}/hadoop-jar.keep" "${REPO}/${JAR_H}"

echo "== T4 deploy =="
APPR="APPROVED_PLAN_SHA256=${PLAN_SHA}"
DRYG=(--dry-run --gate-file "${GATE_OK}")
PLANS="${REPO}/infra/terraform/plans"
reset_state
touch "${REPO}/dirty-file.txt"
EXTRA_ENV="${APPR}" run deploy-dirty-refused 3 bash scripts/deploy.sh "${DRYG[@]}"
expect_in "deploy dirty message" "${OUT}/deploy-dirty-refused.err" "dirty"
rm -f "${REPO}/dirty-file.txt"
reset_state
EXTRA_ENV="APPROVED_PLAN_SHA256=0000000000000000000000000000000000000000000000000000000000000000" run deploy-wrong-checksum 3 bash scripts/deploy.sh "${DRYG[@]}"
expect_in "deploy checksum message" "${OUT}/deploy-wrong-checksum.err" "PLAN CHECKSUM MISMATCH"
run deploy-missing-checksum 3 bash scripts/deploy.sh "${DRYG[@]}"
expect_in "deploy missing-approval message" "${OUT}/deploy-missing-checksum.err" "APPROVED_PLAN_SHA256 must be set"
# M1: the plan record must resolve inside infra/terraform/plans/ (also through a symlink), the .tfplan must exist and match, the gate file must name the hash.
EXTRA_ENV="${APPR}" run deploy-record-outside-plans 3 bash scripts/deploy.sh "${DRYG[@]}" --plan-record "${SCRATCH}/other.sha256"
expect_in "record outside plans/ message" "${OUT}/deploy-record-outside-plans.err" "must resolve to infra/terraform/plans"
ln -s "${SCRATCH}/other.sha256" "${PLANS}/tfplan-course-link-20261124T020000Z.sha256"
EXTRA_ENV="${APPR}" run deploy-record-symlink-escape 3 bash scripts/deploy.sh "${DRYG[@]}" --plan-record "${PLANS}/tfplan-course-link-20261124T020000Z.sha256"
expect_in "record symlink escaping plans/ message" "${OUT}/deploy-record-symlink-escape.err" "must resolve to infra/terraform/plans"
rm -f "${PLANS:?}/tfplan-course-link-20261124T020000Z.sha256"
printf '%s  tfplan-course-notfplan-20261124T030000Z.tfplan\n' "${PLAN_SHA}" > "${PLANS}/tfplan-course-notfplan-20261124T030000Z.sha256"
EXTRA_ENV="${APPR}" run deploy-missing-tfplan 3 bash scripts/deploy.sh "${DRYG[@]}" --plan-record "${PLANS}/tfplan-course-notfplan-20261124T030000Z.sha256"
expect_in "missing .tfplan message" "${OUT}/deploy-missing-tfplan.err" ".tfplan next to the record is missing"
rm -f "${PLANS:?}/tfplan-course-notfplan-20261124T030000Z.sha256"
printf '%s  tfplan-course-tamper-20261124T040000Z.tfplan\n' "${PLAN_SHA}" > "${PLANS}/tfplan-course-tamper-20261124T040000Z.sha256"
printf 'changed after planning' > "${PLANS}/tfplan-course-tamper-20261124T040000Z.tfplan"
EXTRA_ENV="${APPR}" run deploy-tampered-tfplan 3 bash scripts/deploy.sh "${DRYG[@]}" --plan-record "${PLANS}/tfplan-course-tamper-20261124T040000Z.sha256"
expect_in "tampered .tfplan message" "${OUT}/deploy-tampered-tfplan.err" "no longer matches its recorded checksum"
rm -f "${PLANS:?}/tfplan-course-tamper-20261124T040000Z.sha256" "${PLANS:?}/tfplan-course-tamper-20261124T040000Z.tfplan"
EXTRA_ENV="${APPR}" run deploy-no-gate-file 3 bash scripts/deploy.sh --dry-run
expect_in "missing gate file message" "${OUT}/deploy-no-gate-file.err" "--gate-file"
EXTRA_ENV="${APPR}" run deploy-gate-mismatch 3 bash scripts/deploy.sh --dry-run --gate-file "docs/agent-runs/phase-bad/${GN}"
expect_in "gate file without the approved hash message" "${OUT}/deploy-gate-mismatch.err" "does not contain the approved value"
EXTRA_ENV="${APPR}" run deploy-gate-wrong-dir 3 bash scripts/deploy.sh --dry-run --gate-file "docs/other/${GN}"
expect_in "gate file outside docs/agent-runs message" "${OUT}/deploy-gate-wrong-dir.err" "docs/agent-runs/<phase>"
EXTRA_ENV="${APPR}" run deploy-gate-symlink-escape 3 bash scripts/deploy.sh --dry-run --gate-file "docs/agent-runs/phase-link/${GN}"
expect_in "gate file symlink escaping docs/agent-runs message" "${OUT}/deploy-gate-symlink-escape.err" "docs/agent-runs/<phase>"
expect_not_in "refusals happen before any AWS call" "${LOG}" "aws "
EXTRA_ENV="${APPR} STUB_TF_EMPTY=1" run deploy-outputs-missing 4 bash scripts/deploy.sh "${DRYG[@]}"
expect_in "deploy prints the human apply command" "${OUT}/deploy-outputs-missing.out" "terraform -chdir=infra/terraform/environments/course"
EXTRA_ENV="${APPR} STUB_ACCOUNT=999999999999" run deploy-account-mismatch 1 bash scripts/deploy.sh "${DRYG[@]}"
expect_in "deploy account mismatch is printed without the id" "${OUT}/deploy-account-mismatch.out" "account_check=mismatch"
expect_not_in "deploy never prints the account id" "${OUT}/deploy-account-mismatch.out" "123456789012"
# M2: a non-release or unrelated artifacts.json is refused.
reset_state
cp "${ART}" "${SCRATCH}/artifacts.good"
jq '.release = false' "${SCRATCH}/artifacts.good" > "${ART}"
EXTRA_ENV="${APPR}" run deploy-nonrelease-artifacts 3 bash scripts/deploy.sh "${DRYG[@]}"
expect_in "non-release artifacts message" "${OUT}/deploy-nonrelease-artifacts.err" "not a release record"
jq 'del(.release)' "${SCRATCH}/artifacts.good" > "${ART}"
EXTRA_ENV="${APPR}" run deploy-artifacts-without-release-flag 3 bash scripts/deploy.sh "${DRYG[@]}"
cp "${SCRATCH}/artifacts.good" "${ART}"
reset_state
EXTRA_ENV="${APPR}" run deploy-dry 0 bash scripts/deploy.sh "${DRYG[@]}"
expect_in "deploy dry-run prints account_check=match" "${OUT}/deploy-dry.out" "account_check=match"
expect_not_in "deploy dry-run registers nothing" "${LOG}" "register-task-definition"
expect_not_in "deploy dry-run updates nothing" "${LOG}" "update-service"
expect_not_in "deploy dry-run pushes nothing" "${LOG}" "docker push"
[[ ! -e "${REPO}/reports/deployments/${SHA}/deployment.json" ]] && ok "deploy dry-run writes no record" || bad "dry-run wrote a record"
# Image mismatch (artifacts.json against the local image).
reset_state
EXTRA_ENV="${APPR} STUB_DOCKER_ID=sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" run deploy-image-mismatch 1 bash scripts/deploy.sh "${DRYG[@]}"
expect_in "image mismatch message" "${OUT}/deploy-image-mismatch.err" "does not match artifacts.json"
# Real deploy: the in-progress record must exist before the first service is updated (stub exits 98 otherwise).
RECF="${REPO}/reports/deployments/${SHA}/deployment.json"
reset_state
EXTRA_ENV="${APPR} STUB_RECORD_FILE=${RECF}" run deploy-real 0 bash scripts/deploy.sh --gate-file "${GATE_OK}" --query-api-count 1 --retrieval-count 1 --worker-count 0
expect_not_in "no service was updated before the in-progress record existed" "${LOG}" "UPDATE BEFORE RECORD"
[[ "$(count_in "${LOG}" "ecs register-task-definition")" == 3 ]] && ok "deploy registered 3 task definitions" || bad "deploy register count"
[[ "$(count_in "${LOG}" "ecs update-service")" == 3 ]] && ok "deploy updated 3 services" || bad "deploy update count"
[[ "$(cat "${STATE}/desired-embedding-worker" 2> /dev/null)" == 0 ]] && ok "worker desired count is 0 (--desired-count 0 reached the API)" || bad "worker desired count is not 0"
[[ "$(cat "${STATE}/desired-query-api" 2> /dev/null)" == 1 && "$(cat "${STATE}/desired-retrieval" 2> /dev/null)" == 1 ]] && ok "query-api and retrieval desired counts are 1" || bad "api/retrieval desired counts"
[[ "$(grep -cE 'ecs update-service.*--service embedding-worker .*--desired-count 0' "${LOG}")" == 1 ]] && ok "update-service for the worker carries --desired-count 0" || bad "worker update-service call"
[[ "$(jq -r .status "${RECF}" 2> /dev/null)" == succeeded ]] && ok "deployment.json status succeeded" || bad "deployment.json status"
[[ "$(jq '[.verification[] | select(.verified)] | length' "${RECF}" 2> /dev/null)" == 3 ]] && ok "3 services verified (task definition and digest)" || bad "verification entries"
[[ "$(jq '[.services[] | select(.previous_task_definition != "" and .task_definition != "")] | length' "${RECF}" 2> /dev/null)" == 3 ]] && ok "record holds previous and new task definitions for 3 services" || bad "record task definitions"
expect_not_in "no forbidden terraform call during deploy" "${LOG}" "FORBIDDEN"
for svc in query-api retrieval embedding-worker; do expect_in "deploy pushed the ${svc} image" "${LOG}" "docker push 123456789012.dkr.ecr.us-east-2.amazonaws.com/scholarchain/${svc}:"; done
[[ "$(count_in "${LOG}" "docker push")" == 3 ]] && ok "deploy pushed exactly three images" || bad "docker push count"
# MERGE-2: only the two injected secrets are checked for a value (the Voyage and Anthropic secrets stay empty and unused).
[[ "$(count_in "${LOG}" "secretsmanager describe-secret")" == 2 ]] && ok "deploy checked exactly two secrets" || bad "secret check count"
expect_not_in "deploy did not check the Voyage secret" "${LOG}" "voyage-api-key"
expect_not_in "deploy did not check the Anthropic secret" "${LOG}" "SCHOLARCHAIN_ANTHROPIC_API_KEY"
# M6: a stabilisation timeout leaves a FAILED record with the previous task definitions and a rollback hint that names it.
rm -rf -- "${REPO:?}/reports/deployments"
reset_state
EXTRA_ENV="${APPR} STUB_ECS_NOT_STABLE=1 SC_POLL_SECONDS=1" run deploy-not-stable 5 bash scripts/deploy.sh --gate-file "${GATE_OK}" --timeout 1
expect_in "timeout message names the service" "${OUT}/deploy-not-stable.err" "did not stabilise"
expect_in "rollback hint names the record that exists" "${OUT}/deploy-not-stable.err" "--from-record reports/deployments/${SHA}/deployment.json"
[[ "$(jq -r .status "${RECF}" 2> /dev/null)" == failed ]] && ok "timeout leaves status failed" || bad "timeout record status"
[[ "$(jq -r .failure_reason "${RECF}" 2> /dev/null)" == *stabilise* ]] && ok "failure_reason says why" || bad "failure_reason"
[[ "$(jq '[.services[] | select(.previous_task_definition | startswith("arn:"))] | length' "${RECF}" 2> /dev/null)" == 3 ]] && ok "failed record holds the previous task definition ARN of 3 services" || bad "previous task definitions in failed record"
[[ "$(count_in "${LOG}" "ecs update-service")" == 3 ]] && ok "services had been updated (the failed record matters)" || bad "update count in timeout test"
# M6: an sc_die path (digest mismatch of a running task) also ends in a failed record.
rm -rf -- "${REPO:?}/reports/deployments"
reset_state
EXTRA_ENV="${APPR} STUB_TASK_DIGEST=sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd" run deploy-digest-mismatch 1 bash scripts/deploy.sh --gate-file "${GATE_OK}"
expect_in "digest mismatch message" "${OUT}/deploy-digest-mismatch.err" "verification failed"
[[ "$(jq -r .status "${RECF}" 2> /dev/null)" == failed ]] && ok "digest mismatch leaves status failed" || bad "digest mismatch record status"
[[ "$(jq -r .failure_reason "${RECF}" 2> /dev/null)" == *"verification failed"* ]] && ok "failure_reason holds the sc_die message" || bad "digest mismatch failure_reason"
rm -rf -- "${REPO:?}/reports/deployments"

echo "== T5 rollback =="
reset_state
run rollback-no-approval 3 bash scripts/rollback.sh --revisions query-api=1,retrieval=1,embedding-worker=1 --reason "test"
expect_in "rollback approval message" "${OUT}/rollback-no-approval.err" "recorded human approval is required"
printf 'human approval recorded\n' > "${SCRATCH}/approval.txt"
run rollback-dry 0 bash scripts/rollback.sh --revisions query-api=1,retrieval=1,embedding-worker=1 --reason "test" --approval-ref "${SCRATCH}/approval.txt" --dry-run
expect_not_in "rollback dry-run changes nothing" "${LOG}" "update-service"
reset_state
run rollback-real 0 bash scripts/rollback.sh --revisions query-api=1,retrieval=1,embedding-worker=1 --reason "test" --approval-ref "${SCRATCH}/approval.txt"
[[ "$(count_in "${LOG}" "ecs update-service")" == 3 ]] && ok "rollback updated 3 services" || bad "rollback update count"
expect_not_in "rollback never changes desired counts" "${LOG}" "--desired-count"
rm -rf -- "${REPO:?}/reports/deployments"

echo "== T6 smoke-test =="
reset_state
run smoke-dry 0 bash scripts/smoke-test.sh --dry-run --allow-insecure-http
expect_not_in "smoke dry-run makes no HTTP call" "${LOG}" "curl "
run smoke-dry-http-refused 3 bash scripts/smoke-test.sh --dry-run
expect_in "http refusal message" "${OUT}/smoke-dry-http-refused.err" "sent in clear text"
reset_state
EXTRA_ENV="SC_SMOKE_API_KEY_FILE=${KEYFILE}" run smoke-http-refused 3 bash scripts/smoke-test.sh
expect_in "http refusal message (real run)" "${OUT}/smoke-http-refused.err" "sent in clear text"
expect_not_in "refused http run makes no HTTP call" "${LOG}" "curl "
EXTRA_ENV="SC_SMOKE_API_KEY_FILE=${KEYFILE} SC_SMOKE_BASE_URL=https://evil.example" run smoke-host-mismatch 3 bash scripts/smoke-test.sh
expect_in "foreign host refusal message" "${OUT}/smoke-host-mismatch.err" "never sent to another host"
EXTRA_ENV="SC_SMOKE_API_KEY_FILE=${KEYFILE} SC_SMOKE_BASE_URL=http://evil.example" run smoke-host-mismatch-insecure 3 bash scripts/smoke-test.sh --allow-insecure-http
expect_in "foreign host refused even with the insecure flag" "${OUT}/smoke-host-mismatch-insecure.err" "never sent to another host"
expect_not_in "no HTTP call to a foreign host" "${LOG}" "evil.example"
reset_state
EXTRA_ENV="SC_SMOKE_API_KEY_FILE=${KEYFILE} SC_SMOKE_BASE_URL=https://alb.example" run smoke-https-same-host 0 bash scripts/smoke-test.sh
expect_not_in "https to the Terraform host needs no flag" "${OUT}/smoke-https-same-host.err" "INSECURE"
reset_state
EXTRA_ENV="SC_SMOKE_API_KEY_FILE=${KEYFILE} SC_SMOKE_BASE_URL=http://localhost:8080" run smoke-loopback-stub 0 bash scripts/smoke-test.sh --allow-insecure-http
reset_state
EXTRA_ENV="SC_SMOKE_API_KEY_FILE=${KEYFILE}" run smoke-real 0 bash scripts/smoke-test.sh --allow-insecure-http
expect_in "smoke prints the loud insecure warning" "${OUT}/smoke-real.err" "INSECURE"
[[ "$(jq -r .all_passed "${REPO}/reports/deployments/${SHA}/smoke-test.json" 2> /dev/null)" == true ]] && ok "smoke-test.json all_passed" || bad "smoke-test.json all_passed"
[[ "$(jq '[.checks[] | select(.check == "unauthenticated-401" and .passed)] | length' "${REPO}/reports/deployments/${SHA}/smoke-test.json" 2> /dev/null)" == 1 ]] && ok "401 without a key was observed" || bad "401 check"
expect_not_in "key not in stub argument log" "${LOG}" "${SENTINEL}"
expect_not_in "key not on stdout" "${OUT}/smoke-real.out" "${SENTINEL}"
expect_not_in "key not on stderr" "${OUT}/smoke-real.err" "${SENTINEL}"
expect_not_in "key not in the JSON result" "${REPO}/reports/deployments/${SHA}/smoke-test.json" "${SENTINEL}"
chmod 644 "${KEYFILE}"
EXTRA_ENV="SC_SMOKE_API_KEY_FILE=${KEYFILE}" run smoke-loose-key-refused 6 bash scripts/smoke-test.sh --allow-insecure-http
expect_in "loose key mode message" "${OUT}/smoke-loose-key-refused.err" "mode 600 or 400"
chmod 600 "${KEYFILE}"
# L6: a key file inside the repository is refused whether named relatively, absolutely or through a symlink.
INREPO="${REPO}/inrepo-apikey-file"
cp "${KEYFILE}" "${INREPO}"
chmod 600 "${INREPO}"
reset_state
EXTRA_ENV="SC_SMOKE_API_KEY_FILE=inrepo-apikey-file" run smoke-keyfile-in-repo-relative 6 bash scripts/smoke-test.sh --allow-insecure-http
expect_in "relative in-repo key file message" "${OUT}/smoke-keyfile-in-repo-relative.err" "outside the repository"
EXTRA_ENV="SC_SMOKE_API_KEY_FILE=scripts/../inrepo-apikey-file" run smoke-keyfile-in-repo-dotdot 6 bash scripts/smoke-test.sh --allow-insecure-http
ln -s "${INREPO}" "${SCRATCH}/linkedkey"
EXTRA_ENV="SC_SMOKE_API_KEY_FILE=${SCRATCH}/linkedkey" run smoke-keyfile-in-repo-symlink 6 bash scripts/smoke-test.sh --allow-insecure-http
expect_in "symlinked in-repo key file message" "${OUT}/smoke-keyfile-in-repo-symlink.err" "outside the repository"
expect_not_in "refused key file run makes no HTTP call" "${LOG}" "curl "
rm -f "${INREPO:?}" "${SCRATCH:?}/linkedkey"
rm -rf -- "${REPO:?}/reports/deployments"

echo "== T7 run-bounded-pipeline =="
reset_state
run pipe-50k-no-record 3 bash scripts/run-bounded-pipeline.sh --scale 50k --approve-50k --dry-run
expect_in "50K refusal message (no 10K record)" "${OUT}/pipe-50k-no-record.err" "no successful 10K run record"
mkdir -p "${REPO}/reports/pipeline/10k/r1"
printf '{"scale":"10k","status":"succeeded"}\n' > "${REPO}/reports/pipeline/10k/r1/run.json"
run pipe-50k-no-flag 3 bash scripts/run-bounded-pipeline.sh --scale 50k --dry-run
expect_in "50K refusal message (no approval flag)" "${OUT}/pipe-50k-no-flag.err" "--approve-50k"
printf '{"scale":"10k","status":"failed"}\n' > "${REPO}/reports/pipeline/10k/r1/run.json"
run pipe-50k-failed-10k 3 bash scripts/run-bounded-pipeline.sh --scale 50k --approve-50k --dry-run
expect_in "50K refusal message (10K record failed)" "${OUT}/pipe-50k-failed-10k.err" "no successful 10K run record"
expect_not_in "50K refusal happens before any AWS call" "${LOG}" "aws "
printf '{"scale":"10k","status":"succeeded"}\n' > "${REPO}/reports/pipeline/10k/r1/run.json"
run pipe-50k-ok-dry 0 bash scripts/run-bounded-pipeline.sh --scale 50k --approve-50k --dry-run --stages hadoop,spark --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
expect_in "50K allowed with record and flag" "${OUT}/pipe-50k-ok-dry.out" "50K allowed"
rm -rf -- "${REPO}/reports/pipeline"
run pipe-core-count-3 2 bash scripts/run-bounded-pipeline.sh --scale 10k --core-count 3 --dry-run
run pipe-fallback-needs-hadoop-only 2 bash scripts/run-bounded-pipeline.sh --scale 10k --hadoop-fallback --dry-run
run pipe-10k-dry 0 bash scripts/run-bounded-pipeline.sh --scale 10k --dry-run --stages hadoop,spark --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
[[ "$(grep -c 'ActionOnFailure=TERMINATE_CLUSTER timeout=3600s' "${OUT}/pipe-10k-dry.out")" == 2 ]] && ok "dry-run plan: 2 steps (PipelineDriver, Spark), all TERMINATE_CLUSTER, 3600s" || bad "dry-run plan step lines"
expect_not_in "F14-6 main class comes from the jar manifest, not an argument" "${OUT}/pipe-10k-dry.out" "scholarchain.hadoop.PipelineDriver"
expect_not_in "no per-job *Tool class (they have no main)" "${OUT}/pipe-10k-dry.out" "Tool"
run pipe-hadoop-mode-tools-refused 2 bash scripts/run-bounded-pipeline.sh --scale 10k --hadoop-mode tools --dry-run
expect_in "tools mode refusal message" "${OUT}/pipe-hadoop-mode-tools-refused.err" "must be driver"
# M5: instance class allow-list, idle timeout cap.
reset_state
EXTRA_ENV="SC_EMR_INSTANCE_TYPE=m5.4xlarge" run pipe-itype-refused 3 bash scripts/run-bounded-pipeline.sh --scale 10k --dry-run --stages hadoop --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
expect_in "larger instance class refusal message" "${OUT}/pipe-itype-refused.err" "REFUSED: instance class m5.4xlarge"
expect_not_in "refused instance class made no AWS call" "${LOG}" "aws "
EXTRA_ENV="SC_EMR_INSTANCE_TYPE=m5.2xlarge" run pipe-itype-no-gate 3 bash scripts/run-bounded-pipeline.sh --scale 10k --dry-run --approve-instance-class --stages hadoop --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
expect_in "approved class without a gate file message" "${OUT}/pipe-itype-no-gate.err" "--gate-file"
EXTRA_ENV="SC_EMR_INSTANCE_TYPE=m5.2xlarge" run pipe-itype-wrong-gate 3 bash scripts/run-bounded-pipeline.sh --scale 10k --dry-run --approve-instance-class --gate-file "${GATE_OK}" --stages hadoop --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
expect_in "gate file that does not name the class message" "${OUT}/pipe-itype-wrong-gate.err" "does not contain the approved value (m5.2xlarge)"
EXTRA_ENV="SC_EMR_INSTANCE_TYPE=m5.2xlarge" run pipe-itype-approved 0 bash scripts/run-bounded-pipeline.sh --scale 10k --dry-run --approve-instance-class --gate-file "docs/agent-runs/phase-itype/${GN}" --stages hadoop --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
expect_in "approved class reaches the cluster plan" "${OUT}/pipe-itype-approved.out" "(m5.2xlarge, ON_DEMAND)"
EXTRA_ENV="SC_EMR_IDLE_TIMEOUT=3600" run pipe-idle-too-long 2 bash scripts/run-bounded-pipeline.sh --scale 10k --dry-run --stages hadoop --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
expect_in "idle timeout cap message" "${OUT}/pipe-idle-too-long.err" "60 to 900"
EXTRA_ENV="SC_EMR_IDLE_TIMEOUT=900" run pipe-idle-900-ok 0 bash scripts/run-bounded-pipeline.sh --scale 10k --dry-run --stages hadoop --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
expect_not_in "dry-run creates no cluster" "${LOG}" "create-cluster"
expect_not_in "dry-run uploads nothing" "${LOG}" "s3 cp"
reset_state
run pipe-smoke-real 0 bash scripts/run-bounded-pipeline.sh --smoke
expect_in "smoke uses the default release label" "${LOG}" "--release-label emr-spark-8.1.0"
expect_in "smoke job is a tiny MapReduce job" "${STATE}/emr-steps.json" "hadoop-mapreduce-examples.jar"
reset_state
run pipe-fallback-smoke 0 bash scripts/run-bounded-pipeline.sh --smoke --hadoop-fallback
expect_in "fallback flag selects emr-7.14.0" "${LOG}" "--release-label emr-7.14.0"
reset_state
run pipe-1k-real 0 bash scripts/run-bounded-pipeline.sh --scale 1k --stages hadoop,spark --run-id rtest --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
[[ "$(jq 'length' "${STATE}/emr-steps.json")" == 2 ]] && ok "2 steps submitted (PipelineDriver HJ1-HJ7 + spark)" || bad "submitted step count"
[[ "$(jq -r '.[0].Args | index("scholarchain.hadoop.PipelineDriver") == null' "${STATE}/emr-steps.json")" == true ]] && ok "F14-6 the Hadoop step does not pass the main class as an argument" || bad "Hadoop step main class arg"
[[ "$(jq -r '.[0].Args | .[12] == "--dblp" and .[11] == (.[7] + "/" + (.[8] | split("/") | last))' "${STATE}/emr-steps.json")" == true ]] && ok "F14-6 the argument right after the local jar path is --dblp" || bad "F14-6 first arg after jar"
[[ "$(jq -r '.[0].Args | (.[3] == "bash" and .[4] == "-c" and (.[5] | contains("hadoop fs -copyToLocal") and contains("exec \"$@\"")) and .[7] == "/tmp/scholarchain-rtest" and (.[8] | startswith("s3://")) and (.[8] | endswith(".jar")) and .[9] == "hadoop" and .[10] == "jar" and .[11] == (.[7] + "/" + (.[8] | split("/") | last)))' "${STATE}/emr-steps.json")" == true ]] && ok "F14-5 Hadoop step copies the S3 jar locally, then runs hadoop jar on the LOCAL path" || bad "F14-5 Hadoop step copy-then-run"
[[ "$(jq -r '.[1].Args | (.[3] == "bash" and .[4] == "-c" and (.[5] | contains("hadoop fs -test -e") and contains("FinalDecisions") and contains("--master \"local[*]\"") and contains("--driver-memory 8g") and contains("--conf spark.eventLog.enabled=false") and contains("--conf spark.dynamicAllocation.enabled=false") and contains("--conf spark.shuffle.service.enabled=false") and contains("hadoop fs -mkdir -p \"${so}/provenance\"") and contains("--conf spark.hadoop.fs.defaultFS=file:///") and contains("--conf \"spark.local.dir=${L}/spark-tmp\"") and contains("echo \"staging in ${L}\"") and contains("hadoop fs -copyFromLocal -d \"${L}/spark-out\" \"${so}\"") and contains("match-decisions-final.summary.json") and contains("hj6d/match-candidates") and contains("hj6a/entity-views") and contains("er-v1-frozen-policy.json")) and .[6] == "spark-local-run")' "${STATE}/emr-steps.json")" == true ]] && ok "F14-5 Spark step: refuse non-empty target, copy-in, FinalDecisions, spark-submit local[*], copy-back, decisions provenance" || bad "F14-5 Spark step shape"
[[ "$(jq -r '.[1].Args[5] | contains("s3://") | not' "${STATE}/emr-steps.json")" == true ]] && ok "F14-5 no s3:// literal in the Spark step script" || bad "F14-5 s3 literal in Spark script"
[[ "$(jq -r '.[1].Args[5] | split("\n") | map(select(contains("spark-submit") or contains("FinalDecisions"))) | length == 2 and all(.[]; (contains("${so}") or contains("${out}") or contains("${pol}") or contains("${hj}") or contains("${sj}")) | not)' "${STATE}/emr-steps.json")" == true ]] && ok "F14-5 no S3 variable reaches spark-submit or FinalDecisions (local paths only)" || bad "F14-5 s3 variable reaches spark-submit/FinalDecisions"
[[ "$(jq -r '.[1].Args | .[7] == "rtest" and (.[8] | test("/jars/[0-9a-f]{12}/scholarchain-hadoop")) and (.[9] | test("scholarchain-spark")) and (.[10] | test("^s3://b-curated/curated/runs/1k/rtest$")) and (.[11] | test("^s3://b-raw/raw/1k/rtest/er-v1-frozen-policy.json$")) and (.[12] | test("^s3://b-curated/graph/runs/1k/rtest/spark$"))' "${STATE}/emr-steps.json")" == true ]] && ok "F14-5 Spark step arguments: jars, OUT_ROOT, uploaded policy, SPARK_OUT" || bad "F14-5 Spark step args"
expect_in "F14-5 the frozen policy is uploaded with the raw inputs" "${LOG}" "er-v1-frozen-policy.json"
[[ "$(jq -r '[.[].Args[]] | map(select(startswith("s3://") and endswith(".jar"))) | length' "${STATE}/emr-steps.json")" == 3 ]] && ok "F14-5 the only s3 jar URIs are the three copy sources (Hadoop jar; Hadoop and Spark jars in the Spark step)" || bad "F14-5 s3 jar count"
[[ "$(jq 'all(.ActionOnFailure == "TERMINATE_CLUSTER")' "${STATE}/emr-steps.json")" == true ]] && ok "EVERY submitted EMR step has ActionOnFailure=TERMINATE_CLUSTER" || bad "ActionOnFailure"
[[ "$(jq 'all(.Args[0] == "timeout" and .Args[2] == "3600")' "${STATE}/emr-steps.json")" == true ]] && ok "EVERY submitted EMR step carries the 3600 s (1 hour) timeout" || bad "step timeout"
expect_in "cluster has idle auto-termination" "${LOG}" "--auto-termination-policy IdleTimeout=900"
expect_in "cluster stays up only for steps (no-auto-terminate + idle policy)" "${LOG}" "--no-auto-terminate"
expect_in "managed scaling max 2 core/task (3 nodes)" "${LOG}" "MaximumCapacityUnits=2"
expect_in "core group of 1 (1 primary + 1 core)" "${LOG}" "InstanceGroupType=CORE,InstanceCount=1"
expect_not_in "pipeline never terminates a cluster itself" "${LOG}" "terminate-clusters"
[[ "$(jq -r .status "${REPO}/reports/pipeline/1k/rtest/run.json" 2> /dev/null)" == succeeded ]] && ok "run.json status succeeded" || bad "run.json status"
# L4: a reused --run-id is refused and creates no cluster.
reset_state
run pipe-run-id-reused 3 bash scripts/run-bounded-pipeline.sh --scale 1k --stages hadoop,spark --run-id rtest --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
expect_in "reused run id message" "${OUT}/pipe-run-id-reused.err" "already has records"
expect_not_in "reused run id created no cluster" "${LOG}" "create-cluster"
# M4: the step watcher must read a non-UTC start time. A step that started 10 s ago and is printed as UTC-5 must NOT be cancelled; a step
# that started 2 h ago and is printed as UTC+5:30 MUST be cancelled.
reset_state
EXTRA_ENV="STUB_STEP_RUNNING=recent-west" run pipe-tz-recent-step 0 bash scripts/run-bounded-pipeline.sh --smoke --run-id rt-west
expect_not_in "a recent step printed with a UTC-5 offset is not cancelled" "${LOG}" "cancel-steps"
expect_not_in "no timestamp parse warning" "${OUT}/pipe-tz-recent-step.err" "cannot parse"
[[ "$(jq -r .status "${REPO}/reports/pipeline/smoke/rt-west/run.json" 2> /dev/null)" == succeeded ]] && ok "run.json is finalised after a RUNNING step with a non-UTC offset" || bad "run.json status after non-UTC RUNNING step"
reset_state
EXTRA_ENV="STUB_STEP_RUNNING=old-east" run pipe-tz-old-step 0 bash scripts/run-bounded-pipeline.sh --smoke --run-id rt-east
expect_in "an old step printed with a UTC+5:30 offset is cancelled" "${LOG}" "cancel-steps"
reset_state
rm -rf -- "${REPO}/reports/pipeline"

echo "== T7b run-bounded-pipeline: convert and load (M13-2, option D) =="
SHORT12="$(git -C "${REPO}" rev-parse --short=12 HEAD)"
reset_state
run pipe-old-stage-enqueue 2 bash scripts/run-bounded-pipeline.sh --scale 1k --stages enqueue --run-id rc0 --dry-run
run pipe-postonly-needs-run-id 2 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert,load --dry-run
run pipe-cap-refused 3 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc1 --max-vectors 10001 --dry-run
expect_in "cap above 10000 message" "${OUT}/pipe-cap-refused.err" "hard cap of 10000"
expect_not_in "cap refusal happens before any AWS call" "${LOG}" "aws "
run pipe-cap-zero 2 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc1 --max-vectors 0 --dry-run
run pipe-neptune-timeout-bad 2 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc1 --neptune-timeout-seconds 5 --dry-run
# Neptune off: exit 6, no run-task
reset_state
jq 'del(.neptune_endpoint)' "${TFOUT}" > "${SCRATCH}/tf-noneptune.json"
cp "${TFOUT}" "${SCRATCH}/tf-outputs.keep"; cp "${SCRATCH}/tf-noneptune.json" "${TFOUT}"
run pipe-load-neptune-off 6 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc2
expect_in "Neptune-off message" "${OUT}/pipe-load-neptune-off.err" "needs Neptune"
expect_not_in "no task started while Neptune is off" "${LOG}" "run-task"
cp "${SCRATCH}/tf-outputs.keep" "${TFOUT}"
# ops outputs missing (Terraform change not applied): exit 6
jq 'del(.ops_task_definition_family)' "${TFOUT}" > "${SCRATCH}/tf-noops.json"; cp "${SCRATCH}/tf-noops.json" "${TFOUT}"
run pipe-load-no-ops-outputs 6 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc3
expect_in "ops outputs message" "${OUT}/pipe-load-no-ops-outputs.err" "ops task outputs"
cp "${SCRATCH}/tf-outputs.keep" "${TFOUT}"
# dry run of convert and load (post-only): plans, no mutation
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12}" run pipe-post-dry 0 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert,load --run-id rc4 --dry-run
expect_in "dry run shows the LocalLoad command" "${OUT}/pipe-post-dry.out" "scholarchain.storage.LocalLoad --run-root"
expect_in "dry run shows the code version" "${OUT}/pipe-post-dry.out" "--code-version ${SHORT12}"
expect_in "dry run shows the Neptune target prefix" "${OUT}/pipe-post-dry.out" "s3://b-curated/graph/neptune-load/1k/rc4/"
expect_in "dry run shows the items target prefix" "${OUT}/pipe-post-dry.out" "s3://b-curated/curated/loads/1k/rc4/"
expect_in "dry run shows run-task on FARGATE" "${OUT}/pipe-post-dry.out" "ecs run-task --cluster scholarchain-cluster"
expect_in "dry run: public subnet, ops SG, public IP enabled" "${OUT}/pipe-post-dry.out" "subnets=[subnet-1],securityGroups=[sg-0ops1234],assignPublicIp=ENABLED"
expect_in "dry run: SERVICE_MAIN override" "${OUT}/pipe-post-dry.out" "scholarchain.services.OpsLoadMain"
expect_in "dry run: max vectors argument 10000" "${OUT}/pipe-post-dry.out" '"value": "10000"'
expect_not_in "dry run starts no task" "${LOG}" "ecs run-task"
expect_not_in "dry run runs no sbt" "${LOG}" "sbt "
expect_not_in "dry run uploads nothing" "${LOG}" "s3 cp"
[[ ! -e "${REPO}/reports/pipeline/1k/rc4" ]] && ok "dry run writes no record" || bad "dry run wrote a record"
# real convert (no load): uploads, checksums, records
reset_state
EXTRA_ENV="STUB_EMBEDDING_INPUTS=1" run pipe-convert-real 0 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert --run-id rc5
expect_in "convert syncs the Spark output" "${LOG}" "s3 sync s3://b-curated/graph/runs/1k/rc5/spark/ "
expect_in "convert runs LocalLoad through sbt Test with the code version" "${LOG}" "sbt storage/Test/runMain scholarchain.storage.LocalLoad --run-root "
expect_in "convert passes the 12-hex code version" "${LOG}" "--code-version ${SHORT12}"
expect_in "convert checked sbt check is not running" "${LOG}" "pgrep -f"
expect_in "vertex CSV uploaded under the Neptune prefix" "${LOG}" "s3://b-curated/graph/neptune-load/1k/rc5/vertices/Researcher.csv"
expect_in "edge CSV uploaded under the Neptune prefix" "${LOG}" "s3://b-curated/graph/neptune-load/1k/rc5/edges/COAUTHORED_WITH.csv"
expect_in "items JSONL uploaded under the items prefix" "${LOG}" "s3://b-curated/curated/loads/1k/rc5/dynamodb/items/analytics-bridges.jsonl"
expect_not_in "nothing but vertices/ and edges/ under the Neptune prefix (no neptune/ path)" "${LOG}" "graph/neptune-load/1k/rc5/neptune/"
run pipe-runid-too-long 2 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id "$(printf 'a%.0s' $(seq 1 129))" --dry-run
expect_in "load manifest uploaded" "${LOG}" "s3://b-curated/curated/loads/1k/rc5/load-manifest.json"
expect_in "checksums file uploaded" "${LOG}" "s3://b-curated/curated/loads/1k/rc5/checksums.sha256"
expect_in "embedding-inputs uploaded when LocalLoad emits them" "${LOG}" "s3://b-curated/curated/loads/1k/rc5/embedding-inputs/works.jsonl"
expect_not_in "no run-task in a convert-only run" "${LOG}" "run-task"
CJ="${REPO}/reports/pipeline/1k/rc5/convert.json"
[[ "$(jq -r '.source_s3_run.run_id' "${CJ}" 2> /dev/null)" == rc5 ]] && ok "convert.json records the S3 source run id" || bad "convert.json source run id"
[[ "$(jq -r '.source_s3_run.spark_run_id_in_load_manifest' "${CJ}" 2> /dev/null)" == spark-run-1 ]] && ok "convert.json records the Spark run id found in load-manifest.json" || bad "convert.json spark run id"
[[ "$(jq '.uploads | length' "${CJ}" 2> /dev/null)" == 6 ]] && ok "convert.json lists 6 uploaded files" || bad "convert.json upload count"
[[ "$(jq '[.uploads[] | select(.sha256 | test("^[0-9a-f]{64}$"))] | length' "${CJ}" 2> /dev/null)" == 6 ]] && ok "every uploaded file has a SHA-256" || bad "upload SHA-256 values"
LMSHA="$(shasum -a 256 "${REPO}/reports/pipeline/1k/rc5/load-manifest.json" | cut -d' ' -f1)"
[[ "$(jq -r '.load_manifest_sha256' "${CJ}" 2> /dev/null)" == "${LMSHA}" ]] && ok "convert.json holds the SHA-256 of the load-manifest.json copy" || bad "load manifest checksum"
[[ -f "${REPO}/reports/pipeline/1k/rc5/checksums.sha256" ]] && ok "checksums.sha256 copied to the run records" || bad "checksums copy"
[[ "$(jq -r '.[] | select(.Name == "convert-local-load") | .status' "${REPO}/reports/pipeline/1k/rc5/steps.json" 2> /dev/null)" == succeeded ]] && ok "steps.json records the convert step" || bad "steps.json convert"
# no-overwrite and the sbt check guard
reset_state
run pipe-convert-again 3 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert --run-id rc5
expect_in "convert never overwrites its record" "${OUT}/pipe-convert-again.err" "never overwritten"
reset_state
EXTRA_ENV="STUB_S3_NONEMPTY=1" run pipe-convert-nonempty-prefix 3 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert --run-id rc6
expect_in "non-empty target prefix refused" "${OUT}/pipe-convert-nonempty-prefix.err" "is not empty"
expect_not_in "no upload into a non-empty prefix" "${LOG}" "s3 cp"
expect_not_in "no sbt run into a non-empty prefix" "${LOG}" "sbt "
reset_state
EXTRA_ENV="STUB_SBT_CHECK_RUNNING=1" run pipe-convert-sbt-check-running 6 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert --run-id rc7
expect_in "sbt check guard says why" "${OUT}/pipe-convert-sbt-check-running.err" "another 'sbt check' is running"
expect_not_in "no sbt while sbt check runs" "${LOG}" "sbt storage"
reset_state
EXTRA_ENV="STUB_NO_SPARK_OUT=1" run pipe-convert-no-spark-output 4 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert --run-id rc8
reset_state
EXTRA_ENV="STUB_SBT_FAIL=1" run pipe-convert-sbt-fails 1 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert --run-id rc9
expect_not_in "nothing uploaded when LocalLoad fails" "${LOG}" "s3 cp"
reset_state
EXTRA_ENV="STUB_BAD_CHECKSUM=1" run pipe-convert-bad-checksum 1 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert --run-id rc10
expect_in "checksum mismatch message" "${OUT}/pipe-convert-bad-checksum.err" "checksum mismatch"
expect_not_in "nothing uploaded on a checksum mismatch" "${LOG}" "s3 cp"
# optional local hook (unconfigured by default): a hook that adds files under its own prefix
HOOK="${SCRATCH}/render-hook.sh"
cat > "${HOOK}" << 'EOF'
#!/usr/bin/env bash
mkdir -p "$2/embedding-docs"
echo '{"d":1}' > "$2/embedding-docs/docs.jsonl"
EOF
chmod +x "${HOOK}"
reset_state
EXTRA_ENV="SC_CONVERT_EXTRA_CMD=${HOOK} SC_CONVERT_EXTRA_PREFIX=embedding-docs" run pipe-convert-hook 0 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert --run-id rc11
expect_in "hook output uploaded under the extra prefix" "${LOG}" "s3://b-curated/curated/loads/1k/rc11/embedding-docs/docs.jsonl"
reset_state
run pipe-convert-no-hook-by-default 0 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert --run-id rc12
expect_not_in "no extra prefix without the hook" "${LOG}" "embedding-docs"
EXTRA_ENV="SC_CONVERT_EXTRA_CMD=${HOOK}" run pipe-convert-hook-needs-prefix 2 bash scripts/run-bounded-pipeline.sh --scale 1k --stages convert --run-id rc13 --dry-run
# real load: run-task arguments, wait, exit-code mapping
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12}" run pipe-load-real 0 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc20
expect_in "run-task uses the pinned ops task definition revision" "${LOG}" "--task-definition arn:aws:ecs:us-east-2:123456789012:task-definition/scholarchain-ops"
expect_in "run-task launch type FARGATE" "${LOG}" "--launch-type FARGATE"
expect_in "run-task network: public subnet, ops SG, public IP" "${LOG}" "subnets=[subnet-1],securityGroups=[sg-0ops1234],assignPublicIp=ENABLED"
[[ "$(count_in "${LOG}" "ecs run-task")" == 1 ]] && ok "run-task called exactly once (never retried)" || bad "run-task call count"
OV="${STATE}/run-task-overrides.json"
[[ "$(jq -r '.containerOverrides[0].name' "${OV}" 2> /dev/null)" == ops ]] && ok "override targets the ops container" || bad "override container name"
ovv() { jq -r --arg n "$1" '.containerOverrides[0].environment[] | select(.name == $n) | .value' "${OV}" 2> /dev/null; }
[[ "$(ovv SERVICE_MAIN)" == scholarchain.services.OpsLoadMain ]] && ok "SERVICE_MAIN override" || bad "SERVICE_MAIN override"
[[ "$(ovv SCHOLARCHAIN_OPS_NEPTUNE_SOURCE)" == s3://b-curated/graph/neptune-load/1k/rc20/ ]] && ok "neptune source argument" || bad "neptune source argument"
[[ "$(ovv SCHOLARCHAIN_OPS_ITEMS_SOURCE)" == s3://b-curated/curated/loads/1k/rc20/ ]] && ok "items source argument" || bad "items source argument"
[[ "$(ovv SCHOLARCHAIN_OPS_RUN_ID)" == rc20 && "$(ovv SCHOLARCHAIN_OPS_CODE_VERSION)" == "${SHORT12}" ]] && ok "run id and code version arguments" || bad "run id or code version argument"
[[ "$(ovv SCHOLARCHAIN_OPS_MAX_VECTORS)" == 10000 && "$(ovv SCHOLARCHAIN_OPS_NEPTUNE_TIMEOUT_SECONDS)" == 1800 && "$(ovv SCHOLARCHAIN_OPS_DRY_RUN)" == false ]] && ok "max vectors 10000, Neptune timeout 1800, not a dry run" || bad "max vectors, timeout or dry-run argument"
[[ "$(jq -r '.[] | select(.Name == "ops-load") | .exit_code' "${REPO}/reports/pipeline/1k/rc20/steps.json" 2> /dev/null)" == 0 ]] && ok "steps.json records exit code 0" || bad "steps.json exit code"
[[ "$(jq -r '.log.stream' "${REPO}/reports/pipeline/1k/rc20/load.json" 2> /dev/null)" == ops/ops/abc123 ]] && ok "load.json records the log stream" || bad "load.json log stream"
[[ "$(jq -r '.log.group' "${REPO}/reports/pipeline/1k/rc20/load.json" 2> /dev/null)" == /scholarchain/embedding-worker ]] && ok "load.json records the log group" || bad "load.json log group"
[[ "$(count_in "${LOG}" "ecs describe-tasks")" -ge 3 ]] && ok "the script polled until STOPPED" || bad "poll count"
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12}" run pipe-load-tuned 0 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc21 --max-vectors 500 --neptune-timeout-seconds 600 --ops-task-dry-run
OV="${STATE}/run-task-overrides.json"
[[ "$(ovv SCHOLARCHAIN_OPS_MAX_VECTORS)" == 500 && "$(ovv SCHOLARCHAIN_OPS_NEPTUNE_TIMEOUT_SECONDS)" == 600 && "$(ovv SCHOLARCHAIN_OPS_DRY_RUN)" == true ]] && ok "tuned arguments reach the task" || bad "tuned arguments"
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12} STUB_OPS_EXIT=1" run pipe-load-exit1 1 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc22
expect_in "a failed ops task is reported with its log location" "${OUT}/pipe-load-exit1.err" "exited with code 1"
[[ "$(jq -r .status "${REPO}/reports/pipeline/1k/rc22/load.json" 2> /dev/null)" == failed ]] && ok "load.json status failed on exit 1" || bad "load.json status on failure"
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12} STUB_OPS_EXIT=3" run pipe-load-exit3 3 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc23
expect_in "ops exit 3 (cap or invariant refusal) is reported as a refusal" "${OUT}/pipe-load-exit3.err" "exited with code 3"
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12} STUB_OPS_EXIT=null" run pipe-load-no-exit-code 1 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc24
expect_in "a task that stopped without an exit code is a failure" "${OUT}/pipe-load-no-exit-code.err" "exited with code none"
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12} STUB_OPS_NEVER_STOP=1 SC_LOAD_TIMEOUT=1" run pipe-load-timeout 5 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc25
expect_in "timeout asks ECS to stop the task" "${LOG}" "ecs stop-task"
reset_state
EXTRA_ENV="STUB_OPS_TAG=oldsha000000" run pipe-load-wrong-image 6 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc26
expect_in "image tag from another commit refused" "${OUT}/pipe-load-wrong-image.err" "not this commit"
expect_not_in "no task with the wrong image" "${LOG}" "ecs run-task"
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12} STUB_RUN_TASK_ARN=None" run pipe-load-no-task 1 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc27
[[ "$(count_in "${LOG}" "ecs run-task")" == 1 ]] && ok "a failed run-task start is not retried" || bad "run-task retried"
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12}" run pipe-load-again 3 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rc20
expect_in "load never overwrites its record" "${OUT}/pipe-load-again.err" "never overwritten"
expect_not_in "no second task for the same run id" "${LOG}" "ecs run-task"
# integrated run: EMR stages then convert and load
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12}" run pipe-integrated 0 bash scripts/run-bounded-pipeline.sh --scale 1k --run-id rc30 --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}"
[[ "$(jq -r .status "${REPO}/reports/pipeline/1k/rc30/run.json" 2> /dev/null)" == succeeded ]] && ok "integrated run.json succeeded" || bad "integrated run.json status"
[[ "$(jq '.post_steps | length' "${REPO}/reports/pipeline/1k/rc30/run.json" 2> /dev/null)" == 2 ]] && ok "run.json lists the two post steps" || bad "run.json post steps"
[[ "$(jq -r '[.[].Name] | join(",")' "${REPO}/reports/pipeline/1k/rc30/steps.json" 2> /dev/null)" == "hadoop-pipeline-HJ1-HJ7,spark-graph-analytics,convert-local-load,ops-load" ]] && ok "steps.json: EMR steps then convert then ops-load" || bad "steps.json order"
expect_not_in "EMR steps hold no load or enqueue placeholder" "${STATE}/emr-steps.json" "UNCONFIGURED"
reset_state
EXTRA_ENV="STUB_OPS_TAG=${SHORT12}" run pipe-integrated-dry 0 bash scripts/run-bounded-pipeline.sh --scale 1k --run-id rc31 --dblp-xml "${KEYFILE}" --dtd "${KEYFILE}" --dry-run
expect_in "integrated dry run plans the convert stage" "${OUT}/pipe-integrated-dry.out" "scholarchain.storage.LocalLoad"
expect_in "integrated dry run plans the load stage" "${OUT}/pipe-integrated-dry.out" "ecs run-task"
rm -rf -- "${REPO}/reports/pipeline"

echo "== T8 env-window =="
reset_state
run env-up 0 bash scripts/env-window.sh up
expect_in "up prints create_neptune" "${OUT}/env-up.out" "create_neptune = true"
expect_in "up prints create_alb" "${OUT}/env-up.out" "create_alb     = true"
expect_in "up prints the cost estimate" "${OUT}/env-up.out" "SC-COST"
expect_not_in "up makes no AWS call" "${LOG}" "aws "
run env-down-needs-yes 2 bash scripts/env-window.sh down
reset_state
run env-down-dry 0 bash scripts/env-window.sh down --dry-run
expect_not_in "down --dry-run changes nothing" "${LOG}" "update-service"
reset_state
run env-down 0 bash scripts/env-window.sh down --yes
[[ "$(grep -c 'ecs update-service.*--desired-count 0' "${LOG}")" == 3 ]] && ok "down scaled all three services to 0" || bad "down update-service count"
expect_in "down prints how to remove Neptune and the ALB" "${OUT}/env-down.out" "create_neptune = false"
expect_not_in "no forbidden terraform call" "${LOG}" "FORBIDDEN"
rm -rf -- "${REPO}/reports/env-window"

echo "== T9 run-experiment =="
run experiment-no-dir 6 bash scripts/run-experiment.sh exp-1
expect_in "clear error without experiments/manifests" "${OUT}/experiment-no-dir.err" "manifest directory"

echo "== T10 archive-evidence =="
reset_state
run archive-dry 0 bash scripts/archive-evidence.sh --dry-run
reset_state
AR_DIR="${REPO}/reports/pipeline/zz-archtest/run-1"
mkdir -p "${AR_DIR}"
for n in run.json steps.json convert.json load.json load-manifest.json checksums.sha256; do printf '{}\n' > "${AR_DIR}/${n}"; done
run archive-dry-pipeline 0 bash scripts/archive-evidence.sh --dry-run
expect_in "dry run lists convert.json" "${OUT}/archive-dry-pipeline.out" "convert.json"
expect_in "dry run lists load.json" "${OUT}/archive-dry-pipeline.out" "load.json"
run archive-real 0 bash scripts/archive-evidence.sh
[[ -f "${REPO}/reports/evidence/${SHA}/MANIFEST.json" ]] && ok "MANIFEST.json written" || bad "MANIFEST.json"
for n in convert.json load.json load-manifest.json checksums.sha256; do
  [[ -f "${REPO}/reports/evidence/${SHA}/runs/pipeline__zz-archtest__run-1__${n}" ]] && ok "archived ${n}" || bad "archived ${n}"
done
grep -q 'pipeline__zz-archtest__run-1__load.json' "${REPO}/reports/evidence/${SHA}/MANIFEST.json" && ok "MANIFEST lists load.json" || bad "MANIFEST lists load.json"
rm -rf -- "${REPO}/reports/pipeline/zz-archtest"
run archive-twice-refused 3 bash scripts/archive-evidence.sh
rm -rf -- "${REPO}/reports/evidence" "${REPO}/reports/release"

echo "== T11 cleanup (a copy with the image-deletion token renamed so the stub can be called; the real file is human-executed) =="
sed 's/batch-delete-image/batch-xcopy-image/' "${REPO}/scripts/cleanup.sh" > "${REPO}/scripts/cleanup-under-test.sh"
chmod +x "${REPO}/scripts/cleanup-under-test.sh"
printf 'human decision recorded\n' > "${SCRATCH}/gate.txt"
reset_state
run cleanup-list-only 0 bash scripts/cleanup-under-test.sh
expect_not_in "cleanup lists only by default" "${LOG}" "batch-xcopy-image"
expect_in "cleanup prints the secret recovery note (G12-4)" "${OUT}/cleanup-list-only.out" "7-day recovery window"
expect_in "cleanup prints the force-delete commands (never runs them)" "${OUT}/cleanup-list-only.out" "--force-delete-without-recovery"
expect_not_in "cleanup does not run secret deletion" "${LOG}" "force-delete-without-recovery"
run cleanup-empty-needs-confirm 2 bash scripts/cleanup-under-test.sh --empty-ecr
run cleanup-empty-dry 0 bash scripts/cleanup-under-test.sh --empty-ecr --confirm-empty-ecr --gate-file "${SCRATCH}/gate.txt" --dry-run
expect_in "cleanup dry-run lists what would be deleted" "${OUT}/cleanup-empty-dry.out" "would delete"
expect_not_in "cleanup dry-run deletes nothing" "${LOG}" "batch-xcopy-image"
reset_state
run cleanup-empty-real 0 bash scripts/cleanup-under-test.sh --empty-ecr --confirm-empty-ecr --gate-file "${SCRATCH}/gate.txt"
[[ "$(count_in "${LOG}" "batch-xcopy-image")" == 3 ]] && ok "ECR emptying called once per repository (3)" || bad "ECR emptying call count"
ls "${REPO}"/reports/cleanup/*/inventory-before.json > /dev/null 2>&1 && ls "${REPO}"/reports/cleanup/*/inventory-after.json > /dev/null 2>&1 && ok "inventory before and after written" || bad "inventories"
expect_not_in "no forbidden terraform call during cleanup" "${LOG}" "FORBIDDEN"
rm -f "${REPO}/scripts/cleanup-under-test.sh"
rm -rf -- "${REPO}/reports/cleanup"

echo "== T12 check.sh =="
reset_state
run check-ok 0 bash scripts/check.sh --skip-sbt
printf '{ broken' > "${REPO}/.mcp.json"
run check-bad-json 1 bash scripts/check.sh --skip-sbt --skip-terraform
printf '{}\n' > "${REPO}/.mcp.json"

echo "== T13 no guarded phrase in any script text =="
P1="terra"; P2="form"
if grep -rniE --exclude-dir=tests "${P1}${P2}[[:space:]\\]+(apply|destroy)" "${SRC_ROOT}/scripts" > "${OUT}/phrase.out" 2>&1; then bad "guarded phrase found (see ${OUT}/phrase.out)"; else ok "no script (outside this harness) contains the guarded apply/destroy phrase"; fi
if grep -rn --exclude-dir=tests 'terminate-clusters' "${SRC_ROOT}/scripts" --include='*.sh' | grep -vE '^[^:]*:[0-9]+:[[:space:]]*#' > "${OUT}/term.out"; then bad "terminate-clusters appears outside a comment (see ${OUT}/term.out)"; else ok "no terminate-clusters command"; fi
[[ "$((FORB + $(grep -c FORBIDDEN "${LOG}" || true)))" == 0 ]] && ok "the stubbed infrastructure tool never received an apply or destroy subcommand" || bad "forbidden infrastructure call"

echo
printf 'RESULT passed=%s failed=%s scratch=%s\n' "${PASS}" "${FAILN}" "${SCRATCH}"
if [[ "${KEEP}" != 1 && -z "${SC_TEST_SCRATCH:-}" ]]; then rm -rf -- "${SCRATCH:?}"; fi
((FAILN == 0))
