#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
BUILD_DIR="${DIST_DIR}/raofflineproxy-onion-app"
APP_DIR="${BUILD_DIR}/App/RAOfflineProxy"
RUNTIME_CACHE_DIR="${SCRIPT_DIR}/runtime-cache"
RUNTIME_ARCHIVE_NAME="cpython-3.10.20+20260510-armv7-unknown-linux-gnueabihf-install_only_stripped.tar.gz"
RUNTIME_ARCHIVE_PATH="${RUNTIME_CACHE_DIR}/${RUNTIME_ARCHIVE_NAME}"
ZIP_NAME="RAOfflineProxy-Onion-v1.1.0-linux-alpha.zip"

rm -rf "${BUILD_DIR}"
rm -f "${DIST_DIR}/${ZIP_NAME}"

mkdir -p "${APP_DIR}"

export COPYFILE_DISABLE=1

cp -R "${SCRIPT_DIR}/app/RAOfflineProxy/." "${APP_DIR}/"
mkdir -p "${APP_DIR}/app"
cp -R "${LINUX_DIR}/raofflineproxy" "${APP_DIR}/app/raofflineproxy"
cp "${LINUX_DIR}/requirements.txt" "${APP_DIR}/app/requirements.txt"
sips -z 74 74 "${LINUX_DIR}/../docs/public/logo.png" --out "${APP_DIR}/icon.png" >/dev/null
mkdir -p "${APP_DIR}/data"

if [ -f "${RUNTIME_ARCHIVE_PATH}" ]; then
  rm -rf "${APP_DIR}/runtime"
  mkdir -p "${APP_DIR}/runtime"
  tar -xzf "${RUNTIME_ARCHIVE_PATH}" -C "${APP_DIR}/runtime" --strip-components 1
  python3 "${SCRIPT_DIR}/flatten_symlinks.py" "${APP_DIR}/runtime"
fi

find "${APP_DIR}" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}" -name "*.pyc" -delete

chmod +x "${APP_DIR}/launch.sh"
chmod +x "${APP_DIR}/onion-menu.sh"
chmod +x "${APP_DIR}/autostart-launch.sh"
chmod +x "${APP_DIR}/autostart-cleanup.sh"
chmod +x "${APP_DIR}/autostart-template.sh"
chmod +x "${APP_DIR}/checkoff-template.sh"

mkdir -p "${DIST_DIR}"
rm -f "${DIST_DIR}/${ZIP_NAME}"

python3 - <<'PY'
from pathlib import Path
import zipfile

dist_dir = Path(r"/Users/dschkalee/src/RAOfflineProxy/linux/onion/dist")
build_dir = dist_dir / "raofflineproxy-onion-app"
zip_path = dist_dir / "RAOfflineProxy-Onion-v1.1.0-linux-alpha.zip"

with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(build_dir.rglob("*")):
        if path.is_dir():
            continue
        archive.write(path, path.relative_to(build_dir))
PY

echo "Created ${BUILD_DIR}"
echo "Created ${DIST_DIR}/${ZIP_NAME}"
if [ -f "${RUNTIME_ARCHIVE_PATH}" ]; then
  echo "Included runtime from ${RUNTIME_ARCHIVE_PATH}"
else
  echo "No cached runtime found at ${RUNTIME_ARCHIVE_PATH}"
fi
