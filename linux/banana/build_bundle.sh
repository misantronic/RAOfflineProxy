#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
BUILD_DIR="${DIST_DIR}/RAOfflineProxy-BananaOS"
APP_DIR="${BUILD_DIR}/raofflineproxy/app"
LIB_DIR="${BUILD_DIR}/raofflineproxy/lib"
VERSION="${RAOFFLINEPROXY_APP_VERSION:-3.1.0-alpha1}"
ARCHIVE="${DIST_DIR}/RAOfflineProxy-BananaOS-v${VERSION}.zip"
VENDOR_DIR="${LINUX_DIR}/rocknix/vendor"

if [ ! -d "${VENDOR_DIR}/pygame" ] || [ ! -d "${VENDOR_DIR}/pygame_ce.libs" ]; then
  echo "Missing vendored pygame-ce. Run linux/rocknix/fetch_vendor.sh first." >&2
  exit 1
fi

ZIG_BIN="${ZIG_BIN:-$(command -v zig)}" \
TARGET="aarch64-linux-gnu.2.17" OUT_DIR="${SCRIPT_DIR}/native" \
  "${LINUX_DIR}/build_rchash.sh"

rm -rf "${BUILD_DIR}"
rm -f "${ARCHIVE}"
mkdir -p "${APP_DIR}" "${LIB_DIR}" "${BUILD_DIR}/images"

export COPYFILE_DISABLE=1
cp -r "${LINUX_DIR}/raofflineproxy" "${APP_DIR}/raofflineproxy"
cp "${LINUX_DIR}/requirements.txt" "${APP_DIR}/requirements.txt"
cp "${LINUX_DIR}/../docs/public/logo-320.png" "${APP_DIR}/raofflineproxy/logo-320.png"
cp -R "${VENDOR_DIR}/pygame/." "${APP_DIR}/pygame/"
cp -R "${VENDOR_DIR}/pygame_ce.libs/." "${APP_DIR}/pygame_ce.libs/"
cp "${SCRIPT_DIR}/gamecontrollerdb.txt" "${BUILD_DIR}/raofflineproxy/gamecontrollerdb.txt"
cp "${SCRIPT_DIR}/RAOfflineProxy.sh" "${BUILD_DIR}/RAOfflineProxy.sh"
cp "${SCRIPT_DIR}/RAOfflineProxy-Diagnose.sh" "${BUILD_DIR}/RAOfflineProxy Diagnose.sh"
cp "${SCRIPT_DIR}/README.md" "${BUILD_DIR}/README.md"
cp "${LINUX_DIR}/../docs/public/logo-320.png" "${BUILD_DIR}/images/RAOfflineProxy.png"
cp "${SCRIPT_DIR}/launch.sh" "${BUILD_DIR}/raofflineproxy/launch.sh"
cp "${SCRIPT_DIR}/native/libraproxy_rchash.so" "${LIB_DIR}/libraproxy_rchash.so"

find "${APP_DIR}/raofflineproxy" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}/raofflineproxy" -name "*.pyc" -delete
find "${APP_DIR}/pygame" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}/pygame" -name "*.pyc" -delete

chmod +x "${BUILD_DIR}/RAOfflineProxy.sh"
chmod +x "${BUILD_DIR}/RAOfflineProxy Diagnose.sh"
chmod +x "${BUILD_DIR}/raofflineproxy/launch.sh"

mkdir -p "${DIST_DIR}"
(
  cd "${BUILD_DIR}"
  zip -qry "${ARCHIVE}" .
)
rm -rf "${BUILD_DIR}"

echo "Created ${ARCHIVE}"
