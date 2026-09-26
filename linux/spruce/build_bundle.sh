#!/usr/bin/env bash
set -euo pipefail

# spruceOS bundle: a background service with no UI. spruce starts and stops it
# and picks games to cache, so no interpreter, pygame or SDL2 is shipped - only
# stdlib Python, which spruce provides, plus both arches of the hashing lib.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
BUILD_DIR="${DIST_DIR}/raofflineproxy-spruce-app"
APP_DIR="${BUILD_DIR}/App/RAOfflineProxy"
APP_VERSION="${RAOFFLINEPROXY_APP_VERSION:-1.13.0-alpha1}"
ZIP_NAME="RAOfflineProxy-Spruce-v${APP_VERSION}.zip"

TARGET="arm-linux-gnueabihf.2.17" OUT_DIR="${SCRIPT_DIR}/native/armv7" \
  "${LINUX_DIR}/build_rchash.sh"
TARGET="aarch64-linux-gnu.2.17" OUT_DIR="${SCRIPT_DIR}/native/aarch64" \
  "${LINUX_DIR}/build_rchash.sh"

rm -rf "${BUILD_DIR}"
rm -f "${DIST_DIR}/${ZIP_NAME}"

mkdir -p "${APP_DIR}/app" "${APP_DIR}/data"

export COPYFILE_DISABLE=1

cp "${SCRIPT_DIR}/app/RAOfflineProxy/common.sh" "${APP_DIR}/common.sh"
cp -R "${LINUX_DIR}/raofflineproxy" "${APP_DIR}/app/raofflineproxy"
cp "${LINUX_DIR}/requirements.txt" "${APP_DIR}/app/requirements.txt"

for arch in armv7 aarch64; do
  mkdir -p "${APP_DIR}/lib/${arch}"
  cp "${SCRIPT_DIR}/native/${arch}/libraproxy_rchash.so" "${APP_DIR}/lib/${arch}/libraproxy_rchash.so"
done

find "${APP_DIR}" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}" -name "*.pyc" -delete

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

echo "Created ${DIST_DIR}/${ZIP_NAME}"
echo "Files: $(find "${BUILD_DIR}" -type f | wc -l)"
