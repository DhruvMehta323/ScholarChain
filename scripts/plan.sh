#!/usr/bin/env bash
# scripts/plan.sh [environment]            (default environment: course)
#
# Read-only planning for a ScholarChain Terraform environment. It never creates, changes or removes any resource and it
# never runs the human apply step: it ends after printing a summary for human review.
#
# Usage (human, own terminal):   AWS_PROFILE=scholarchain bash scripts/plan.sh
# Fails before planning unless the STS account equals variable expected_account_id and the caller Region equals aws_region, and
# refuses tfvars.json, *.auto.tfvars, override files, *.tf.json, TF_WORKSPACE, TF_DATA_DIR, non-default workspace and TF_CLI_ARGS*.
# Account IDs are never printed. After planning it re-checks the plan variables and deletes the plan file on mismatch.
# Requires: AWS_PROFILE in the environment, terraform, aws, git, jq. A dirty git tree is allowed and is recorded (dirty flag,
# SHA-256 of "git diff HEAD", sorted untracked list with its SHA-256).
# Required Terraform variables (owner, expiration_date, alert_email) come from a gitignored terraform.tfvars in the
# environment directory or from TF_VAR_* variables; the script runs with -input=false and fails if one is missing.
#
# Output (all gitignored by infra/terraform/plans/.gitignore, which ignores everything but itself): infra/terraform/plans/tfplan-<env>-<commit>-<utc>.{tfplan,sha256,summary.txt,untracked.txt}
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

TOTAL=6
ENV_NAME="${1:-course}"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

sc_step 1 "${TOTAL}" "preflight"
sc_require_cmd git terraform aws jq
: "${AWS_PROFILE:?AWS_PROFILE must be set, for example: AWS_PROFILE=scholarchain bash scripts/plan.sh}"
ENV_DIR="$(sc_env_dir "${ROOT}" "${ENV_NAME}")"

# A dirty tree is allowed for planning, but it is recorded exactly: tracked changes by the SHA-256 of "git diff HEAD" and
# untracked (not ignored) files by the SHA-256 of their sorted list.
COMMIT_SHA="$(git -C "${ROOT}" rev-parse HEAD)"
DIFF_SHA="$(git -C "${ROOT}" diff HEAD | sc_sha256_stdin)"
UNTRACKED_LIST="$(git -C "${ROOT}" ls-files --others --exclude-standard | LC_ALL=C sort)"
UNTRACKED_SHA="$(printf '%s\n' "${UNTRACKED_LIST}" | sc_sha256_stdin)"
if [[ -n "$(git -C "${ROOT}" status --porcelain)" ]]; then DIRTY=true; else DIRTY=false; fi
sc_info "environment=${ENV_NAME} commit=${COMMIT_SHA} profile=${AWS_PROFILE}"
sc_info "dirty=${DIRTY} git_diff_head_sha256=${DIFF_SHA} untracked_list_sha256=${UNTRACKED_SHA}"
if [[ "${DIRTY}" == true ]]; then
  sc_warn "working tree is dirty: the plan is NOT tied to the commit alone; see the recorded diff and untracked hashes"
fi

sc_info "checking Terraform inputs (no AWS call)"
sc_preflight_tf_inputs "${ROOT}" "${ENV_DIR}"

# Load the variables the guard needs BEFORE any AWS call, with the precedence Terraform uses (no eval):
# variables.tf default < TF_VAR_<name> < terraform.tfvars (the only accepted file).
rc=0
EXPECTED_LINE="$(sc_resolve_tfvar "${ENV_DIR}" expected_account_id)" || rc=$?
case "${rc}" in
  0) ;;
  1) sc_die "expected_account_id is not set: define it in ${ENV_DIR}/terraform.tfvars or TF_VAR_expected_account_id" ;;
  *) sc_die "expected_account_id is empty, ambiguous or not in the simple form name = \"value\"; fix terraform.tfvars" ;;
esac
EXPECTED_ACCOUNT="${EXPECTED_LINE%%$'\t'*}"
EXPECTED_ACCOUNT_SRC="${EXPECTED_LINE#*$'\t'}"
[[ "${EXPECTED_ACCOUNT}" =~ ^[0-9]{12}$ ]] || sc_die "expected_account_id (from ${EXPECTED_ACCOUNT_SRC}) is not 12 digits"
rc=0
REGION_LINE="$(sc_resolve_tfvar "${ENV_DIR}" aws_region)" || rc=$?
case "${rc}" in
  0) ;;
  1) sc_die "aws_region cannot be resolved from variables.tf or tfvars" ;;
  *) sc_die "aws_region is empty, ambiguous or not in the simple form name = \"value\"; fix terraform.tfvars" ;;
esac
EXPECTED_REGION="${REGION_LINE%%$'\t'*}"
EXPECTED_REGION_SRC="${REGION_LINE#*$'\t'}"
rc=0
CALLER_REGION="$(sc_effective_region)" || rc=$?
case "${rc}" in
  0) ;;
  1) sc_die "no effective Region: set AWS_REGION or configure a region for profile ${AWS_PROFILE}" ;;
  *) sc_die "AWS_REGION and AWS_DEFAULT_REGION disagree; unset one" ;;
esac
[[ "${CALLER_REGION}" == "${EXPECTED_REGION}" ]] || sc_die "REGION MISMATCH: caller region ${CALLER_REGION} is not the Terraform region ${EXPECTED_REGION}; nothing was planned"
sc_info "terraform_aws_region=${EXPECTED_REGION} (${EXPECTED_REGION_SRC}) caller_effective_region=${CALLER_REGION}"

sc_step 2 "${TOTAL}" "identity (read-only sts call)"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)" || sc_die "cannot read caller identity for profile ${AWS_PROFILE}"
[[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]] || sc_die "STS returned an unexpected account value; refusing to continue"
# Account IDs are never printed (the summary is pasted into tracked reports); only the comparison result is.
[[ "${ACCOUNT_ID}" == "${EXPECTED_ACCOUNT}" ]] || sc_die "ACCOUNT MISMATCH: the caller account is not expected_account_id (from ${EXPECTED_ACCOUNT_SRC}); nothing was planned"
sc_ok "account and Region match the Terraform variables (account_check=match)"

sc_step 3 "${TOTAL}" "terraform init"
terraform -chdir="${ENV_DIR}" init -input=false

sc_step 4 "${TOTAL}" "terraform plan"
PLAN_DIR="${ROOT}/infra/terraform/plans"
mkdir -p "${PLAN_DIR}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BASE="tfplan-${ENV_NAME}-${COMMIT_SHA:0:12}-${STAMP}"
PLAN_FILE="${PLAN_DIR}/${BASE}.tfplan"
set +e
terraform -chdir="${ENV_DIR}" plan -input=false -lock-timeout=60s -detailed-exitcode -out="${PLAN_FILE}"
PLAN_RC=$?
set -e
case "${PLAN_RC}" in
  0) sc_info "plan exit code 0: no changes" ;;
  2) sc_info "plan exit code 2: changes present" ;;
  *) sc_die "terraform plan failed (exit code ${PLAN_RC})" ;;
esac

sc_step 5 "${TOTAL}" "post-plan check, checksum and metadata"
# Remove the plan file and stop: a plan that does not match the caller must never be applied.
reject_plan() {
  rm -f -- "${PLAN_FILE:?}"
  sc_die "$1: the plan file was deleted"
}
PLAN_JSON="$(terraform -chdir="${ENV_DIR}" show -json "${PLAN_FILE}")" || reject_plan "terraform show failed"
REGION="$(printf '%s' "${PLAN_JSON}" | jq -r '.variables.aws_region.value // "unknown"')"
PLAN_ACCOUNT="$(printf '%s' "${PLAN_JSON}" | jq -r '.variables.expected_account_id.value // "unknown"')"
# Second check against what Terraform actually used (covers any case the pre-check could not see).
[[ "${PLAN_ACCOUNT}" == "${ACCOUNT_ID}" ]] || reject_plan "plan used an expected_account_id that is not the caller account"
[[ "${REGION}" == "${CALLER_REGION}" ]] || reject_plan "plan used aws_region ${REGION}, caller region is ${CALLER_REGION}"
PLAN_SHA="$(sc_sha256 "${PLAN_FILE}")"
printf '%s\n' "${UNTRACKED_LIST}" > "${PLAN_DIR}/${BASE}.untracked.txt"
printf '%s  %s\n' "${PLAN_SHA}" "$(basename "${PLAN_FILE}")" > "${PLAN_DIR}/${BASE}.sha256"

sc_step 6 "${TOTAL}" "summary"
{
  echo "SC-SUMMARY-BEGIN"
  echo "plan_file=${PLAN_FILE}"
  echo "plan_sha256=${PLAN_SHA}"
  echo "commit_sha=${COMMIT_SHA}"
  echo "dirty=${DIRTY}"
  echo "git_diff_head_sha256=${DIFF_SHA}"
  echo "untracked_list_sha256=${UNTRACKED_SHA}"
  echo "untracked_list_file=${PLAN_DIR}/${BASE}.untracked.txt"
  echo "account_check=match"
  echo "region=${REGION}"
  echo "environment=${ENV_NAME}"
  echo "plan_exit_code=${PLAN_RC}"
  printf '%s' "${PLAN_JSON}" | jq -r '
    def acts: .change.actions;
    [.resource_changes[]? | select((acts | . == ["no-op"] or . == ["read"]) | not)] as $c
    | "resources_to_add=\([$c[] | select(acts == ["create"])] | length)",
      "resources_to_change=\([$c[] | select(acts == ["update"])] | length)",
      "resources_to_destroy=\([$c[] | select(acts == ["delete"])] | length)",
      "resources_to_replace=\([$c[] | select(acts | (index("delete") != null and index("create") != null))] | length)",
      "iam_changes=\([$c[] | select(.type | startswith("aws_iam_"))] | length)",
      ($c[] | select(.type | startswith("aws_iam_")) | "IAM  \(acts | join("+"))  \(.address)"),
      "nat_gateways_in_plan=\([.resource_changes[]? | select(.type == "aws_nat_gateway")] | length)",
      "interface_endpoints_in_plan=\([.resource_changes[]? | select(.type == "aws_vpc_endpoint" and (.change.after.vpc_endpoint_type // "") == "Interface")] | length)",
      "stateful_resources_in_plan:",
      ($c[] | select(.type | IN("aws_neptune_cluster","aws_neptune_cluster_instance","aws_dynamodb_table","aws_s3_bucket","aws_s3vectors_vector_bucket","aws_s3vectors_index","aws_secretsmanager_secret","aws_ecr_repository","aws_sqs_queue")) | "STATEFUL  \(acts | join("+"))  \(.address)"),
      "stateful_destroy_or_replace:",
      ($c[] | select(.type | IN("aws_neptune_cluster","aws_neptune_cluster_instance","aws_dynamodb_table","aws_s3_bucket","aws_s3vectors_vector_bucket","aws_s3vectors_index","aws_secretsmanager_secret","aws_ecr_repository","aws_sqs_queue")) | select(acts | index("delete") != null) | "STATEFUL-DESTROY-OR-REPLACE  \(acts | join("+"))  \(.address)")
  '
  echo "SC-SUMMARY-END"
} | tee "${PLAN_DIR}/${BASE}.summary.txt"

sc_ok "plan saved: ${PLAN_FILE}"
sc_info "A human reviews the summary above, the reviewer verdict and the cost table; the apply step is human-only and is not part of this script."
