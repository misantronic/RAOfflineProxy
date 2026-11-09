#!/usr/bin/env bash
# Builds the portable Windows bundle: embeddable Python + pygame + the shared
# raofflineproxy package + raproxy_rchash.dll/zstd.dll, with Start/Stop scripts.
#
# Run fetch_vendor.sh once first. Needs zig (ZIG_BIN, default: zig on PATH).
#
# Usage:
#   linux/windows/build_bundle.sh

set -euo pipefail

# "python" first: on Windows, "python3" can be the Microsoft Store stub.
PYTHON="$(command -v python || command -v python3)"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
VERSION="${RAOFFLINEPROXY_APP_VERSION:-3.0.0-alpha1}"
DIST_DIR="${SCRIPT_DIR}/dist/RAOfflineProxy"
APP_DIR="${DIST_DIR}/app"
PYTHON_DIR="${DIST_DIR}/python"
ZIP_PATH="${SCRIPT_DIR}/dist/RAOfflineProxy-Windows-v${VERSION}.zip"

if [ ! -x "${SCRIPT_DIR}/vendor/python/python.exe" ] && [ ! -f "${SCRIPT_DIR}/vendor/python/python.exe" ]; then
  echo "Missing vendor/python: run ${SCRIPT_DIR}/fetch_vendor.sh first" >&2
  exit 1
fi

TARGET="x86_64-windows-gnu" OUT_DIR="${SCRIPT_DIR}/native" ZIG_BIN="${ZIG_BIN:-zig}" \
  "${LINUX_DIR}/build_rchash.sh"

rm -rf "${DIST_DIR}"
mkdir -p "${APP_DIR}" "${PYTHON_DIR}"

cp -R "${SCRIPT_DIR}/vendor/python/." "${PYTHON_DIR}/"
cp -r "${LINUX_DIR}/raofflineproxy" "${APP_DIR}/raofflineproxy"
cp -R "${SCRIPT_DIR}/vendor/site/." "${APP_DIR}/"
cp "${LINUX_DIR}/../docs/public/logo-320.png" "${APP_DIR}/raofflineproxy/logo-320.png"
# rom_hashing.py and native_codecs.py look next to the module first.
cp "${SCRIPT_DIR}/native/raproxy_rchash.dll" "${SCRIPT_DIR}/native/zstd.dll" "${APP_DIR}/raofflineproxy/"
# Portable layout: nothing is installed. Start/Stop scripts at the top, the app's
# data/ created next to them on first start, and portable.txt marking the folder
# (windows/paths.py, portable_root). .cmd files get CRLF line endings for cmd.exe.
for script in "Start RAOfflineProxy.cmd" "Stop RAOfflineProxy.cmd" "README.txt"; do
  sed 's/\r*$/\r/' "${SCRIPT_DIR}/${script}" > "${DIST_DIR}/${script}"
done
printf '%s\r\n' "This folder is a portable RAOfflineProxy. Keep this file: it marks the folder."   > "${DIST_DIR}/portable.txt"

# Test builds only: RAOFFLINEPROXY_TEST_CHANNEL names a testing.json that sends
# Send Logs and updates to test destinations (windows/paths.py, test_channel).
# Release and nightly builds never set it, and CI checks they carry no such file.
if [ -n "${RAOFFLINEPROXY_TEST_CHANNEL:-}" ]; then
  cp "${RAOFFLINEPROXY_TEST_CHANNEL}" "${DIST_DIR}/testing.json"
  echo "TEST BUILD: included ${RAOFFLINEPROXY_TEST_CHANNEL} as testing.json"
fi

# The embeddable Python ignores PYTHONPATH and the working directory; its ._pth
# file is the import path, so add the app directory there.
PTH_FILE="$(ls "${PYTHON_DIR}"/python3*._pth)"
printf '%s\n' "../app" >> "${PTH_FILE}"

find "${APP_DIR}" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}" -name "*.pyc" -delete
# The bundled fonts are only used on Onion-family devices; Windows uses a system font.
find "${APP_DIR}" -name "font-mono*.ttf" -delete

printf '%s\n' "Created ${DIST_DIR}"

rm -f "${ZIP_PATH}"
if command -v zip >/dev/null 2>&1; then
  (cd "${SCRIPT_DIR}/dist" && zip -qr "${ZIP_PATH}" "RAOfflineProxy")
else
  # Git Bash on Windows ships no zip.
  (cd "${SCRIPT_DIR}/dist" && "${PYTHON}" -m zipfile -c "${ZIP_PATH}" "RAOfflineProxy")
fi
printf '%s\n' "Created ${ZIP_PATH}"
