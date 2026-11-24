#!/usr/bin/env bash
# scripts/check.sh [options]
#
# All the non-cloud validation in one command; it makes no AWS call and changes no infrastructure. Every check runs even when an
# earlier one fails, and the exit code is nonzero if any failed. Checks:
#   agent-files     .claude/hooks/validate-agent-files.sh (agent frontmatter, roster, commands, settings, hooks, MCP configuration)
#   json            .mcp.json, .claude/settings.json and every JSON contract, schema or sample file under modules, datasets and infra (jq);
#                   test fixtures (fixtures/, src/test, src/it) are skipped because some are invalid on purpose
#   hooks           bash -n on .claude/hooks/*.sh
#   scripts         bash -n on every script under scripts/ (and shellcheck when it is installed)
#   terraform       terraform fmt -check -recursive, and validate with no backend (init -backend=false only when .terraform/ is absent;
#                   -lockfile=readonly so the committed lock file is not rewritten)
#   script-tests    scripts/tests/test-scripts.sh: the offline harness (stub aws, terraform, docker, sbt, curl; no AWS call)
#   sbt             the canonical definition of done: sbt check (it also writes the test marker itself)
# Environment (names only): none required; SC_SBT_TIMEOUT (default 3600) bounds the sbt run.
# Options: --skip-sbt  --skip-terraform  --skip-script-tests  --env <name> (default course)  --dry-run (print the checks)  --help
# Exit codes: 0 all passed, 1 at least one check failed, 2 usage.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

SKIP_SBT=0 SKIP_TF=0 SKIP_TESTS=0 ENV_NAME=course
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --skip-sbt) SKIP_SBT=1 ;;
    --skip-terraform) SKIP_TF=1 ;;
    --skip-script-tests) SKIP_TESTS=1 ;;
    --env) ENV_NAME="${2:?}"; shift ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

FAILED=0
fail() { sc_warn "FAILED: $1"; FAILED=$((FAILED + 1)); }
run() { # run <label> <command...>: in dry-run only prints
  local label="$1"
  shift
  if [[ "${SC_DRY_RUN}" == 1 ]]; then printf 'SC-DRYRUN [%s] %s\n' "${label}" "$*"; return 0; fi
  if "$@"; then sc_ok "${label}"; else fail "${label}"; fi
}

cd "${ROOT}"

sc_stage agent-files
if [[ -f .claude/hooks/validate-agent-files.sh ]]; then run agent-files bash .claude/hooks/validate-agent-files.sh; else fail "agent-files: .claude/hooks/validate-agent-files.sh is missing"; fi

sc_stage json
sc_require_cmd jq
JSON_FILES=()
for f in .mcp.json .claude/settings.json; do [[ -f "${f}" ]] && JSON_FILES+=("${f}"); done
while IFS= read -r f; do JSON_FILES+=("${f}"); done < <(find modules datasets infra -type f -name '*.json' -not -path '*/target/*' -not -path '*/.terraform/*' -not -path '*/raw/*' -not -path '*/fixtures/*' -not -path '*/src/test/*' -not -path '*/src/it/*' 2> /dev/null | LC_ALL=C sort)
bad=0
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf 'SC-DRYRUN [json] jq empty on %s files\n' "${#JSON_FILES[@]}"
else
  for f in "${JSON_FILES[@]}"; do jq -e . "${f}" > /dev/null 2>&1 || { sc_warn "invalid JSON: ${f}"; bad=$((bad + 1)); }; done
  if ((bad == 0)); then sc_ok "json (${#JSON_FILES[@]} files)"; else fail "json (${bad} invalid)"; fi
fi

sc_stage hooks
hooks_ok=1
for f in .claude/hooks/*.sh; do
  [[ -f "${f}" ]] || continue
  if [[ "${SC_DRY_RUN}" == 1 ]]; then printf 'SC-DRYRUN [hooks] bash -n %s\n' "${f}"; elif ! bash -n "${f}"; then sc_warn "syntax error: ${f}"; hooks_ok=0; fi
done
if [[ "${SC_DRY_RUN}" != 1 ]]; then if ((hooks_ok == 1)); then sc_ok "hooks syntax"; else fail "hooks syntax"; fi; fi

sc_stage scripts
scripts_ok=1
SCRIPT_FILES=()
while IFS= read -r f; do SCRIPT_FILES+=("${f}"); done < <(find scripts -type f -name '*.sh' | LC_ALL=C sort)
for f in "${SCRIPT_FILES[@]}"; do
  if [[ "${SC_DRY_RUN}" == 1 ]]; then printf 'SC-DRYRUN [scripts] bash -n %s\n' "${f}"; elif ! bash -n "${f}"; then sc_warn "syntax error: ${f}"; scripts_ok=0; fi
done
if [[ "${SC_DRY_RUN}" != 1 ]]; then
  if command -v shellcheck > /dev/null 2>&1; then
    shellcheck -x -P SCRIPTDIR "${SCRIPT_FILES[@]}" || scripts_ok=0
  else
    sc_warn "shellcheck is not installed: only bash -n was run"
  fi
  if ((scripts_ok == 1)); then sc_ok "scripts"; else fail "scripts"; fi
fi

sc_stage terraform
if [[ "${SKIP_TF}" == 1 ]]; then
  sc_info "skipped (--skip-terraform)"
else
  sc_require_cmd terraform
  ENV_DIR="$(sc_env_dir "${ROOT}" "${ENV_NAME}")"
  run terraform-fmt terraform -chdir=infra/terraform fmt -check -recursive
  if [[ ! -d "${ENV_DIR}/.terraform" ]]; then
    run terraform-init-no-backend terraform -chdir="${ENV_DIR}" init -backend=false -input=false -lockfile=readonly
  fi
  run terraform-validate terraform -chdir="${ENV_DIR}" validate
fi

sc_stage script-tests
if [[ "${SKIP_TESTS}" == 1 || -n "${SC_IN_TEST_HARNESS:-}" ]]; then
  sc_info "skipped"
else
  run script-tests bash scripts/tests/test-scripts.sh
fi

sc_stage sbt
if [[ "${SKIP_SBT}" == 1 ]]; then
  sc_info "skipped (--skip-sbt)"
else
  sc_require_cmd sbt
  run sbt-check sc_timeout "${SC_SBT_TIMEOUT:-3600}" sbt check
fi

sc_stage summary
if [[ "${SC_DRY_RUN}" == 1 ]]; then sc_ok "dry run: nothing was executed"; exit 0; fi
if ((FAILED > 0)); then
  sc_warn "${FAILED} check group(s) failed"
  exit 1
fi
sc_ok "all checks passed"
