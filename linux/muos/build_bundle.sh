#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist/RAOfflineProxy"
APP_DIR="${DIST_DIR}/app"
PYGAME_DIR="${DIST_DIR}/pygame"
PYGAME_LIBS_DIR="${DIST_DIR}/pygame.libs"

rm -rf "${DIST_DIR}"
mkdir -p "${APP_DIR}" "${PYGAME_DIR}" "${PYGAME_LIBS_DIR}" "${DIST_DIR}/data"

cp -r "${LINUX_DIR}/raofflineproxy" "${APP_DIR}/raofflineproxy"
cp "${LINUX_DIR}/../docs/public/logo-320.png" "${APP_DIR}/raofflineproxy/logo-320.png"
cp -R "${SCRIPT_DIR}/vendor/pygame/." "${PYGAME_DIR}/"
cp -R "${SCRIPT_DIR}/vendor/pygame.libs/." "${PYGAME_LIBS_DIR}/"
cp "${SCRIPT_DIR}/launch.sh" "${DIST_DIR}/launch.sh"
cp "${SCRIPT_DIR}/mux_launch.sh" "${DIST_DIR}/mux_launch.sh"
cp "${SCRIPT_DIR}/mux_lang.ini" "${DIST_DIR}/mux_lang.ini"

find "${APP_DIR}" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}" -name "*.pyc" -delete

chmod +x "${DIST_DIR}/launch.sh"
chmod +x "${DIST_DIR}/mux_launch.sh"

printf '%s\n' "Created ${DIST_DIR}"
