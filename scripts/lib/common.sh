#!/usr/bin/env bash
# Shared helpers for ScholarChain operational scripts. Source this file; do not execute it.
# Owner: infra-engineer. Contains no deletion commands and no state-changing infrastructure commands.

# Greppable step markers: "SC-STEP <n>/<total> <name>", "SC-INFO <text>", "SC-WARN <text>", "SC-ERROR <text>", "SC-OK <text>".
sc_step() {
  printf '==> STEP %s\n' "$3"
  printf 'SC-STEP %s/%s %s\n' "$1" "$2" "$3"
}
# Step marker for scripts that do not number their steps: "==> STEP <name>".
sc_stage() { printf '==> STEP %s\n' "$1"; }
sc_info() { printf 'SC-INFO %s\n' "$*"; }
sc_warn() { printf 'SC-WARN %s\n' "$*" >&2; }
sc_ok() { printf 'SC-OK %s\n' "$*"; }
# SC_LAST_ERROR keeps the last sc_die message so an EXIT trap (deploy.sh) can put it in a failure record.
SC_LAST_ERROR=""
sc_die() {
  # shellcheck disable=SC2034 # read by deploy.sh (EXIT trap)
  SC_LAST_ERROR="$*"
  printf 'SC-ERROR %s\n' "$*" >&2
  exit 1
}

sc_require_cmd() {
  local c
  for c in "$@"; do
    command -v "$c" > /dev/null 2>&1 || sc_die "required command not found: ${c}"
  done
}

# Repository root (works from any working directory inside the repository).
sc_repo_root() {
  git rev-parse --show-toplevel
}

# SHA-256 of a file, portable between macOS and Linux.
sc_sha256() {
  if command -v sha256sum > /dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# Environment names are directories under infra/terraform/environments.
sc_env_dir() {
  local root="$1" env="$2"
  [[ "${env}" =~ ^[a-z][a-z0-9-]*$ ]] || sc_die "invalid environment name: ${env}"
  [[ -d "${root}/infra/terraform/environments/${env}" ]] || sc_die "unknown environment: ${env}"
  printf '%s\n' "${root}/infra/terraform/environments/${env}"
}

# SHA-256 of standard input, portable between macOS and Linux.
sc_sha256_stdin() {
  if command -v sha256sum > /dev/null 2>&1; then
    sha256sum | cut -d' ' -f1
  else
    shasum -a 256 | cut -d' ' -f1
  fi
}

# ---------------------------------------------------------------------------------------------------------------------
# Terraform variable resolution without eval (used by plan.sh to compare the account and Region before planning).
# Terraform precedence, lowest to highest, when no -var/-var-file is given (plan.sh passes none):
#   variable default < TF_VAR_<name> < terraform.tfvars < terraform.tfvars.json < *.auto.tfvars < *.auto.tfvars.json
# Only the simple form  name = "value"  (one assignment per file) is understood. Anything else that mentions the name is
# treated as ambiguous and the caller must fail closed.
# ---------------------------------------------------------------------------------------------------------------------

# sc_tfvars_hcl_value <file> <name>: prints the value. Return 0 found, 1 not present, 2 ambiguous or unparseable.
sc_tfvars_hcl_value() {
  local file="$1" name="$2" line count=0 value="" re
  [[ -f "${file}" ]] || return 1
  re='^[[:space:]]*'"${name}"'[[:space:]]*=[[:space:]]*"([^"\\]*)"[[:space:]]*(#.*)?$'
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ "${line}" =~ ^[[:space:]]*${name}[[:space:]]*= ]]; then
      count=$((count + 1))
      [[ "${line}" =~ ${re} ]] || return 2
      value="${BASH_REMATCH[1]}"
    fi
  done < "${file}"
  ((count == 0)) && return 1
  ((count > 1)) && return 2
  printf '%s\n' "${value}"
}

# sc_tfvars_json_value <file> <name>: same contract, for *.tfvars.json files.
sc_tfvars_json_value() {
  local file="$1" name="$2" v
  [[ -f "${file}" ]] || return 1
  jq -e . "${file}" > /dev/null 2>&1 || return 2
  if ! jq -e --arg n "${name}" 'has($n)' "${file}" > /dev/null 2>&1; then return 1; fi
  v="$(jq -r --arg n "${name}" 'if (.[$n] | type) == "string" then .[$n] else "\u0000NOTSTRING" end' "${file}")" || return 2
  [[ "${v}" == *NOTSTRING* ]] && return 2
  printf '%s\n' "${v}"
}

# sc_tf_default <variables.tf> <name>: prints the default of a string variable when it is a simple quoted literal.
sc_tf_default() {
  local file="$1" name="$2"
  [[ -f "${file}" ]] || return 1
  awk -v n="${name}" '
    $0 ~ "^variable[[:space:]]+\"" n "\"[[:space:]]*\\{" { inblk = 1; next }
    inblk && /^}/ { inblk = 0 }
    inblk && /^[[:space:]]*default[[:space:]]*=[[:space:]]*"[^"]*"[[:space:]]*$/ {
      v = $0; sub(/^[^"]*"/, "", v); sub(/"[[:space:]]*$/, "", v); print v; found = 1; exit
    }
    END { exit found ? 0 : 1 }
  ' "${file}"
}

# sc_resolve_tfvar <env-dir> <name>: prints "<value><TAB><source>". Return 0 resolved, 1 missing, 2 ambiguous.
sc_resolve_tfvar() {
  local dir="$1" name="$2" value="" src="" v rc f envname
  if [[ -n "${TF_CLI_ARGS:-}${TF_CLI_ARGS_plan:-}${TF_CLI_ARGS_init:-}" ]]; then
    return 2 # command-line arguments injected through the environment could override any value
  fi
  if v="$(sc_tf_default "${dir}/variables.tf" "${name}")"; then value="${v}"; src="default in variables.tf"; fi
  envname="TF_VAR_${name}"
  # Terraform treats a set-but-empty TF_VAR_ as the empty string (it overrides the default); that can never be a valid value here.
  if [[ -n "${!envname+x}" && -z "${!envname}" ]]; then return 2; fi
  if [[ -n "${!envname:-}" ]]; then value="${!envname}"; src="environment ${envname}"; fi
  rc=0; v="$(sc_tfvars_hcl_value "${dir}/terraform.tfvars" "${name}")" || rc=$?
  ((rc == 2)) && return 2
  ((rc == 0)) && { value="${v}"; src="terraform.tfvars"; }
  rc=0; v="$(sc_tfvars_json_value "${dir}/terraform.tfvars.json" "${name}")" || rc=$?
  ((rc == 2)) && return 2
  ((rc == 0)) && { value="${v}"; src="terraform.tfvars.json"; }
  while IFS= read -r f; do
    [[ -n "${f}" ]] || continue
    rc=0; v="$(sc_tfvars_hcl_value "${f}" "${name}")" || rc=$?
    ((rc == 2)) && return 2
    ((rc == 0)) && { value="${v}"; src="$(basename "${f}")"; }
  done < <(find "${dir}" -maxdepth 1 -name '*.auto.tfvars' -type f | LC_ALL=C sort)
  while IFS= read -r f; do
    [[ -n "${f}" ]] || continue
    rc=0; v="$(sc_tfvars_json_value "${f}" "${name}")" || rc=$?
    ((rc == 2)) && return 2
    ((rc == 0)) && { value="${v}"; src="$(basename "${f}")"; }
  done < <(find "${dir}" -maxdepth 1 -name '*.auto.tfvars.json' -type f | LC_ALL=C sort)
  [[ -n "${value}" ]] || return 1
  printf '%s\t%s\n' "${value}" "${src}"
}

# sc_effective_region: the Region the caller's AWS configuration selects. Fails closed when unset or contradictory.
sc_effective_region() {
  local a="${AWS_REGION:-}" b="${AWS_DEFAULT_REGION:-}" r=""
  if [[ -n "${a}" && -n "${b}" && "${a}" != "${b}" ]]; then
    return 2
  fi
  r="${a:-${b}}"
  if [[ -z "${r}" ]]; then
    r="$(aws configure get region 2> /dev/null || true)"
  fi
  [[ -n "${r}" ]] || return 1
  printf '%s\n' "${r}"
}

# ---------------------------------------------------------------------------------------------------------------------
# Input hygiene: refuse every Terraform input that this script's parser cannot see or that could change what is planned.
# sc_preflight_tf_inputs <repo-root> <env-dir>: returns 0 or dies with the first problem found. No AWS call.
# ---------------------------------------------------------------------------------------------------------------------
sc_preflight_tf_inputs() {
  local root="$1" dir="$2" f v tfv
  # Environment variables that redirect state, workspace or arguments.
  [[ -z "${TF_WORKSPACE+x}" ]] || sc_die "TF_WORKSPACE is set: refusing (it can select a workspace other than default)"
  [[ -z "${TF_DATA_DIR+x}" ]] || sc_die "TF_DATA_DIR is set: refusing (it can redirect the backend and workspace data)"
  while IFS= read -r v; do
    [[ -n "${v}" ]] && sc_die "${v} is set: refusing (TF_CLI_ARGS* can inject -var or other overrides)"
  done < <(compgen -e | grep -E '^TF_CLI_ARGS' || true)
  # Non-default workspace recorded by a previous 'terraform workspace select'.
  if [[ -e "${dir}/.terraform/environment" ]]; then
    [[ "$(cat "${dir}/.terraform/environment")" == "default" ]] || sc_die "non-default Terraform workspace selected (${dir}/.terraform/environment): refusing"
  fi
  # Variable files other than terraform.tfvars (symlinks included: find lists them without following).
  f="$(find "${dir}" -maxdepth 1 \( -name '*.tfvars.json' -o -name '*.auto.tfvars' -o -name '*.auto.tfvars.json' \) | LC_ALL=C sort | head -n 1)"
  [[ -z "${f}" ]] || sc_die "unsupported variable file ${f}: only terraform.tfvars is accepted (tfvars.json and *.auto.tfvars are refused)"
  tfv="${dir}/terraform.tfvars"
  if [[ -e "${tfv}" || -L "${tfv}" ]]; then
    [[ ! -L "${tfv}" ]] || sc_die "terraform.tfvars is a symlink: refusing"
    [[ -f "${tfv}" ]] || sc_die "terraform.tfvars is not a regular file: refusing"
    if [[ "$(head -c 3 "${tfv}" | od -An -tx1 | tr -d ' \n')" == "efbbbf" ]]; then sc_die "terraform.tfvars starts with a byte-order mark: refusing"; fi
    if grep -Eq '/\*|\*/' "${tfv}"; then sc_die "terraform.tfvars contains a block comment: refusing"; fi
    if grep -Eq '<<' "${tfv}"; then sc_die "terraform.tfvars contains a heredoc: refusing"; fi
  fi
  # Override files and JSON configuration in the environment and in the local modules (Terraform merges them silently).
  f="$(find "${dir}" "${root}/infra/terraform/modules" -name .terraform -prune -o -type f \( -name 'override.tf' -o -name 'override.tf.json' -o -name '*_override.tf' -o -name '*_override.tf.json' -o -name '*.tf.json' \) -print -o -type l \( -name '*.tf' -o -name '*.tf.json' \) -print | LC_ALL=C sort | head -n 1)"
  [[ -z "${f}" ]] || sc_die "refusing override, JSON or symlinked configuration file: ${f}"
  return 0
}

# ---------------------------------------------------------------------------------------------------------------------
# Phase 13 helpers: dry-run, timeouts and retries on cloud calls, Terraform outputs, account and Region guard, git guards.
# None of these runs an infrastructure apply or removal. Callers set SC_DRY_RUN=1 for --dry-run.
# ---------------------------------------------------------------------------------------------------------------------
SC_DRY_RUN="${SC_DRY_RUN:-0}"
export AWS_PAGER=""

# sc_mut <command...>: runs the command, or only prints it under --dry-run. Use for every state-changing call.
sc_mut() {
  if [[ "${SC_DRY_RUN}" == 1 ]]; then
    printf 'SC-DRYRUN %s\n' "$*"
    return 0
  fi
  "$@"
}

# sc_aws <args...>: the AWS CLI with connect and read timeouts. Profile and Region come from the environment only.
sc_aws() {
  aws --cli-connect-timeout "${SC_AWS_CONNECT_TIMEOUT:-15}" --cli-read-timeout "${SC_AWS_READ_TIMEOUT:-60}" "$@"
}

# sc_retry <command...>: up to 3 attempts, prints the successful stdout. ONLY for idempotent reads (describe, list, get).
sc_retry() {
  local n=1 out rc
  while :; do
    rc=0
    out="$("$@")" || rc=$?
    if ((rc == 0)); then
      printf '%s' "${out}"
      return 0
    fi
    ((n >= 3)) && return "${rc}"
    n=$((n + 1))
    sleep "${SC_RETRY_SLEEP:-3}"
  done
}

# sc_timeout <seconds> <command...>: GNU timeout when present (also gtimeout), otherwise a bash watchdog (macOS ships neither). 124 on expiry.
sc_timeout() {
  local secs="$1" pid wd rc=0 flag
  shift
  if command -v timeout > /dev/null 2>&1; then
    timeout --kill-after=30 "${secs}" "$@"
    return $?
  fi
  if command -v gtimeout > /dev/null 2>&1; then
    gtimeout --kill-after=30 "${secs}" "$@"
    return $?
  fi
  flag="$(mktemp)"
  "$@" &
  pid=$!
  (
    sleep "${secs}"
    if kill -0 "${pid}" 2> /dev/null; then
      : > "${flag}.hit"
      kill "${pid}" 2> /dev/null
    fi
  ) &
  wd=$!
  wait "${pid}" || rc=$?
  kill "${wd}" 2> /dev/null || true
  wait "${wd}" 2> /dev/null || true
  if [[ -e "${flag}.hit" ]]; then rc=124; fi
  rm -f -- "${flag:?}" "${flag:?}.hit"
  return "${rc}"
}

# sc_apply_hint <env-dir-relative>: prints the exact HUMAN command (never run by any script).
sc_apply_hint() {
  local envrel="$1"
  printf 'SC-HUMAN-COMMAND review the approved saved plan, then run it yourself in your own terminal (scripts never apply):\n'
  printf '  AWS_PROFILE=<profile> terraform -chdir=%s apply <saved plan file under infra/terraform/plans/>\n' "${envrel}"
}

# sc_load_outputs <repo-root> <env-name>: loads the Terraform outputs (read-only, local state) into TF_OUT. Exit 4 if absent.
sc_load_outputs() {
  local root="$1" env="$2" dir rc=0
  dir="$(sc_env_dir "${root}" "${env}")"
  TF_OUT="$(terraform -chdir="${dir}" output -json 2> /dev/null)" || rc=$?
  if ((rc != 0)) || ! printf '%s' "${TF_OUT}" | jq -e 'type == "object" and has("account_id") and has("region")' > /dev/null 2>&1; then
    sc_warn "Terraform outputs are missing: the environment has not been applied from this checkout (or the local state file is absent)."
    sc_apply_hint "infra/terraform/environments/${env}"
    exit 4
  fi
}

# sc_out <name>: a string output.
sc_out() { printf '%s' "${TF_OUT}" | jq -r --arg k "$1" '.[$k].value // empty'; }
# sc_out_f <name> <jq filter applied to the output value>.
sc_out_f() { printf '%s' "${TF_OUT}" | jq -r --arg k "$1" ".[\$k].value | $2"; }

# sc_guard_account_region: fail fast unless the caller account and Region equal the Terraform outputs account_id and region.
# Account IDs are never printed; only account_check=match.
sc_guard_account_region() {
  local exp_acct exp_region caller_region acct rc=0
  : "${AWS_PROFILE:?AWS_PROFILE must be set}"
  exp_acct="$(sc_out account_id)"
  exp_region="$(sc_out region)"
  [[ "${exp_acct}" =~ ^[0-9]{12}$ ]] || sc_die "Terraform output account_id is not 12 digits"
  [[ -n "${exp_region}" ]] || sc_die "Terraform output region is empty"
  caller_region="$(sc_effective_region)" || rc=$?
  case "${rc}" in
    0) ;;
    1) sc_die "no effective Region: set AWS_REGION" ;;
    *) sc_die "AWS_REGION and AWS_DEFAULT_REGION disagree; unset one" ;;
  esac
  [[ "${caller_region}" == "${exp_region}" ]] || sc_die "REGION MISMATCH: caller region ${caller_region} is not the Terraform region ${exp_region}; nothing was changed"
  export AWS_REGION="${exp_region}"
  acct="$(sc_retry sc_aws sts get-caller-identity --query Account --output text)" || sc_die "cannot read caller identity for profile ${AWS_PROFILE}"
  [[ "${acct}" =~ ^[0-9]{12}$ ]] || sc_die "STS returned an unexpected account value"
  if [[ "${acct}" != "${exp_acct}" ]]; then
    printf 'account_check=mismatch\n'
    sc_die "ACCOUNT MISMATCH: the caller account is not the Terraform output account_id; nothing was changed"
  fi
  printf 'account_check=match region=%s\n' "${exp_region}"
}

# Generated report directories do not count as a dirty tree (written by these scripts; the root .gitignore, owned by
# build-engineer, does not ignore them: merge item M13-3).
SC_GENERATED_EXCLUDES=(':(exclude)reports/release' ':(exclude)reports/release-local' ':(exclude)reports/deployments' ':(exclude)reports/pipeline' ':(exclude)reports/cleanup' ':(exclude)reports/evidence' ':(exclude)reports/experiments' ':(exclude)reports/env-window' ':(exclude)reports/smoke')

# sc_tree_dirty <repo-root>: true when tracked changes or untracked files exist outside the generated report directories.
sc_tree_dirty() {
  [[ -n "$(git -C "$1" status --porcelain -- . "${SC_GENERATED_EXCLUDES[@]}")" ]]
}

# sc_require_clean_tree <repo-root>: dies on a dirty tree.
sc_require_clean_tree() {
  if sc_tree_dirty "$1"; then
    git -C "$1" status --short -- . "${SC_GENERATED_EXCLUDES[@]}" | head -n 20 >&2
    printf 'SC-ERROR working tree is dirty: commit or stash first (refused)\n' >&2
    exit 3
  fi
}

# sc_latest_plan_record <repo-root>: prints the newest plan checksum file written by scripts/plan.sh. Names are
# tfplan-<env>-<commit>-<UTC stamp>.sha256; the sort key is the UTC stamp (not the commit hash that precedes it).
sc_latest_plan_record() {
  local f stamp best="" best_stamp=""
  while IFS= read -r f; do
    stamp="$(basename "${f}" | sed -n 's/^.*-\([0-9]\{8\}T[0-9]\{6\}Z\)\.sha256$/\1/p')"
    [[ -n "${stamp}" ]] || continue
    if [[ -z "${best}" || "${stamp}" > "${best_stamp}" ]]; then best="${f}" best_stamp="${stamp}"; fi
  done < <(find "$1/infra/terraform/plans" -maxdepth 1 -name 'tfplan-*.sha256' -type f 2> /dev/null | LC_ALL=C sort)
  [[ -n "${best}" ]] || return 1
  printf '%s\n' "${best}"
}

# sc_realpath <path>: absolute path with every symlink resolved (the file itself and its directories). The file need not exist, its
# directory must. Returns 1 otherwise.
sc_realpath() {
  local p="$1" l n=0
  while [[ -L "${p}" ]] && ((n < 20)); do
    l="$(readlink "${p}")"
    case "${l}" in
      /*) p="${l}" ;;
      *) p="$(dirname "${p}")/${l}" ;;
    esac
    n=$((n + 1))
  done
  local d
  d="$(cd "$(dirname "${p}")" 2> /dev/null && pwd -P)" || return 1
  printf '%s/%s\n' "${d}" "$(basename "${p}")"
}

# sc_require_gate_file <repo-root> <file> <needle>: the file must resolve (symlinks included) to docs/agent-runs/<phase>/human-gate.md
# inside the repository, be a non-empty regular file and contain <needle> (case-insensitive). READ ONLY: no script ever writes it (it is
# human-owned evidence). Exits 3 (refused) on any failure.
sc_require_gate_file() {
  local root="$1" f="$2" needle="$3" rootp real rel
  if [[ -z "${f}" ]]; then
    sc_warn "REFUSED: --gate-file <docs/agent-runs/<phase>/human-gate.md> is required (the file the human writes the decision in)"
    exit 3
  fi
  rootp="$(cd "${root}" && pwd -P)"
  real="$(sc_realpath "${f}")" || { sc_warn "REFUSED: gate file path is not usable: ${f}"; exit 3; }
  rel="${real#"${rootp}"/docs/agent-runs/}"
  if [[ "${rel}" == "${real}" || ! "${rel}" =~ ^[A-Za-z0-9._-]+/human-gate\.md$ ]]; then
    sc_warn "REFUSED: the gate file must resolve to docs/agent-runs/<phase>/human-gate.md inside the repository (got ${f})"
    exit 3
  fi
  if [[ ! -f "${real}" || ! -s "${real}" ]]; then
    sc_warn "REFUSED: the gate file does not exist or is empty: ${f}"
    exit 3
  fi
  if ! grep -qiF -- "${needle}" "${real}"; then
    sc_warn "REFUSED: the gate file does not contain the approved value (${needle}); the human has not recorded this decision"
    exit 3
  fi
}

# sc_json_write <file> <json>: writes validated, key-sorted JSON; the parent directory is created.
sc_json_write() {
  mkdir -p "$(dirname "$1")"
  printf '%s' "$2" | jq -S . > "$1"
}

# sc_utc: compact UTC stamp. sc_utc_iso: ISO-8601.
sc_utc() { date -u +%Y%m%dT%H%M%SZ; }
sc_utc_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# sc_valid_token <value>: letters, digits, dot, underscore, hyphen only.
sc_valid_token() { [[ "$1" =~ ^[A-Za-z0-9._-]+$ ]] || sc_die "invalid value (allowed: letters, digits, . _ -): $1"; }

# Per-service values without associative arrays (the target shell is bash 3.2 on macOS):
# sc_set <namespace> <key> <value>; sc_get <namespace> <key> prints the value (empty if unset).
sc_set() { printf -v "SCV_$1_${2//-/_}" '%s' "$3"; }
sc_get() {
  local n="SCV_$1_${2//-/_}"
  printf '%s' "${!n:-}"
}

# The three ECS services (also the keys of the Terraform output ecr_repository_urls).
# shellcheck disable=SC2034 # used by the scripts that source this file
SC_SERVICES=(query-api retrieval embedding-worker)
