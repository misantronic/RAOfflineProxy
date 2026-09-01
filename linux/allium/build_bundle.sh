#!/usr/bin/env bash
set -euo pipefail

# Allium bundle. Allium runs on the same Miyoo Mini armv7 hardware as Onion/spruce, so this
# package reuses Onion's CPython runtime, its pygame + "Mini" SDL2 vendor libraries and its
# armv7 libraproxy_rchash.so rather than duplicating them here.
# Run linux/onion/fetch_runtime.sh and linux/onion/fetch_vendor.sh once if they're missing.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ONION_DIR="${LINUX_DIR}/onion"
DIST_DIR="${SCRIPT_DIR}/dist"
BUILD_DIR="${DIST_DIR}/raofflineproxy-allium-app"
APP_DIR="${BUILD_DIR}/Apps/RAOfflineProxy.pak"
LIB_DIR="${APP_DIR}/lib"
RUNTIME_CACHE_DIR="${ONION_DIR}/runtime-cache"
RUNTIME_ARCHIVE_NAME="cpython-3.9.20+20241016-armv7-unknown-linux-gnueabihf-install_only_stripped.tar.gz"
RUNTIME_ARCHIVE_PATH="${RUNTIME_CACHE_DIR}/${RUNTIME_ARCHIVE_NAME}"
VENDOR_DIR="${ONION_DIR}/vendor"
APP_VERSION="${RAOFFLINEPROXY_APP_VERSION:-1.13.0-alpha1}"
ZIP_NAME="RAOfflineProxy-Allium-v${APP_VERSION}.zip"

TARGET="arm-linux-gnueabihf.2.17" OUT_DIR="${SCRIPT_DIR}/native" \
  "${LINUX_DIR}/build_rchash.sh"

rm -rf "${BUILD_DIR}"
rm -f "${DIST_DIR}/${ZIP_NAME}"

mkdir -p "${APP_DIR}"
mkdir -p "${LIB_DIR}"

export COPYFILE_DISABLE=1

cp -R "${SCRIPT_DIR}/app/RAOfflineProxy.pak/." "${APP_DIR}/"
mkdir -p "${APP_DIR}/app"
cp -R "${LINUX_DIR}/raofflineproxy" "${APP_DIR}/app/raofflineproxy"
cp "${LINUX_DIR}/../docs/public/logo-320.png" "${APP_DIR}/app/raofflineproxy/logo-320.png"
cp "${SCRIPT_DIR}/native/libraproxy_rchash.so" "${LIB_DIR}/libraproxy_rchash.so"
cp "${LINUX_DIR}/requirements.txt" "${APP_DIR}/app/requirements.txt"
"${LINUX_DIR}/resize_icon.sh" "${LINUX_DIR}/../docs/public/logo.png" "${APP_DIR}/icon.png" 74
mkdir -p "${APP_DIR}/data"

if [ -f "${RUNTIME_ARCHIVE_PATH}" ]; then
  rm -rf "${APP_DIR}/runtime"
  mkdir -p "${APP_DIR}/runtime"
  tar -xzf "${RUNTIME_ARCHIVE_PATH}" -C "${APP_DIR}/runtime" --strip-components 1
  python3 "${ONION_DIR}/flatten_symlinks.py" "${APP_DIR}/runtime"
else
  echo "No cached runtime at ${RUNTIME_ARCHIVE_PATH} — run ./linux/onion/fetch_runtime.sh first"
fi

if [ -d "${VENDOR_DIR}/pygame" ] && [ -d "${VENDOR_DIR}/pygame.libs" ]; then
  SITE_PACKAGES="${APP_DIR}/runtime/lib/python3.9/site-packages"
  mkdir -p "${SITE_PACKAGES}"
  rm -rf "${SITE_PACKAGES}/pygame"
  cp -R "${VENDOR_DIR}/pygame" "${SITE_PACKAGES}/pygame"
  cp "${VENDOR_DIR}"/pygame.libs/* "${LIB_DIR}/"
  echo "Included pygame + SDL2 vendor libs from ${VENDOR_DIR}"
else
  echo "No vendor directory found at ${VENDOR_DIR} — run ./linux/onion/fetch_vendor.sh first (menu-sdl will be unavailable)"
fi

find "${APP_DIR}" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}" -name "*.pyc" -delete

chmod +x "${APP_DIR}/launch.sh"
chmod +x "${APP_DIR}/autostart-launch.sh"

mkdir -p "${DIST_DIR}"

BUILD_DIR="${BUILD_DIR}" ZIP_PATH="${DIST_DIR}/${ZIP_NAME}" python3 - <<'PY'
import os
import zipfile
from pathlib import Path

build_dir = Path(os.environ["BUILD_DIR"])
zip_path = Path(os.environ["ZIP_PATH"])

with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(build_dir.rglob("*")):
        if path.is_dir():
            continue
        archive.write(path, path.relative_to(build_dir))
PY

echo "Created ${BUILD_DIR}"
echo "Created ${DIST_DIR}/${ZIP_NAME}"
