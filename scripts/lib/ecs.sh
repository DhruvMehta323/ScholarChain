#!/usr/bin/env bash
# ECS helpers shared by deploy.sh and rollback.sh. Source this file; do not execute it. Owner: infra-engineer.
# Requires lib/common.sh to be sourced first. Reads are retried (idempotent); nothing here creates or removes a resource.

# sc_wait_service <cluster> <service> <task-definition-arn> <desired> <timeout-seconds>
# Returns 0 when the service has one deployment on that task definition, rolloutState COMPLETED and runningCount == desired.
# Returns 5 on timeout.
sc_wait_service() {
  local cluster="$1" svc="$2" td="$3" want="$4" timeout="$5" start=$SECONDS st
  while :; do
    st="$(sc_retry sc_aws ecs describe-services --cluster "${cluster}" --services "${svc}" \
      --query 'services[0].{n:length(deployments),r:deployments[0].rolloutState,run:runningCount,td:deployments[0].taskDefinition}' --output json)" || return 1
    if [[ "$(printf '%s' "${st}" | jq -r --arg td "${td}" --argjson w "${want}" '(.n == 1 and .td == $td and .run == $w and (.r == "COMPLETED" or .r == null))')" == true ]]; then
      return 0
    fi
    if ((SECONDS - start >= timeout)); then return 5; fi
    sleep "${SC_POLL_SECONDS:-10}"
  done
}

# sc_verify_service <cluster> <service> <task-definition-arn> [expected-image-digest]
# Verifies the service runs that task definition and, when tasks are running and a digest is given, that every running task's image
# digest equals it. Prints the running task digests as a JSON array on stdout (and nothing else). Returns 1 on any mismatch.
sc_verify_service() {
  local cluster="$1" svc="$2" td="$3" want_digest="${4:-}" cur tasks digests='[]'
  cur="$(sc_retry sc_aws ecs describe-services --cluster "${cluster}" --services "${svc}" --query 'services[0].taskDefinition' --output text)" || return 1
  [[ "${cur}" == "${td}" ]] || { sc_warn "${svc} runs ${cur##*/}, expected ${td##*/}"; return 1; }
  tasks="$(sc_retry sc_aws ecs list-tasks --cluster "${cluster}" --service-name "${svc}" --desired-status RUNNING --query 'taskArns' --output json)" || return 1
  if [[ "$(printf '%s' "${tasks}" | jq 'length')" -gt 0 ]]; then
    local arns
    arns="$(printf '%s' "${tasks}" | jq -r 'join(" ")')"
    # shellcheck disable=SC2086
    digests="$(sc_retry sc_aws ecs describe-tasks --cluster "${cluster}" --tasks ${arns} --query 'tasks[].containers[].imageDigest' --output json)" || return 1
    if [[ -n "${want_digest}" && "$(printf '%s' "${digests}" | jq --arg d "${want_digest}" 'all(. == $d)')" != true ]]; then
      sc_warn "${svc}: a running task image digest differs from the expected digest"
      return 1
    fi
  fi
  printf '%s' "${digests}"
}
