#!/usr/bin/env bash
# scripts/cleanup.sh [environment] [options]         (default environment: course)
#
# HUMAN-EXECUTED. It never destroys infrastructure and never runs an apply: it lists what a cleanup of the Terraform environment
# would remove, writes an inventory BEFORE and AFTER to reports/cleanup/<utc>/, states which buckets are preserved as evidence, and
# PRINTS the exact commands for a human to run. Data destruction needs a human decision recorded in the phase human-gate.md before
# any printed command is run (docs/agents.md section 4).
#
# The only deletion this script can perform is emptying the three ECR repositories (images only, never the repositories), because the
# repositories have force_delete = false and a repository that holds images cannot be removed. That needs ALL of:
#   --empty-ecr   --confirm-empty-ecr   --gate-file <the human-written decision file, must exist and be non-empty>
# Without them (or with --dry-run) it only LISTS the images that would be deleted. It is human-executed. The agent guard blocks agents
# from running it because the script text contains the Terraform commands (the two infrastructure-changing verbs) that it PRINTS for the
# human: in Phase 13 repair round 1 the guard reported its Terraform rule as "found inside" this file when a syntax check was
# attempted. The guard's reason is that printed text, not the image-deletion call.
#
# Environment (names only): AWS_PROFILE and AWS_REGION (the Region must equal the Terraform output "region"; the account is compared to
# the output account_id and never printed). With --no-aws the script reads only the local Terraform state and writes a state-only inventory.
# Options: --empty-ecr  --confirm-empty-ecr  --gate-file <file>  --no-aws  --dry-run  --help
# Exit codes: 0 ok, 1 failure, 2 usage (also: --empty-ecr without all its confirmations), 4 outputs missing.
# shellcheck disable=SC2016,SC1003 # intentional: literal text in single quotes, JMESPath backticks, A && B || C status idiom, trap handler
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

ENV_NAME="" EMPTY=0 CONFIRM=0 GATE="" NO_AWS=0
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --empty-ecr) EMPTY=1 ;;
    --confirm-empty-ecr) CONFIRM=1 ;;
    --gate-file) GATE="${2:?}"; shift ;;
    --no-aws) NO_AWS=1 ;;
    -*) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
    *) [[ -z "${ENV_NAME}" ]] || { echo "one environment only" >&2; exit 2; }; ENV_NAME="$1" ;;
  esac
  shift
done
[[ -n "${ENV_NAME}" ]] || ENV_NAME=course
DELETE_ECR=0
if [[ "${EMPTY}" == 1 ]]; then
  if [[ "${CONFIRM}" != 1 || -z "${GATE}" || ! -s "${GATE}" ]]; then
    echo "--empty-ecr needs --confirm-empty-ecr and --gate-file <existing, non-empty human decision file>" >&2
    exit 2
  fi
  [[ "${SC_DRY_RUN}" == 1 ]] || DELETE_ECR=1
elif [[ "${CONFIRM}" == 1 || -n "${GATE}" ]]; then
  echo "--confirm-empty-ecr and --gate-file only apply together with --empty-ecr" >&2
  exit 2
fi

TOTAL=7
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

sc_step 1 "${TOTAL}" "preflight"
sc_require_cmd terraform jq
ENV_DIR="$(sc_env_dir "${ROOT}" "${ENV_NAME}")"
ENV_REL="infra/terraform/environments/${ENV_NAME}"
if [[ ! -f "${ENV_DIR}/terraform.tfstate" ]]; then
  sc_die "no local state file at ${ENV_REL}/terraform.tfstate: nothing is recorded as created from this checkout"
fi
STAMP="$(sc_utc)"
OUTDIR="${ROOT}/reports/cleanup/${STAMP}"
AWS_OK=0
if [[ "${NO_AWS}" != 1 ]]; then
  sc_require_cmd aws
  : "${AWS_PROFILE:?AWS_PROFILE must be set (or pass --no-aws for a state-only inventory)}"
  sc_load_outputs "${ROOT}" "${ENV_NAME}"
  sc_guard_account_region
  AWS_OK=1
fi

# inventory <label>: JSON of what exists, from the local state and (read-only) AWS describe and list calls.
inventory() {
  local label="$1" state count inv ecr='{}' ecs='[]' emr=0 nep=0 ddb=0 buckets='{}' svc repo n b url
  state="$(terraform -chdir="${ENV_DIR}" state list)"
  count="$(printf '%s\n' "${state}" | grep -c . || true)"
  if [[ "${AWS_OK}" == 1 ]]; then
    for svc in "${SC_SERVICES[@]}"; do
      repo="scholarchain/${svc}"
      n="$(sc_retry sc_aws ecr list-images --repository-name "${repo}" --query 'length(imageIds)' --output text 2> /dev/null || echo unknown)"
      ecr="$(printf '%s' "${ecr}" | jq --arg r "${repo}" --arg n "${n}" '. + {($r): $n}')"
    done
    ecs="$(sc_retry sc_aws ecs describe-services --cluster "$(sc_out ecs_cluster_name)" --services "${SC_SERVICES[@]}" --query 'services[].{service:serviceName,desired:desiredCount,running:runningCount}' --output json 2> /dev/null || echo '[]')"
    emr="$(sc_retry sc_aws emr list-clusters --active --query 'length(Clusters)' --output text 2> /dev/null || echo unknown)"
    nep="$(sc_retry sc_aws neptune describe-db-clusters --query 'length(DBClusters[?starts_with(DBClusterIdentifier, `scholarchain`)])' --output text 2> /dev/null || echo unknown)"
    ddb="$(sc_retry sc_aws dynamodb list-tables --query 'length(TableNames[?starts_with(@, `scholarchain-`)])' --output text 2> /dev/null || echo unknown)"
    for b in raw_bucket curated_bucket reports_bucket logs_bucket artifacts_bucket; do
      url="$(sc_out "${b}")"
      n="$(sc_retry sc_aws s3api list-objects-v2 --bucket "${url}" --max-keys 1000 --query 'KeyCount' --output text 2> /dev/null || echo unknown)"
      buckets="$(printf '%s' "${buckets}" | jq --arg k "${b}" --arg n "${n}" '. + {($k): $n}')"
    done
  fi
  inv="$(jq -n --arg label "${label}" --arg at "$(sc_utc_iso)" --argjson state "${count}" --argjson aws "${AWS_OK}" \
    --argjson ecr "${ecr}" --argjson ecs "${ecs}" --arg emr "${emr}" --arg nep "${nep}" --arg ddb "${ddb}" --argjson buckets "${buckets}" \
    '{label: $label, taken_at: $at, terraform_state_resources: $state, aws_reads_included: ($aws == 1), ecr_image_counts: $ecr, ecs_services: $ecs,
      emr_active_clusters: $emr, neptune_scholarchain_clusters: $nep, dynamodb_scholarchain_tables: $ddb, bucket_object_counts_capped_at_1000: $buckets,
      evidence_buckets_preserved: ["raw", "reports"]}')"
  if [[ "${SC_DRY_RUN}" == 1 ]]; then
    printf 'SC-DRYRUN would write reports/cleanup/%s/inventory-%s.json:\n%s\n' "${STAMP}" "${label}" "$(printf '%s' "${inv}" | jq -S .)"
  else
    sc_json_write "${OUTDIR}/inventory-${label}.json" "${inv}"
    sc_ok "inventory ${label}: reports/cleanup/${STAMP}/inventory-${label}.json"
  fi
}

sc_step 2 "${TOTAL}" "inventory before"
inventory before

sc_step 3 "${TOTAL}" "resources recorded in local state"
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

sc_step 4 "${TOTAL}" "evidence policy"
sc_info "Buckets raw and reports hold datasets and evidence. Command D (printed below) drops them and their sub-resources from state before teardown, so they stay in AWS intact."
sc_info "Archive evidence first (bash scripts/archive-evidence.sh --to-s3), then decide in human-gate.md whether raw and reports are emptied."
sc_info "Versioned buckets (raw, reports, artifacts) also need old versions and delete markers removed before a bucket can go: that is a human data-destruction step."

sc_step 5 "${TOTAL}" "ECR repositories (force_delete = false: images must be removed before destroy)"
if [[ "${AWS_OK}" != 1 ]]; then
  sc_warn "--no-aws: the ECR images were not listed. Re-run without --no-aws to list or empty them."
else
  for svc in "${SC_SERVICES[@]}"; do
    repo="scholarchain/${svc}"
    IDS="$(sc_retry sc_aws ecr list-images --repository-name "${repo}" --query 'imageIds[*]' --output json)" || sc_die "cannot list images of ${repo}"
    N="$(printf '%s' "${IDS}" | jq 'length')"
    printf 'SC-LIST %s images=%s\n' "${repo}" "${N}"
    ((N > 0)) || continue
    if [[ "${DELETE_ECR}" != 1 ]]; then
      if [[ "${EMPTY}" == 1 ]]; then printf 'SC-DRYRUN would delete %s images from %s (images only; the repository stays)\n' "${N}" "${repo}"; fi
      continue
    fi
    sc_info "emptying ${repo}: ${N} images (human gate file: $(basename "${GATE}"))"
    while IFS= read -r chunk; do
      FAILS="$(sc_aws ecr batch-delete-image --repository-name "${repo}" --image-ids "${chunk}" --query 'length(failures)' --output text)" || sc_die "batch-delete-image failed for ${repo}"
      [[ "${FAILS}" == 0 ]] || sc_die "batch-delete-image reported ${FAILS} failures for ${repo}"
    done < <(printf '%s' "${IDS}" | jq -c '[range(0; length; 100) as $i | .[$i:$i+100]] | .[]')
    sc_ok "${repo} emptied"
  done
  if [[ "${EMPTY}" != 1 ]]; then sc_info "listing only. To empty the repositories add: --empty-ecr --confirm-empty-ecr --gate-file <human decision file>"; fi
fi

sc_step 6 "${TOTAL}" "inventory after"
inventory after

sc_step 7 "${TOTAL}" "commands for a HUMAN to run (printed, not executed)"
printf '%s\n' \
  'SC-HUMAN-COMMAND A. record a human decision in docs/agent-runs/<phase>/human-gate.md, then archive evidence (bash scripts/archive-evidence.sh --to-s3)' \
  'SC-HUMAN-COMMAND B. plan the protection switches off (read-only):' \
  "  AWS_PROFILE=scholarchain terraform -chdir=${ENV_REL} plan -input=false -out=../../plans/tfplan-cleanup-flags.tfplan \\" \
  '    -var neptune_deletion_protection=false -var dynamodb_deletion_protection=false \' \
  '    -var force_destroy_intermediate=true -var vector_force_destroy=true' \
  'SC-HUMAN-COMMAND C. after reviewing that plan, apply exactly that saved plan (this turns protections off, it removes nothing):' \
  "  AWS_PROFILE=scholarchain terraform -chdir=${ENV_REL} apply ../../plans/tfplan-cleanup-flags.tfplan" \
  'SC-HUMAN-COMMAND D. EVIDENCE PRESERVATION (human-only state change). Before any teardown, drop the raw and reports buckets AND all of their' \
  '  Terraform sub-resources from the local state, so teardown cannot touch the buckets or strip their settings. Preview first (changes nothing),' \
  '  then run the same list without -dry-run:'
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
printf '%s\n' \
  'SC-HUMAN-COMMAND E. preview the removal (read-only), then run it (it now leaves raw and reports untouched):' \
  "  AWS_PROFILE=scholarchain terraform -chdir=${ENV_REL} plan -destroy -input=false \\" \
  '    -var neptune_deletion_protection=false -var dynamodb_deletion_protection=false \' \
  '    -var force_destroy_intermediate=true -var vector_force_destroy=true' \
  "  AWS_PROFILE=scholarchain terraform -chdir=${ENV_REL} destroy \\" \
  '    -var neptune_deletion_protection=false -var dynamodb_deletion_protection=false \' \
  '    -var force_destroy_intermediate=true -var vector_force_destroy=true' \
  'SC-HUMAN-NOTE ORDER MATTERS: empty ECR (this script, with its confirmations), then B and C (protection switches, applied while the evidence buckets are still in state), then D, then E.' \
  'SC-HUMAN-NOTE Resulting posture: raw and reports buckets stay in AWS intact with their settings (public-access block, TLS-only policy, versioning, SSE, lifecycle),' \
  '  but Terraform no longer tracks them. A later plan with the same configuration would try to create them and fail on the name, so either' \
  '  re-import the seven addresses per bucket (terraform import) or remove them from the configuration. Their removal is a separate human data-destruction decision.' \
  'SC-HUMAN-NOTE If the state file is lost or replaced before step D, run nothing from E: the buckets would be unprotected again.' \
  'SC-HUMAN-NOTE Neptune: skip_final_snapshot defaults to true (graph is rebuilt from S3). Set -var neptune_skip_final_snapshot=false to keep a final snapshot.' \
  'SC-HUMAN-NOTE Cheaper partial stop without removing data: -var create_neptune=false and/or -var create_alb=false (needs the protection switches above for Neptune).' \
  'SC-HUMAN-NOTE G12-4: the four secrets have a 7-day recovery window. After removal they stay scheduled for deletion for 7 days, and a new apply within those 7 days FAILS on the secret names.' \
  'SC-HUMAN-NOTE If the stack will be applied again within 7 days, a human may force-delete each secret AFTER the removal (data destruction: a human decision in human-gate.md first). Printed, never run:'
if [[ "${AWS_OK}" == 1 ]]; then
  while IFS= read -r arn; do
    nm="${arn##*:secret:}"
    nm="${nm%-??????}"
    printf '  aws secretsmanager delete-secret --force-delete-without-recovery --secret-id %s\n' "${nm}"
  done < <(printf '%s' "${TF_OUT}" | jq -r '.secret_arns.value | to_entries[] | .value')
else
  for nm in scholarchain/voyage-api-key SCHOLARCHAIN_ANTHROPIC_API_KEY SCHOLARCHAIN_SERVICES_API_KEY SCHOLARCHAIN_INTERNAL_API_KEY; do
    printf '  aws secretsmanager delete-secret --force-delete-without-recovery --secret-id %s\n' "${nm}"
  done
fi
sc_info "If the environment directory was not initialized on this machine, run terraform init there first (state list needs it)."
sc_ok "cleanup listing complete; nothing was destroyed (ecr_images_deleted=${DELETE_ECR})"
