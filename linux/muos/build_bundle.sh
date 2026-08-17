#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
VERSION="${RAOFFLINEPROXY_APP_VERSION:-1.11.1-alpha3}"
DIST_DIR="${SCRIPT_DIR}/dist/RAOfflineProxy"
APP_DIR="${DIST_DIR}/app"
LIB_DIR="${DIST_DIR}/lib"
PYGAME_DIR="${DIST_DIR}/pygame"
PYGAME_LIBS_DIR="${DIST_DIR}/pygame.libs"
MUXAPP_PATH="${SCRIPT_DIR}/dist/RAOfflineProxy-muOS-v${VERSION}.muxapp"

# Build the aarch64 libraproxy_rchash.so (rcheevos rc_hash + libchdr) used for
# ROM/disc hashing on-device.
TARGET="aarch64-linux-gnu.2.17" OUT_DIR="${SCRIPT_DIR}/native" \
  "${LINUX_DIR}/build_rchash.sh"

rm -rf "${DIST_DIR}"
mkdir -p "${APP_DIR}" "${LIB_DIR}" "${PYGAME_DIR}" "${PYGAME_LIBS_DIR}" "${DIST_DIR}/data"

cp -r "${LINUX_DIR}/raofflineproxy" "${APP_DIR}/raofflineproxy"
cp "${LINUX_DIR}/../docs/public/logo-320.png" "${APP_DIR}/raofflineproxy/logo-320.png"
cp "${SCRIPT_DIR}/native/libraproxy_rchash.so" "${LIB_DIR}/libraproxy_rchash.so"
cp -R "${SCRIPT_DIR}/vendor/pygame/." "${PYGAME_DIR}/"
cp -R "${SCRIPT_DIR}/vendor/pygame.libs/." "${PYGAME_LIBS_DIR}/"
cp "${SCRIPT_DIR}/launch.sh" "${DIST_DIR}/launch.sh"
cp "${SCRIPT_DIR}/mux_launch.sh" "${DIST_DIR}/mux_launch.sh"
cp "${SCRIPT_DIR}/mux_lang.ini" "${DIST_DIR}/mux_lang.ini"
cp "${SCRIPT_DIR}/uninstall.sh" "${DIST_DIR}/uninstall.sh"

find "${APP_DIR}" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}" -name "*.pyc" -delete
find "${APP_DIR}" -name "font-mono*.ttf" -delete

chmod +x "${DIST_DIR}/launch.sh"
chmod +x "${DIST_DIR}/mux_launch.sh"
chmod +x "${DIST_DIR}/uninstall.sh"

# Bundle the icon inside the app dir; launch.sh installs it into theme dirs on first run.
cp "${SCRIPT_DIR}/raofflineproxy.png" "${DIST_DIR}/raofflineproxy.png"

printf '%s\n' "Created ${DIST_DIR}"

# Build .muxapp archive — .muxapp is always extracted into application/ by Archive Manager.
# The icon is installed into theme dirs on first launch by launch.sh.
rm -f "${MUXAPP_PATH}"
export COPYFILE_DISABLE=1  # suppress macOS ._* metadata files in the zip
(cd "${SCRIPT_DIR}/dist" && zip -r "${MUXAPP_PATH}" "RAOfflineProxy" --exclude "RAOfflineProxy/data/*")
printf '%s\n' "Created ${MUXAPP_PATH}"
