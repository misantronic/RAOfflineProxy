#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
BUILD_DIR="${DIST_DIR}/raofflineproxy-knulli-bundle"
APP_DIR="${BUILD_DIR}/app"
LIB_DIR="${BUILD_DIR}/lib"
INSTALLER_PATH="${DIST_DIR}/RAOfflineProxy-Knulli-v1.13.0-alpha1-Install.sh"
TEMP_TARBALL="${DIST_DIR}/.raofflineproxy-knulli-bundle.tar.gz"

TARGET="aarch64-linux-gnu.2.17" OUT_DIR="${SCRIPT_DIR}/native" \
  "${LINUX_DIR}/build_rchash.sh"

rm -rf "${BUILD_DIR}"
rm -f "${DIST_DIR}/raofflineproxy-knulli-bundle.tar.gz"
mkdir -p "${APP_DIR}"
mkdir -p "${LIB_DIR}"

export COPYFILE_DISABLE=1

cp -r "${LINUX_DIR}/raofflineproxy" "${APP_DIR}/raofflineproxy"
cp "${LINUX_DIR}/requirements.txt" "${APP_DIR}/requirements.txt"
cp "${LINUX_DIR}/../docs/public/logo-320.png" "${APP_DIR}/raofflineproxy/logo-320.png"
cp -r "${SCRIPT_DIR}/scripts" "${BUILD_DIR}/scripts"
cp "${SCRIPT_DIR}/native/libraproxy_rchash.so" "${LIB_DIR}/libraproxy_rchash.so"
cp "${SCRIPT_DIR}/scripts/install.sh" "${BUILD_DIR}/install.sh"
cp "${SCRIPT_DIR}/scripts/uninstall.sh" "${BUILD_DIR}/uninstall.sh"

find "${APP_DIR}" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}" -name "*.pyc" -delete
find "${APP_DIR}" -name "font-mono*.ttf" -delete

chmod +x "${BUILD_DIR}/install.sh"
chmod +x "${BUILD_DIR}/uninstall.sh"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy-uninstall"

mkdir -p "${DIST_DIR}"
rm -f "${TEMP_TARBALL}"
tar -czf "${TEMP_TARBALL}" -C "${DIST_DIR}" "raofflineproxy-knulli-bundle"

cat > "${INSTALLER_PATH}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_PATH="$0"
PAYLOAD_MARKER="__RAOFFLINEPROXY_PAYLOAD_BELOW__"
TARGET_DIR="/userdata/system/raofflineproxy-knulli-bundle"
TOOLS_INSTALL_SCRIPT="/userdata/roms/tools/RAOfflineProxy Install.sh"

marker_line="$(awk -v marker="${PAYLOAD_MARKER}" '$0 == marker { print NR; exit }' "${SCRIPT_PATH}")"
if [ -z "${marker_line}" ]; then
  echo "Installer payload marker not found."
  exit 1
fi

payload_line=$((marker_line + 1))
rm -rf "${TARGET_DIR}"
mkdir -p "/userdata/system"

PAYLOAD_TARBALL="$(mktemp "/userdata/system/.raofflineproxy-payload.XXXXXX")"
trap 'rm -f "${PAYLOAD_TARBALL}"' EXIT
tail -n +"${payload_line}" "${SCRIPT_PATH}" | base64 -d > "${PAYLOAD_TARBALL}"
tar -xzf "${PAYLOAD_TARBALL}" -C "/userdata/system" --no-same-owner

SENTINEL="${TARGET_DIR}/app/raofflineproxy/main.py"
if [ ! -f "${SENTINEL}" ]; then
  echo "Installer payload did not extract correctly (missing ${SENTINEL})."
  echo "Your device's base64/tar may have truncated the payload."
  exit 1
fi

cd "${TARGET_DIR}"
./install.sh
rm -f "${TOOLS_INSTALL_SCRIPT}" "${SCRIPT_PATH}"
echo "RAOfflineProxy installed."
exit 0
__RAOFFLINEPROXY_PAYLOAD_BELOW__
EOF

base64 < "${TEMP_TARBALL}" | fold -w 76 >> "${INSTALLER_PATH}"
chmod +x "${INSTALLER_PATH}"

rm -f "${TEMP_TARBALL}"
rm -rf "${BUILD_DIR}"

echo "Created ${INSTALLER_PATH}"
