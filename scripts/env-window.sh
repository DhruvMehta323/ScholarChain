#!/usr/bin/env bash
# scripts/env-window.sh up|down [options]
#
# The cost toggle for an experiment window (INFRA-CONTRACTS sections 9a, 9b, 10). It never applies or destroys anything.
#   up    PRINTS the exact human steps to switch Neptune and the load balancer on (create_neptune = true, create_alb = true). Makes no
#         AWS call and changes nothing.
#   down  scales all three ECS services to 0 directly through the ECS API (a reversible, no-data change; needs --yes), checks the running
#         count reaches 0, and PRINTS the human steps to switch Neptune and the load balancer off.
# Both print the current hourly cost estimate (INFRA-CONTRACTS section 10). Note: Application Auto Scaling may raise a service again if its
# minimum capacity is above 0 (default minimum is 0); section 9b gives the Terraform way to force it.
#
# Environment (names only): AWS_PROFILE and AWS_REGION (down only; Region must equal the Terraform output "region").
# Options: --env <name> (default course)  --yes (down: perform the scale-down)  --dry-run (down: print instead of changing)  --help
# Exit codes: 0 ok, 1 failure, 2 usage (including down without --yes or --dry-run), 4 Terraform outputs missing, 5 services did not reach 0.
# shellcheck disable=SC2016 # intentional: literal text in single quotes, JMESPath backticks, A && B || C status idiom, trap handler
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

MODE="" ENV_NAME=course YES=0
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --yes) YES=1 ;;
    --env) ENV_NAME="${2:?}"; shift ;;
    up | down) [[ -z "${MODE}" ]] || { echo "give one of up|down" >&2; exit 2; }; MODE="$1" ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done
[[ -n "${MODE}" ]] || { echo "usage: env-window.sh up|down [options] (see --help)" >&2; exit 2; }
ENV_REL="infra/terraform/environments/${ENV_NAME}"
sc_env_dir "${ROOT}" "${ENV_NAME}" > /dev/null

cost_table() {
  sc_stage cost-estimate
  printf '%s\n' \
    'SC-COST Neptune Serverless at the 2.5 NCU maximum: about $0.16 per hour (plus I/O and storage)' \
    'SC-COST Application Load Balancer: about $0.04 per hour (one LCU assumption, two public IPv4 addresses)' \
    'SC-COST Fargate task: about $0.017 per task-hour (0.25 vCPU / 0.5 GB with public IPv4)' \
    'SC-COST EMR cluster of 3 nodes: about $0.76 per hour while it exists' \
    'SC-COST Neptune plus ALB both on, nothing else running: about $0.20 per hour; operating rule A allows 8 hours in total for the project' \
    'SC-COST Source: infra/terraform/INFRA-CONTRACTS.md sections 9a and 10 (estimates, not measurements).'
}

if [[ "${MODE}" == up ]]; then
  sc_stage up
  cost_table
  sc_stage human-commands
  printf '%s\n' \
    "SC-HUMAN-COMMAND 1. In ${ENV_REL}/terraform.tfvars (gitignored) set exactly these two lines (neither default is true):" \
    '     create_neptune = true' \
    '     create_alb     = true' \
    '   Confirm the secrets all hold values and the Neptune engine and capacity variables are as reviewed.' \
    'SC-HUMAN-COMMAND 2. Plan (read-only):' \
    '     AWS_PROFILE=<profile> AWS_REGION=<region> bash scripts/plan.sh' \
    '   Review the summary: only Neptune, its subnet and parameter resources, the load balancer, target group, listener and scaling policy should be added.'
  sc_apply_hint "${ENV_REL}"
  printf '%s\n' \
    'SC-HUMAN-COMMAND 4. After apply: bash scripts/deploy.sh (if images changed), bash scripts/smoke-test.sh. Log the window start time; rule A allows 8 hours in total.' \
    'SC-HUMAN-NOTE this script did not change anything.'
  exit 0
fi

# ---- down ---------------------------------------------------------------------------------------------------------------------------
sc_stage preflight
if [[ "${YES}" != 1 && "${SC_DRY_RUN}" != 1 ]]; then
  echo "down changes the desired count of the three services: pass --yes to proceed, or --dry-run to preview" >&2
  exit 2
fi
sc_require_cmd terraform aws jq
: "${AWS_PROFILE:?AWS_PROFILE must be set}"
sc_load_outputs "${ROOT}" "${ENV_NAME}"
sc_guard_account_region
CLUSTER="$(sc_out ecs_cluster_name)"
[[ -n "${CLUSTER}" ]] || sc_die "Terraform output ecs_cluster_name is empty"

sc_stage scale-services-to-zero
counts() { sc_retry sc_aws ecs describe-services --cluster "${CLUSTER}" --services "${SC_SERVICES[@]}" --query 'services[].{service:serviceName,desired:desiredCount,running:runningCount}' --output json; }
BEFORE="$(counts)" || sc_die "cannot describe the services"
printf 'SC-INFO before: %s\n' "$(printf '%s' "${BEFORE}" | jq -c .)"
for svc in "${SC_SERVICES[@]}"; do
  sc_mut sc_aws ecs update-service --cluster "${CLUSTER}" --service "${svc}" --desired-count 0 --output json > /dev/null
done
AFTER='[]'
if [[ "${SC_DRY_RUN}" != 1 ]]; then
  sc_stage verify-zero
  deadline=$((SECONDS + ${SC_DOWN_TIMEOUT:-600}))
  while :; do
    AFTER="$(counts)" || sc_die "cannot describe the services"
    if [[ "$(printf '%s' "${AFTER}" | jq 'length == 3 and all(.desired == 0 and .running == 0)')" == true ]]; then break; fi
    if ((SECONDS >= deadline)); then
      sc_warn "services did not reach 0: $(printf '%s' "${AFTER}" | jq -c .). A scalable target with a minimum above 0 raises them again: see INFRA-CONTRACTS 9b (Terraform way)."
      exit 5
    fi
    sleep "${SC_POLL_SECONDS:-10}"
  done
  sc_ok "all three services have desired 0 and running 0"
  REC="$(jq -n --arg at "$(sc_utc_iso)" --arg env "${ENV_NAME}" --argjson b "${BEFORE}" --argjson a "${AFTER}" '{kind: "env-window-down", at: $at, environment: $env, before: $b, after: $a, data_changes: "none"}')"
  sc_json_write "${ROOT}/reports/env-window/down-$(sc_utc).json" "${REC}"
fi

cost_table
sc_stage human-commands
printf '%s\n' \
  'SC-HUMAN-COMMAND To also stop Neptune and the load balancer (their hourly cost continues until then):' \
  '  The ALB can be switched off at once: set create_alb = false in terraform.tfvars, run scripts/plan.sh, review, then apply the saved plan.' \
  '  Neptune holds data. Switching it off DROPS the graph (it is rebuilt from S3 on the next window). It needs a recorded human decision and the protection switches first:' \
  "  1. plan with neptune_deletion_protection=false (see the cleanup flow printed by scripts/cleanup.sh, commands B and C), review, apply that saved plan;" \
  '  2. set create_neptune = false (and create_alb = false) in terraform.tfvars, run scripts/plan.sh, review that only Neptune and ALB resources are removed, apply the saved plan.'
sc_apply_hint "${ENV_REL}"
printf '%s\n' 'SC-HUMAN-NOTE this script changed only the desired counts of the three ECS services.'
