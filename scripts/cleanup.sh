#!/usr/bin/env bash
# scripts/cleanup.sh [environment]         (default environment: course)
#
# HUMAN-EXECUTED, READ-ONLY. It lists what a cleanup of the Terraform environment would remove, states which buckets are
# preserved as evidence, and PRINTS the exact commands for a human to run. It never runs an infrastructure change itself,
# never deletes anything and contains no deletion command. Data destruction needs a human decision recorded in the phase
# human-gate.md before any printed command is run (docs/agents.md section 4).
#
# It reads only the local state file of the environment (terraform state list); it makes no AWS call.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

TOTAL=4
ENV_NAME="${1:-course}"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

sc_step 1 "${TOTAL}" "preflight"
sc_require_cmd terraform
ENV_DIR="$(sc_env_dir "${ROOT}" "${ENV_NAME}")"
ENV_REL="infra/terraform/environments/${ENV_NAME}"
if [[ ! -f "${ENV_DIR}/terraform.tfstate" ]]; then
  sc_die "no local state file at ${ENV_REL}/terraform.tfstate: nothing is recorded as created from this checkout"
fi

sc_step 2 "${TOTAL}" "resources recorded in local state"
STATE_LIST="$(terraform -chdir="${ENV_DIR}" state list)"
COUNT="$(printf '%s\n' "${STATE_LIST}" | grep -c . || true)"
sc_info "resources_in_state=${COUNT}"

echo "SC-LIST-BEGIN preserved-as-evidence (dropped from state by command D, never destroyed by this procedure)"
printf '%s\n' "${STATE_LIST}" | grep -E '^module\.data\.aws_s3_bucket\.this\["(raw|reports)"\]$' || echo "(none in state)"
echo "SC-LIST-END preserved-as-evidence"

echo "SC-LIST-BEGIN stateful-resources-to-remove (data is lost once removed)"
printf '%s\n' "${STATE_LIST}" | grep -E '^module\.(neptune\.aws_neptune_cluster\.|neptune\.aws_neptune_cluster_instance\.|data\.aws_dynamodb_table\.|data\.aws_s3vectors_|data\.aws_s3_bucket\.this\["(curated|logs|artifacts)"\]|platform\.aws_ecr_repository\.|platform\.aws_sqs_queue\.|platform\.aws_secretsmanager_secret\.)' || echo "(none in state)"
echo "SC-LIST-END stateful-resources-to-remove"

echo "SC-LIST-BEGIN all-other-resources-to-remove"
printf '%s\n' "${STATE_LIST}" | grep -Ev '^module\.(neptune\.aws_neptune_cluster\.|neptune\.aws_neptune_cluster_instance\.|data\.aws_dynamodb_table\.|data\.aws_s3vectors_|data\.aws_s3_bucket\.this\["(curated|logs|artifacts|raw|reports)"\]|platform\.aws_ecr_repository\.|platform\.aws_sqs_queue\.|platform\.aws_secretsmanager_secret\.)' || echo "(none)"
echo "SC-LIST-END all-other-resources-to-remove"

sc_step 3 "${TOTAL}" "evidence policy"
sc_info "Buckets raw and reports hold datasets and evidence. Command D (printed below) drops them and their sub-resources from state before teardown, so they stay in AWS intact."
sc_info "Archive evidence first (scripts/archive-evidence.sh, when it exists), then decide in human-gate.md whether raw and reports are emptied."
sc_info "Versioned buckets (raw, reports, artifacts) also need old versions and delete markers removed before a bucket can go: that is a human data-destruction step."

sc_step 4 "${TOTAL}" "commands for a HUMAN to run (printed, not executed)"
cat << EOF
SC-HUMAN-COMMAND A. record a human decision in docs/agent-runs/<phase>/human-gate.md, then archive evidence
SC-HUMAN-COMMAND B. plan the protection switches off (read-only):
  AWS_PROFILE=scholarchain terraform -chdir=${ENV_REL} plan -input=false -out=../../plans/tfplan-cleanup-flags.tfplan \\
    -var neptune_deletion_protection=false -var dynamodb_deletion_protection=false \\
    -var force_destroy_intermediate=true -var vector_force_destroy=true
SC-HUMAN-COMMAND C. after reviewing that plan, apply exactly that saved plan (this turns protections off, it removes nothing):
  AWS_PROFILE=scholarchain terraform -chdir=${ENV_REL} apply ../../plans/tfplan-cleanup-flags.tfplan
SC-HUMAN-COMMAND D. EVIDENCE PRESERVATION (human-only state change). Before any teardown, drop the raw and reports buckets AND all of their
  Terraform sub-resources from the local state, so teardown cannot touch the buckets or strip their settings. Preview first (changes nothing),
  then run the same list without -dry-run:
EOF
CONT="\\"
for dry in "state rm -dry-run" "state rm"; do
  printf '  terraform -chdir=%s %s %s\n' "${ENV_REL}" "${dry}" "${CONT}"
  for kind in raw reports; do
    for res in aws_s3_bucket aws_s3_bucket_public_access_block aws_s3_bucket_ownership_controls aws_s3_bucket_server_side_encryption_configuration aws_s3_bucket_versioning aws_s3_bucket_lifecycle_configuration; do
      printf '    %s %s\n' "'module.data.${res}.this[\"${kind}\"]'" "${CONT}"
    done
    printf '    %s %s\n' "'module.data.aws_s3_bucket_policy.tls_only[\"${kind}\"]'" "${CONT}"
  done
  echo "    # (end of list)"
done
cat << EOF
SC-HUMAN-COMMAND E. preview the removal (read-only), then run it (it now leaves raw and reports untouched):
  AWS_PROFILE=scholarchain terraform -chdir=${ENV_REL} plan -destroy -input=false \\
    -var neptune_deletion_protection=false -var dynamodb_deletion_protection=false \\
    -var force_destroy_intermediate=true -var vector_force_destroy=true
  AWS_PROFILE=scholarchain terraform -chdir=${ENV_REL} destroy \\
    -var neptune_deletion_protection=false -var dynamodb_deletion_protection=false \\
    -var force_destroy_intermediate=true -var vector_force_destroy=true
SC-HUMAN-NOTE ORDER MATTERS: run B and C (protection switches, applied while the evidence buckets are still in state), then D, then E.
SC-HUMAN-NOTE Resulting posture: raw and reports buckets stay in AWS intact with their settings (public-access block, TLS-only policy, versioning, SSE, lifecycle),
  but Terraform no longer tracks them. A later plan with the same configuration would try to create them and fail on the name, so either
  re-import the seven addresses per bucket (terraform import) or remove them from the configuration. Their removal is a separate human data-destruction decision.
SC-HUMAN-NOTE If the state file is lost or replaced before step D, run nothing from E: the buckets would be unprotected again.
SC-HUMAN-NOTE Neptune: skip_final_snapshot defaults to true (graph is rebuilt from S3). Set -var neptune_skip_final_snapshot=false to keep a final snapshot.
SC-HUMAN-NOTE Secrets Manager entries stay recoverable for the recovery window (default 7 days) after removal.
SC-HUMAN-NOTE Cheaper partial stop without removing data: -var create_neptune=false and/or -var create_alb=false (needs the protection switches above for Neptune).
EOF
sc_info "If the environment directory was not initialized on this machine, run terraform init there first (state list needs it)."
sc_ok "cleanup listing complete; nothing was changed"
