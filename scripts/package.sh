#!/usr/bin/env bash
# scripts/package.sh [options]
#
# Builds the Hadoop and Spark assemblies and the THREE service images (query-api, retrieval, embedding-worker) exactly as main's build
# defines them, and records artifact checksums and image IDs. It makes no AWS call and pushes nothing (deploy.sh pushes).
# Images: `SCHOLARCHAIN_GIT_SHA=<tag> sbt dockerLocal` (build.sbt servicesDockerBuild) builds scholarchain/<service>:<tag> for all three
# services, where <tag> = the 12-character commit (plus -dirty on a local run). package.sh re-tags nothing; it inspects the three images
# the build produced, checks each carries the revision label equal to <tag>, and records each image ID in artifacts.json. A running sbt
# server keeps its own environment (build.sbt), so the script exports SCHOLARCHAIN_GIT_SHA before the first sbt call and runs `sbt shutdown`
# first. Assembly jars are read from target/out/jvm/scala-*/scholarchain-<mod>/ (sbt 2 layout); exactly one per module or exit 1.
#
# RELEASE RUN (default): clean tree required (exit 3), fixed build commands (`sbt packageJobs`, `sbt dockerLocal`), jars and images are
# freshly built, record written to reports/release/<sha>/artifacts.json with release:true, dirty:false.
# LOCAL RUN (--allow-dirty, local testing only): the tag ends in -dirty, the record says dirty:true and release:false and goes to
# reports/release-local/<sha>/artifacts.json, a path deploy.sh never reads, so a release record is never overwritten.
# --skip-build uses images built earlier under the -dirty tag, so it is REFUSED (exit 3) unless --allow-dirty is also given (review M2).
# SC_IMAGE_SOURCE_* and SC_LOCAL_IMAGE (single-image re-tagging, removed in Phase 14) are refused with exit 2.
#
# Environment (names only; none is secret):
#   SC_BUILD_TIMEOUT       seconds per build command (default 3000)
#   SC_JOBS_BUILD_CMD and SC_IMAGE_BUILD_CMD are NO LONGER SUPPORTED (free-form commands): setting either is refused (exit 2).
# Options:
#   --allow-dirty (local run)   --skip-build (use already built jars and images; local run only)   --dry-run   --help
# Exit codes: 0 ok, 1 failure, 2 usage, 3 refused (dirty tree, or a local-only override without --allow-dirty).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
# shellcheck source=lib/images.sh
source "${SCRIPT_DIR}/lib/images.sh"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() { sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | grep -v '^# shellcheck' | sed 's/^# \{0,1\}//'; }

ALLOW_DIRTY=0 SKIP_BUILD=0
while (($#)); do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --dry-run) SC_DRY_RUN=1 ;;
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

if [[ -n "${SC_JOBS_BUILD_CMD:-}${SC_IMAGE_BUILD_CMD:-}" ]]; then
  echo "SC_JOBS_BUILD_CMD and SC_IMAGE_BUILD_CMD are no longer supported: the build commands are fixed (sbt packageJobs, sbt dockerLocal)" >&2
  exit 2
fi
REMOVED="$(sc_removed_image_overrides | tr '\n' ' ')"
if [[ -n "${REMOVED// /}" ]]; then
  printf 'SC-ERROR %sare no longer supported: package.sh builds all three service images with sbt dockerLocal and re-tags nothing\n' "${REMOVED}" >&2
  exit 2
fi
OVERRIDES=""
[[ "${SKIP_BUILD}" != 1 ]] || OVERRIDES="--skip-build"
if [[ -n "${OVERRIDES}" && "${ALLOW_DIRTY}" != 1 ]]; then
  printf 'SC-ERROR REFUSED: %s can record an unverified earlier build as this commit; it is for local testing only: add --allow-dirty (the record is then release:false)\n' "${OVERRIDES}" >&2
  exit 3
fi

sc_stage preflight
sc_require_cmd git jq docker
[[ "${SKIP_BUILD}" == 1 ]] || sc_require_cmd sbt
SHA="$(git -C "${ROOT}" rev-parse HEAD)"
if sc_tree_dirty "${ROOT}" || [[ "${ALLOW_DIRTY}" == 1 ]]; then
  DIRTY=true
  if [[ "${ALLOW_DIRTY}" != 1 ]]; then
    printf 'SC-ERROR working tree is dirty: commit first, or pass --allow-dirty for local testing (refused)\n' >&2
    exit 3
  fi
  TAG="$(sc_release_tag "${ROOT}")-dirty"
  sc_warn "LOCAL RUN (--allow-dirty): images are tagged ${TAG}, the record is release:false under reports/release-local/ and deploy.sh refuses it"
else
  DIRTY=false
  TAG="$(sc_release_tag "${ROOT}")"
fi
if [[ "${DIRTY}" == true ]]; then RELEASE=false REC_DIR="reports/release-local"; else RELEASE=true REC_DIR="reports/release"; fi
sc_info "commit=${SHA} image_tag=${TAG} dirty=${DIRTY}"

sc_stage build-jobs
# F14-1: SCHOLARCHAIN_GIT_SHA (the image tag, build.sbt servicesDockerBuild: 7 to 40 hex, optional -dirty) is exported BEFORE the first sbt
# call, so every sbt call sees it. The sbt 2 launcher is a thin client that attaches to a background server, and a server keeps the
# environment of the process that started it (build.sbt refuses otherwise). A server that was already running before this script started
# may hold a different or absent value, so `sbt shutdown` runs first: the next call then starts a fresh server from this environment.
# (`sbt --help` confirms no per-call environment option; `shutdown` is the sbt command that stops the server. It exits 0 when no server is running; a failure stops the script.)
export SCHOLARCHAIN_GIT_SHA="${TAG}"
JOBS_CMD=(sbt packageJobs)
IMG_CMD=(sbt dockerLocal)
if [[ "${SKIP_BUILD}" == 1 ]]; then
  sc_info "skipped (--skip-build)"
else
  (cd "${ROOT}" && sc_mut sc_timeout 300 sbt shutdown) || sc_die "sbt shutdown failed: a running sbt server may hold another SCHOLARCHAIN_GIT_SHA; stop it and rerun"
  (cd "${ROOT}" && sc_mut sc_timeout "${SC_BUILD_TIMEOUT:-3000}" "${JOBS_CMD[@]}")
fi

sc_stage build-images
if [[ "${SKIP_BUILD}" == 1 ]]; then sc_info "skipped (--skip-build)"; else (cd "${ROOT}" && sc_mut sc_timeout "${SC_BUILD_TIMEOUT:-3000}" "${IMG_CMD[@]}"); fi

sc_stage record-artifacts
JARS='[]'
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf 'SC-DRYRUN would locate the hadoop and spark assembly jars under target/out/jvm/scala-*/, checksum them, inspect the three images built by sbt dockerLocal and write %s/%s/artifacts.json\n' "${REC_DIR}" "${SHA}"
  for svc in "${SC_SERVICES[@]}"; do
    printf 'SC-DRYRUN docker image inspect %s (built by sbt dockerLocal; no re-tagging)\n' "$(sc_local_tag "${svc}" "${TAG}")"
  done
  exit 0
fi
# F14-2: sbt 2 writes assemblies to <repo>/target/out/jvm/scala-<ver>/scholarchain-<mod>/scholarchain-<mod>-<version>-assembly.jar. A jar in a
# second scala-* directory (stale build) counts toward "more than one" and fails; nothing is picked silently. The old
# modules/<mod>/target layout is not searched.
for mod in hadoop spark; do
  shopt -s nullglob
  found=("${ROOT}/target/out/jvm"/scala-*/"scholarchain-${mod}"/*-assembly.jar)
  shopt -u nullglob
  ((${#found[@]} == 1)) || sc_die "expected exactly one assembly jar under target/out/jvm/scala-*/scholarchain-${mod}/, found ${#found[@]}"
  [[ -f "${found[0]}" ]] || sc_die "assembly jar is not a regular file: ${found[0]#"${ROOT}/"}"
  JARS="$(printf '%s' "${JARS}" | jq --arg m "${mod}" --arg p "${found[0]#"${ROOT}/"}" --arg h "$(sc_sha256 "${found[0]}")" --argjson b "$(wc -c < "${found[0]}" | tr -d ' ')" '. + [{module: $m, path: $p, sha256: $h, bytes: $b}]')"
done
IMAGES='[]'
for svc in "${SC_SERVICES[@]}"; do
  local_ref="$(sc_local_tag "${svc}" "${TAG}")"
  id="$(docker image inspect --format '{{.Id}}' "${local_ref}" 2> /dev/null)" || sc_die "sbt dockerLocal did not produce ${local_ref} (all three images must exist)"
  [[ "${id}" =~ ^sha256:[0-9a-f]{64}$ ]] || sc_die "unexpected image id for ${local_ref}"
  rev="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "${local_ref}" 2> /dev/null)" || sc_die "cannot read the revision label of ${local_ref}"
  [[ "${rev}" == "${TAG}" ]] || sc_die "${local_ref} carries revision label '${rev}', expected '${TAG}' (stale image: rebuild)"
  IMAGES="$(printf '%s' "${IMAGES}" | jq --arg s "${svc}" --arg t "${TAG}" --arg r "${local_ref}" --arg id "${id}" '. + [{service: $s, tag: $t, local_ref: $r, built_by: "sbt dockerLocal", image_id: $id}]')"
  sc_ok "${svc}: ${local_ref} (${id})"
done
OUT="$(jq -n --arg sha "${SHA}" --arg tag "${TAG}" --argjson dirty "${DIRTY}" --arg at "$(sc_utc_iso)" \
  --arg jobs "${JOBS_CMD[*]}" --arg img "SCHOLARCHAIN_GIT_SHA=${TAG} ${IMG_CMD[*]}" --argjson release "${RELEASE}" --argjson skip "$([[ "${SKIP_BUILD}" == 1 ]] && echo true || echo false)" \
  --arg overrides "${OVERRIDES}" --argjson jars "${JARS}" --argjson images "${IMAGES}" \
  '{commit: $sha, image_tag: $tag, dirty: $dirty, release: $release, packaged_at: $at, build_commands: {jobs: $jobs, images: $img}, skip_build: $skip,
    local_overrides: ($overrides | split(" ") | map(select(. != ""))), jars: $jars, images: $images,
    note: "Three images built by sbt dockerLocal (SCHOLARCHAIN_GIT_SHA = image_tag); nothing re-tagged. image_id is the local image config digest; the registry digest is recorded by deploy.sh."}')"
sc_json_write "${ROOT}/${REC_DIR}/${SHA}/artifacts.json" "${OUT}"
sc_ok "${REC_DIR}/${SHA}/artifacts.json written (release=${RELEASE})"
