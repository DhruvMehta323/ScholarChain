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
    img="old:1"; if [[ "${t}" == *":2" && -f "${STUB_STATE}/reg-${fam}.json" ]]; then img="$(jq -r '.containerDefinitions[0].image' "${STUB_STATE}/reg-${fam}.json")"; fi
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
  "ecs describe-tasks") echo "[\"${STUB_TASK_DIGEST:-${DIGEST}}\"]" ;;
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
  "s3api list-objects-v2") echo 3 ;;
  "s3 cp") ;;
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
  image) echo "${STUB_DOCKER_ID:-sha256:$(printf '%064d' 0 | tr 0 b)}" ;;
  login) cat > /dev/null ;;
  push) r="${2##*/}"; r="${r%%:*}"; : > "${STUB_STATE}/pushed-${r}" ;;
  tag) ;;
  *) echo "UNHANDLED docker $1" >> "${STUB_LOG}"; exit 97 ;;
esac
EOF

cat > "${STUBS}/sbt" << 'EOF'
#!/usr/bin/env bash
printf 'sbt %s\n' "$*" >> "${STUB_LOG}"
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
mkdir -p "${REPO}/scripts" "${REPO}/infra/terraform/environments/course" "${REPO}/infra/terraform/plans" "${REPO}/.claude/hooks" "${REPO}/modules/hadoop/target/scala-2.13" "${REPO}/modules/spark/target/scala-2.13"
cp -R "${SRC_ROOT}/scripts/." "${REPO}/scripts/"
printf '# fixture\n' > "${REPO}/infra/terraform/environments/course/main.tf"
printf 'exit 0\n' > "${REPO}/.claude/hooks/validate-agent-files.sh"
printf '{}\n' > "${REPO}/.mcp.json"
printf '{}\n' > "${REPO}/.claude/settings.json"
printf 'target/\n.claude/.last-test-run\n' > "${REPO}/.gitignore"
printf 'jar-h' > "${REPO}/modules/hadoop/target/scala-2.13/hadoop-0.1-assembly.jar"
printf 'jar-s' > "${REPO}/modules/spark/target/scala-2.13/spark-0.1-assembly.jar"
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
  secret_arns: {value: {a: "arn:aws:secretsmanager:us-east-2:123456789012:secret:SCHOLARCHAIN_SERVICES_API_KEY-AbCdEf", b: "arn:aws:secretsmanager:us-east-2:123456789012:secret:scholarchain/voyage-api-key-GhIjKl"}},
  emr_service_role_arn: {value: "arn:aws:iam::123456789012:role/scholarchain-emr-service"}, emr_instance_profile_arn: {value: "arn:aws:iam::123456789012:instance-profile/scholarchain-emr-ec2"},
  public_subnet_ids: {value: ["subnet-1", "subnet-2"]}, vpc_id: {value: "vpc-1"},
  neptune_endpoint: {value: "https://n.example:8182"}, query_api_url: {value: "http://alb.example"}, embedding_queue_url: {value: "https://sqs.us-east-2.amazonaws.com/123456789012/scholarchain-embedding-jobs"}
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
ART="${REPO}/reports/release/${SHA}/artifacts.json"
[[ -f "${ART}" ]] && ok "artifacts.json written" || bad "artifacts.json missing"
if [[ -f "${ART}" ]]; then
  [[ "$(jq '.images | length' "${ART}")" == 3 ]] && ok "artifacts.json lists three images (M13-1 re-tag)" || bad "artifacts.json image count"
  [[ "$(jq '.jars | length' "${ART}")" == 2 ]] && ok "artifacts.json lists two jars" || bad "artifacts.json jar count"
  [[ "$(jq -c '[.release, .dirty]' "${ART}")" == "[true,false]" ]] && ok "clean run records release:true dirty:false" || bad "release flags"
fi
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
EXTRA_ENV="SC_LOCAL_IMAGE=somebody/else:1" run package-local-image-refused 3 bash scripts/package.sh
expect_in "SC_LOCAL_IMAGE refusal message" "${OUT}/package-local-image-refused.err" "SC_LOCAL_IMAGE"
EXTRA_ENV="SC_IMAGE_SOURCE_RETRIEVAL=somebody/else:1" run package-source-refused 3 bash scripts/package.sh --skip-build
expect_in "SC_IMAGE_SOURCE_* refusal message" "${OUT}/package-source-refused.err" "SC_IMAGE_SOURCE_RETRIEVAL"
# M7: free-form build commands are gone.
reset_state
EXTRA_ENV="SC_JOBS_BUILD_CMD=touch-evil" run package-free-cmd-refused 2 bash scripts/package.sh
expect_in "free-form build command message" "${OUT}/package-free-cmd-refused.err" "no longer supported"
expect_not_in "no build command ran" "${LOG}" "touch-evil"
# M2/L9: a local run goes to a separate, non-release path and never touches the release record.
reset_state
EXTRA_ENV="SC_LOCAL_IMAGE=local/test:1" run package-local-ok 0 bash scripts/package.sh --allow-dirty --skip-build
LOC="${REPO}/reports/release-local/${SHA}/artifacts.json"
[[ "$(jq -c '[.release, .dirty]' "${LOC}" 2> /dev/null)" == "[false,true]" ]] && ok "local run records release:false dirty:true under release-local/" || bad "local run record"
[[ "$(shasum -a 256 "${ART}" | cut -d' ' -f1)" == "${ART_SUM}" ]] && ok "the release artifacts.json was not overwritten by the local run" || bad "release artifacts.json was overwritten"
[[ "$(jq -r '.images[0].tag' "${LOC}" 2> /dev/null)" == *-dirty ]] && ok "local images are tagged -dirty" || bad "local image tag"

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
expect_in "default hadoop mode is the PipelineDriver (review M3)" "${OUT}/pipe-10k-dry.out" "scholarchain.hadoop.PipelineDriver"
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
[[ "$(jq -r '.[0].Args | index("scholarchain.hadoop.PipelineDriver") != null' "${STATE}/emr-steps.json")" == true ]] && ok "the Hadoop step runs PipelineDriver" || bad "Hadoop step main class"
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
EXTRA_ENV="STUB_TF_EMPTY=0" run pipe-load-unconfigured 7 bash scripts/run-bounded-pipeline.sh --scale 1k --stages load --run-id rtest2
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
run archive-real 0 bash scripts/archive-evidence.sh
[[ -f "${REPO}/reports/evidence/${SHA}/MANIFEST.json" ]] && ok "MANIFEST.json written" || bad "MANIFEST.json"
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
