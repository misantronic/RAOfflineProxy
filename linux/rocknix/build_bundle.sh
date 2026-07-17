#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
BUILD_DIR="${DIST_DIR}/raofflineproxy-rocknix-bundle"
APP_DIR="${BUILD_DIR}/app"
LIB_DIR="${BUILD_DIR}/lib"
VERSION="${RAOFFLINEPROXY_APP_VERSION:-1.6.0-alpha1}"
INSTALLER_PATH="${DIST_DIR}/RAOfflineProxy-Rocknix-v${VERSION}-Install.sh"
TEMP_TARBALL="${DIST_DIR}/.raofflineproxy-rocknix-bundle.tar.gz"

if [ ! -d "${SCRIPT_DIR}/vendor/pygame" ] || [ ! -d "${SCRIPT_DIR}/vendor/pygame.libs" ]; then
  echo "Missing vendored pygame. Populate linux/rocknix/vendor/{pygame,pygame.libs}" >&2
  echo "from a pygame cp313 aarch64 manylinux wheel before building." >&2
  exit 1
fi

TARGET="aarch64-linux-gnu.2.17" OUT_DIR="${SCRIPT_DIR}/native" \
  "${LINUX_DIR}/build_rchash.sh"

rm -rf "${BUILD_DIR}"
rm -f "${DIST_DIR}/raofflineproxy-rocknix-bundle.tar.gz"
mkdir -p "${APP_DIR}"
mkdir -p "${LIB_DIR}"

export COPYFILE_DISABLE=1

cp -r "${LINUX_DIR}/raofflineproxy" "${APP_DIR}/raofflineproxy"
cp "${LINUX_DIR}/requirements.txt" "${APP_DIR}/requirements.txt"
cp "${LINUX_DIR}/../docs/public/logo-320.png" "${APP_DIR}/raofflineproxy/logo-320.png"
cp -R "${SCRIPT_DIR}/vendor/pygame/." "${APP_DIR}/pygame/"
cp -R "${SCRIPT_DIR}/vendor/pygame.libs/." "${APP_DIR}/pygame.libs/"
cp -r "${SCRIPT_DIR}/scripts" "${BUILD_DIR}/scripts"
cp "${SCRIPT_DIR}/native/libraproxy_rchash.so" "${LIB_DIR}/libraproxy_rchash.so"
cp "${SCRIPT_DIR}/scripts/install.sh" "${BUILD_DIR}/install.sh"
cp "${SCRIPT_DIR}/scripts/uninstall.sh" "${BUILD_DIR}/uninstall.sh"

find "${APP_DIR}/raofflineproxy" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}/raofflineproxy" -name "*.pyc" -delete
find "${APP_DIR}/pygame" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}/pygame" -name "*.pyc" -delete

chmod +x "${BUILD_DIR}/install.sh"
chmod +x "${BUILD_DIR}/uninstall.sh"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy-uninstall"
chmod +x "${BUILD_DIR}/scripts/tool-launcher.sh"

mkdir -p "${DIST_DIR}"
rm -f "${TEMP_TARBALL}"
tar -czf "${TEMP_TARBALL}" -C "${DIST_DIR}" "raofflineproxy-rocknix-bundle"

cat > "${INSTALLER_PATH}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_PATH="$0"
PAYLOAD_MARKER="__RAOFFLINEPROXY_PAYLOAD_BELOW__"
SHARE_DIR="/storage/.local/share"
TARGET_DIR="${SHARE_DIR}/.raofflineproxy-rocknix-bundle"

marker_line="$(awk -v marker="${PAYLOAD_MARKER}" '$0 == marker { print NR; exit }' "${SCRIPT_PATH}")"
if [ -z "${marker_line}" ]; then
  echo "Installer payload marker not found."
  exit 1
fi

payload_line=$((marker_line + 1))
rm -rf "${TARGET_DIR}"
mkdir -p "${SHARE_DIR}"
tail -n +"${payload_line}" "${SCRIPT_PATH}" | base64 -d | tar -xzf - -C "${SHARE_DIR}" --no-same-owner
mv "${SHARE_DIR}/raofflineproxy-rocknix-bundle" "${TARGET_DIR}"
cd "${TARGET_DIR}"
./install.sh
rm -f "${SCRIPT_PATH}"
echo "RAOfflineProxy installed."
exit 0
__RAOFFLINEPROXY_PAYLOAD_BELOW__
EOF

base64 -i "${TEMP_TARBALL}" >> "${INSTALLER_PATH}"
chmod +x "${INSTALLER_PATH}"

rm -f "${TEMP_TARBALL}"
rm -rf "${BUILD_DIR}"

echo "Created ${INSTALLER_PATH}"
