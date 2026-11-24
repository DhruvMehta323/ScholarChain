#!/usr/bin/env bash
# Shared helpers for ScholarChain operational scripts. Source this file; do not execute it.
# Owner: infra-engineer. Contains no deletion commands and no state-changing infrastructure commands.

# Greppable step markers: "SC-STEP <n>/<total> <name>", "SC-INFO <text>", "SC-WARN <text>", "SC-ERROR <text>", "SC-OK <text>".
sc_step() { printf 'SC-STEP %s/%s %s\n' "$1" "$2" "$3"; }
sc_info() { printf 'SC-INFO %s\n' "$*"; }
sc_warn() { printf 'SC-WARN %s\n' "$*" >&2; }
sc_ok() { printf 'SC-OK %s\n' "$*"; }
sc_die() {
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
