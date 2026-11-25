#!/usr/bin/env bash
# Image naming for package.sh and deploy.sh. Source this file; do not execute it. Owner: infra-engineer.
#
# Merge item M13-1 (resolved in Phase 14 Part A): main's build.sbt builds THREE images from the services module with
# `SCHOLARCHAIN_GIT_SHA=<tag> sbt dockerLocal` (alias for services/servicesDockerBuild), each tagged scholarchain/<service>:<tag> where
# <tag> is exactly the value of SCHOLARCHAIN_GIT_SHA (7 to 40 hex characters, optional -dirty suffix; never "latest"). The services are
# query-api, retrieval and embedding-worker, the same names as the Terraform ECR repositories. package.sh does NOT re-tag anything: it
# only inspects the images the build produced. There are no image-source overrides any more (SC_IMAGE_SOURCE_* and SC_LOCAL_IMAGE are
# refused by package.sh).

# sc_local_tag <service> <tag>: the local name main's build gives the image of a service, and deploy.sh pushes.
sc_local_tag() { printf 'scholarchain/%s:%s\n' "$1" "$2"; }

# sc_release_tag <repo-root>: the image tag for a clean commit (12 hex characters of the commit). It is passed to the build as
# SCHOLARCHAIN_GIT_SHA.
sc_release_tag() { git -C "$1" rev-parse --short=12 HEAD; }

# sc_removed_image_overrides: prints the name of every removed image-source override variable that is set (one per line).
sc_removed_image_overrides() {
  local svc var
  for svc in "${SC_SERVICES[@]}"; do
    var="SC_IMAGE_SOURCE_$(printf '%s' "${svc}" | tr 'a-z-' 'A-Z_')"
    [[ -z "${!var:-}" ]] || printf '%s\n' "${var}"
  done
  [[ -z "${SC_LOCAL_IMAGE:-}" ]] || printf 'SC_LOCAL_IMAGE\n'
  return 0
}
