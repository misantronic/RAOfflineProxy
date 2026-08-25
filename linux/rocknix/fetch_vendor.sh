#!/usr/bin/env bash
# Downloads the ROCKNIX pygame vendor directory: prebuilt pygame-ce aarch64
# manylinux wheels, one per CPython ABI a ROCKNIX build may ship.
#
# ROCKNIX has no pip and no pygame, so the bundle carries its own. The wheels'
# extension modules are ABI-tagged (base.cpython-313-... vs
# base.cpython-314-...), so several ABIs live side by side in one pygame
# directory: each interpreter only imports the .so carrying its own tag. The
# pure-Python files and the pygame_ce.libs payload are byte-identical across
# ABIs of the same release, so the merge costs only the extra .so files.
#
# Usage:
#   linux/rocknix/fetch_vendor.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENDOR_DIR="${SCRIPT_DIR}/vendor"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

PYGAME_CE_VERSION="2.5.7"
# Keep in sync with build_bundle.sh's REQUIRED_PY_VERSIONS.
PY_VERSIONS=(313 314)

rm -rf "${VENDOR_DIR}/pygame" "${VENDOR_DIR}/pygame_ce.libs"
mkdir -p "${VENDOR_DIR}"

for py_version in "${PY_VERSIONS[@]}"; do
  abi="cp${py_version}"
  echo "Downloading pygame-ce ${PYGAME_CE_VERSION} ${abi} aarch64..."
  python3 -m pip download "pygame-ce==${PYGAME_CE_VERSION}" \
    --platform manylinux_2_17_aarch64 --python-version "${py_version}" \
    --implementation cp --abi "${abi}" --only-binary=:all: --no-deps \
    -d "${TMP_DIR}" >/dev/null

  # Each .so's RPATH is $ORIGIN/../pygame_ce.libs, so both directories have to
  # land next to each other under vendor/ under those exact names.
  unzip -oq "${TMP_DIR}/pygame_ce-${PYGAME_CE_VERSION}-${abi}-${abi}-"*aarch64.whl \
    "pygame/*" "pygame_ce.libs/*" -d "${VENDOR_DIR}"
done

find "${VENDOR_DIR}/pygame" -name "__pycache__" -type d -prune -exec rm -rf {} +

echo ""
for py_version in "${PY_VERSIONS[@]}"; do
  count="$(find "${VENDOR_DIR}/pygame" -name "*.cpython-${py_version}-aarch64-linux-gnu.so" | wc -l | tr -d ' ')"
  echo "vendor/pygame contains ${count} cpython-${py_version} extension modules."
done
