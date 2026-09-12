#!/usr/bin/env bash
# Downloads the aarch64 CPython runtime used by the arm64 spruce bundle.
#
# The armv7 bundle borrows Onion's cached 3.9 runtime; there is no equivalent to
# borrow for aarch64, because muOS and ROCKNIX run against their firmware's own
# Python rather than shipping one. 3.11 is picked to match the cp311 pygame wheels
# already vendored under linux/muos/vendor.
#
# Usage:
#   ./linux/spruce/fetch_runtime_arm64.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_CACHE_DIR="${SCRIPT_DIR}/runtime-cache"
RELEASE_REPO="astral-sh/python-build-standalone"
RELEASE_TAG="20241016"
ASSET_NAME="${1:-cpython-3.11.10+20241016-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz}"
ASSET_PATH="${RUNTIME_CACHE_DIR}/${ASSET_NAME}"

mkdir -p "${RUNTIME_CACHE_DIR}"

if [ -f "${ASSET_PATH}" ]; then
  echo "Runtime archive already present: ${ASSET_PATH}"
  exit 0
fi

ASSET_URL="$(
  gh api "repos/${RELEASE_REPO}/releases/tags/${RELEASE_TAG}" --jq ".assets[] | select(.name == \"${ASSET_NAME}\") | .browser_download_url"
)"

if [ -z "${ASSET_URL}" ]; then
  echo "Runtime asset not found in ${RELEASE_REPO} release ${RELEASE_TAG}: ${ASSET_NAME}" >&2
  exit 1
fi

curl -L --fail --output "${ASSET_PATH}" "${ASSET_URL}"

echo "Downloaded ${ASSET_PATH}"
echo "Run ./linux/spruce/build_bundle.sh arm64 to include it in the bundle."
