#!/usr/bin/env bash
# scripts/package.sh [options]
#
# Builds the Hadoop and Spark assemblies and the service images, tags every image with the Git commit, and records artifact
# checksums and image IDs. It makes no AWS call and pushes nothing (deploy.sh pushes).
#
# RELEASE RUN (default): clean tree required (exit 3), fixed build commands (`sbt packageJobs`, `sbt dockerLocal`), jars and images are
# freshly built, record written to reports/release/<sha>/artifacts.json with release:true, dirty:false.
# LOCAL RUN (--allow-dirty, local testing only): the tag ends in -dirty, the record says dirty:true and release:false and goes to
# reports/release-local/<sha>/artifacts.json, a path deploy.sh never reads, so a release record is never overwritten.
# --skip-build, SC_LOCAL_IMAGE and SC_IMAGE_SOURCE_* can re-tag an arbitrary earlier build as this commit, so they are REFUSED
# (exit 3) unless --allow-dirty is also given (review M2).
#
# Environment (names only; none is secret):
#   SC_IMAGE_SOURCE_QUERY_API, SC_IMAGE_SOURCE_RETRIEVAL, SC_IMAGE_SOURCE_EMBEDDING_WORKER, SC_LOCAL_IMAGE   local runs only; see scripts/lib/images.sh
#                          Merge item M13-1: build.sbt has one image, Terraform has three repositories; by default the one local
#                          image is re-tagged scholarchain/<service>:<sha> for each service.
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
OVERRIDES="$(sc_image_override_names | tr '\n' ' ')"
[[ "${SKIP_BUILD}" != 1 ]] || OVERRIDES="--skip-build ${OVERRIDES}"
if [[ -n "${OVERRIDES// /}" && "${ALLOW_DIRTY}" != 1 ]]; then
  printf 'SC-ERROR REFUSED: %s can re-tag an unverified earlier build as this commit; they are for local testing only: add --allow-dirty (the record is then release:false)\n' "${OVERRIDES}" >&2
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
JOBS_CMD=(sbt packageJobs)
if [[ "${SKIP_BUILD}" == 1 ]]; then sc_info "skipped (--skip-build)"; else (cd "${ROOT}" && sc_mut sc_timeout "${SC_BUILD_TIMEOUT:-3000}" "${JOBS_CMD[@]}"); fi

sc_stage build-images
IMG_CMD=(sbt dockerLocal)
if [[ "${SKIP_BUILD}" == 1 ]]; then sc_info "skipped (--skip-build)"; else (cd "${ROOT}" && sc_mut sc_timeout "${SC_BUILD_TIMEOUT:-3000}" "${IMG_CMD[@]}"); fi

sc_stage record-artifacts
JARS='[]'
if [[ "${SC_DRY_RUN}" == 1 ]]; then
  printf 'SC-DRYRUN would locate modules/hadoop and modules/spark assembly jars, checksum them, re-tag the service image(s) and write %s/%s/artifacts.json\n' "${REC_DIR}" "${SHA}"
  for svc in "${SC_SERVICES[@]}"; do
    printf 'SC-DRYRUN docker tag <source image of %s> %s\n' "${svc}" "$(sc_local_tag "${svc}" "${TAG}")"
  done
  exit 0
fi
for mod in hadoop spark; do
  found=()
  while IFS= read -r f; do found+=("${f}"); done < <(find "${ROOT}/modules/${mod}/target" -maxdepth 3 -name '*-assembly.jar' -type f 2> /dev/null | LC_ALL=C sort)
  ((${#found[@]} == 1)) || sc_die "expected exactly one assembly jar under modules/${mod}/target, found ${#found[@]}"
  JARS="$(printf '%s' "${JARS}" | jq --arg m "${mod}" --arg p "${found[0]#"${ROOT}/"}" --arg h "$(sc_sha256 "${found[0]}")" --argjson b "$(wc -c < "${found[0]}" | tr -d ' ')" '. + [{module: $m, path: $p, sha256: $h, bytes: $b}]')"
done
IMAGES='[]'
for svc in "${SC_SERVICES[@]}"; do
  src="$(sc_image_source "${svc}")" || sc_die "no local image for ${svc}: set SC_IMAGE_SOURCE_* or SC_LOCAL_IMAGE (merge item M13-1)"
  local_ref="$(sc_local_tag "${svc}" "${TAG}")"
  docker tag "${src}" "${local_ref}"
  id="$(docker image inspect --format '{{.Id}}' "${local_ref}")" || sc_die "cannot inspect ${local_ref}"
  IMAGES="$(printf '%s' "${IMAGES}" | jq --arg s "${svc}" --arg t "${TAG}" --arg r "${local_ref}" --arg src "${src}" --arg id "${id}" '. + [{service: $s, tag: $t, local_ref: $r, source_ref: $src, image_id: $id}]')"
  sc_ok "${svc}: ${local_ref} (source ${src})"
done
OUT="$(jq -n --arg sha "${SHA}" --arg tag "${TAG}" --argjson dirty "${DIRTY}" --arg at "$(sc_utc_iso)" \
  --arg jobs "${JOBS_CMD[*]}" --arg img "${IMG_CMD[*]}" --argjson release "${RELEASE}" --argjson skip "$([[ "${SKIP_BUILD}" == 1 ]] && echo true || echo false)" \
  --arg overrides "${OVERRIDES}" --argjson jars "${JARS}" --argjson images "${IMAGES}" \
  '{commit: $sha, image_tag: $tag, dirty: $dirty, release: $release, packaged_at: $at, build_commands: {jobs: $jobs, images: $img}, skip_build: $skip,
    local_overrides: ($overrides | split(" ") | map(select(. != ""))), jars: $jars, images: $images,
    note: "Merge item M13-1: one local image re-tagged for each service unless SC_IMAGE_SOURCE_* is set (local runs only)."}')"
sc_json_write "${ROOT}/${REC_DIR}/${SHA}/artifacts.json" "${OUT}"
sc_ok "${REC_DIR}/${SHA}/artifacts.json written (release=${RELEASE})"
