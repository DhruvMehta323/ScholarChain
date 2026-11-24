#!/usr/bin/env bash
# Image source mapping for package.sh and deploy.sh. Source this file; do not execute it. Owner: infra-engineer.
#
# Merge item M13-1: lane2 build.sbt defines ONE service image (sbt dockerLocal -> scholarchain-services) while Terraform has THREE
# ECR repositories and ECS services (query-api, retrieval, embedding-worker). Until the per-service images from main are merged,
# the single local image is re-tagged for each repository. Configuration, in this order of precedence, per service:
#   SC_IMAGE_SOURCE_QUERY_API / SC_IMAGE_SOURCE_RETRIEVAL / SC_IMAGE_SOURCE_EMBEDDING_WORKER   a local image reference
#   SC_LOCAL_IMAGE                                                                            one local image for all three
#   default                                                                                   newest local scholarchain-services image
# The build commands are FIXED (`sbt packageJobs`, `sbt dockerLocal`); there is no environment override (review M7).
# Review M2: every override above (and --skip-build) is for local testing only. package.sh refuses them without --allow-dirty and then
# records release:false in reports/release-local/, which deploy.sh never reads.

# sc_image_override_names: prints the name of every image-source override variable that is set and non-empty (one per line).
sc_image_override_names() {
  local svc var
  for svc in "${SC_SERVICES[@]}"; do
    var="SC_IMAGE_SOURCE_$(printf '%s' "${svc}" | tr 'a-z-' 'A-Z_')"
    [[ -z "${!var:-}" ]] || printf '%s\n' "${var}"
  done
  [[ -z "${SC_LOCAL_IMAGE:-}" ]] || printf 'SC_LOCAL_IMAGE\n'
  return 0
}

# sc_image_source <service>: prints the local image reference to use for the service. Returns 1 if none is found.
sc_image_source() {
  local svc="$1" var ref
  var="SC_IMAGE_SOURCE_$(printf '%s' "${svc}" | tr 'a-z-' 'A-Z_')"
  if [[ -n "${!var:-}" ]]; then
    printf '%s\n' "${!var}"
    return 0
  fi
  if [[ -n "${SC_LOCAL_IMAGE:-}" ]]; then
    printf '%s\n' "${SC_LOCAL_IMAGE}"
    return 0
  fi
  ref="$(docker images --format '{{.Repository}}:{{.Tag}}' scholarchain-services 2> /dev/null | head -n 1)"
  [[ -n "${ref}" ]] || return 1
  printf '%s\n' "${ref}"
}

# sc_local_tag <service> <tag>: the local name package.sh gives the image of a service, and deploy.sh pushes.
sc_local_tag() { printf 'scholarchain/%s:%s\n' "$1" "$2"; }

# sc_release_tag <repo-root>: the image tag for a clean commit (12 hex characters of the commit).
sc_release_tag() { git -C "$1" rev-parse --short=12 HEAD; }
