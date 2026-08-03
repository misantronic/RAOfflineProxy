#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
BUILD_DIR="${DIST_DIR}/raofflineproxy-retrodeck-component"
PYRUNTIME_DIR="${BUILD_DIR}/pyruntime"
RUNTIME_CACHE_DIR="${SCRIPT_DIR}/runtime-cache"
RUNTIME_ARCHIVE_NAME="cpython-3.11.10+20241016-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz"
RUNTIME_ARCHIVE_PATH="${RUNTIME_CACHE_DIR}/${RUNTIME_ARCHIVE_NAME}"
PAYLOAD_ZIP="${SCRIPT_DIR}/component/tmp_assets/raofflineproxy-retrodeck-payload.zip"

if [ ! -f "${RUNTIME_ARCHIVE_PATH}" ]; then
  echo "Missing cached runtime at ${RUNTIME_ARCHIVE_PATH} — run ./linux/retrodeck/fetch_runtime.sh first" >&2
  exit 1
fi

TARGET="x86_64-linux-gnu.2.28" OUT_DIR="${SCRIPT_DIR}/native" \
  "${LINUX_DIR}/build_rchash.sh"

rm -rf "${BUILD_DIR}"
mkdir -p "${PYRUNTIME_DIR}"

tar -xzf "${RUNTIME_ARCHIVE_PATH}" -C "${PYRUNTIME_DIR}" --strip-components 1
python3 "${SCRIPT_DIR}/flatten_symlinks.py" "${PYRUNTIME_DIR}"

SITE_PACKAGES="${PYRUNTIME_DIR}/lib/python3.11/site-packages"
mkdir -p "${SITE_PACKAGES}"
rm -rf "${SITE_PACKAGES}/raofflineproxy"
cp -R "${LINUX_DIR}/raofflineproxy" "${SITE_PACKAGES}/raofflineproxy"
cp "${SCRIPT_DIR}/native/libraproxy_rchash.so" "${SITE_PACKAGES}/raofflineproxy/libraproxy_rchash.so"

find "${BUILD_DIR}" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${BUILD_DIR}" -name "*.pyc" -delete

mkdir -p "$(dirname "${PAYLOAD_ZIP}")"
rm -f "${PAYLOAD_ZIP}"
(cd "${BUILD_DIR}" && zip -qr "${PAYLOAD_ZIP}" pyruntime)

# Also assemble a local, fully self-contained copy for smoke testing outside
# of RetroDECK's own build pipeline (component_recipe.json/install_components.sh
# are what wire this payload into an actual RetroDECK build).
cp "${SCRIPT_DIR}/component/component_launcher.sh" "${BUILD_DIR}/"
cp "${SCRIPT_DIR}/component/component_manifest.json" "${BUILD_DIR}/"
cp "${SCRIPT_DIR}/component/component_recipe.json" "${BUILD_DIR}/"
cp "${SCRIPT_DIR}/component/component_prepare.sh" "${BUILD_DIR}/"
cp "${SCRIPT_DIR}/component/component_update.sh" "${BUILD_DIR}/"
cp "${SCRIPT_DIR}/component/component_functions.sh" "${BUILD_DIR}/"
chmod +x "${BUILD_DIR}/component_launcher.sh"

echo "Created ${BUILD_DIR}"
echo "Created ${PAYLOAD_ZIP}"
