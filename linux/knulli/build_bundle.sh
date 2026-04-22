#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
BUILD_DIR="${DIST_DIR}/raofflineproxy-knulli-bundle"
APP_DIR="${BUILD_DIR}/app"

rm -rf "${BUILD_DIR}"
mkdir -p "${APP_DIR}"

cp -r "${LINUX_DIR}/raofflineproxy" "${APP_DIR}/raofflineproxy"
cp "${LINUX_DIR}/requirements.txt" "${APP_DIR}/requirements.txt"
cp -r "${SCRIPT_DIR}/scripts" "${BUILD_DIR}/scripts"
cp "${SCRIPT_DIR}/scripts/install.sh" "${BUILD_DIR}/install.sh"
cp "${SCRIPT_DIR}/scripts/uninstall.sh" "${BUILD_DIR}/uninstall.sh"

find "${APP_DIR}" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}" -name "*.pyc" -delete

chmod +x "${BUILD_DIR}/install.sh"
chmod +x "${BUILD_DIR}/uninstall.sh"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy-ui"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy-start"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy-stop"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy-status"

mkdir -p "${DIST_DIR}"
tar -czf "${DIST_DIR}/raofflineproxy-knulli-bundle.tar.gz" -C "${DIST_DIR}" "raofflineproxy-knulli-bundle"

echo "Created ${DIST_DIR}/raofflineproxy-knulli-bundle.tar.gz"
