#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINUX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
BUILD_DIR="${DIST_DIR}/raofflineproxy-rocknix-bundle"
APP_DIR="${BUILD_DIR}/app"
LIB_DIR="${BUILD_DIR}/lib"
VERSION="${RAOFFLINEPROXY_APP_VERSION:-1.12.0-alpha1}"
INSTALLER_PATH="${DIST_DIR}/RAOfflineProxy-Rocknix-v${VERSION}-Install.sh"
TEMP_TARBALL="${DIST_DIR}/.raofflineproxy-rocknix-bundle.tar.gz"

# ROCKNIX builds ship different CPython minor versions (3.13 through nightly's
# 3.14), and pygame's extension modules only import into the interpreter whose
# ABI tag they carry, so the vendored pygame must cover every one of them.
REQUIRED_PY_VERSIONS=(313 314)

if [ ! -d "${SCRIPT_DIR}/vendor/pygame" ] || [ ! -d "${SCRIPT_DIR}/vendor/pygame_ce.libs" ]; then
  echo "Missing vendored pygame-ce. Run linux/rocknix/fetch_vendor.sh before building." >&2
  exit 1
fi

for py_version in "${REQUIRED_PY_VERSIONS[@]}"; do
  if [ ! -f "${SCRIPT_DIR}/vendor/pygame/base.cpython-${py_version}-aarch64-linux-gnu.so" ]; then
    echo "Vendored pygame-ce has no cpython-${py_version} extension modules." >&2
    echo "Re-run linux/rocknix/fetch_vendor.sh before building." >&2
    exit 1
  fi
done

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
cp -R "${SCRIPT_DIR}/vendor/pygame_ce.libs/." "${APP_DIR}/pygame_ce.libs/"
cp -r "${SCRIPT_DIR}/scripts" "${BUILD_DIR}/scripts"
cp "${SCRIPT_DIR}/native/libraproxy_rchash.so" "${LIB_DIR}/libraproxy_rchash.so"
cp "${SCRIPT_DIR}/scripts/install.sh" "${BUILD_DIR}/install.sh"
cp "${SCRIPT_DIR}/scripts/uninstall.sh" "${BUILD_DIR}/uninstall.sh"

find "${APP_DIR}/raofflineproxy" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}/raofflineproxy" -name "*.pyc" -delete
find "${APP_DIR}/raofflineproxy" -name "font-mono*.ttf" -delete
find "${APP_DIR}/pygame" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "${APP_DIR}/pygame" -name "*.pyc" -delete

chmod +x "${BUILD_DIR}/install.sh"
chmod +x "${BUILD_DIR}/uninstall.sh"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy"
chmod +x "${BUILD_DIR}/scripts/launcher-raofflineproxy-uninstall"
chmod +x "${BUILD_DIR}/scripts/tool-launcher.sh"
chmod +x "${BUILD_DIR}/scripts/sdl-doctor.sh"

mkdir -p "${DIST_DIR}"
rm -f "${TEMP_TARBALL}"
tar -czf "${TEMP_TARBALL}" -C "${DIST_DIR}" "raofflineproxy-rocknix-bundle"

cat > "${INSTALLER_PATH}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

# Set to true to also install the SDL Doctor diagnostic as a second Tools
# entry. Only needed when troubleshooting a menu that crashes on launch; it
# writes a report to /storage/.config/raofflineproxy/sdl-doctor.log.
INSTALL_SDL_DOCTOR="${INSTALL_SDL_DOCTOR:-false}"

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

PAYLOAD_TARBALL="$(mktemp "${SHARE_DIR}/.raofflineproxy-payload.XXXXXX")"
trap 'rm -f "${PAYLOAD_TARBALL}"' EXIT
tail -n +"${payload_line}" "${SCRIPT_PATH}" | base64 -d > "${PAYLOAD_TARBALL}"
tar -xzf "${PAYLOAD_TARBALL}" -C "${SHARE_DIR}" --no-same-owner

SENTINEL="${SHARE_DIR}/raofflineproxy-rocknix-bundle/app/raofflineproxy/main.py"
if [ ! -f "${SENTINEL}" ]; then
  echo "Installer payload did not extract correctly (missing ${SENTINEL})."
  echo "Your device's base64/tar may have truncated the payload."
  exit 1
fi

mv "${SHARE_DIR}/raofflineproxy-rocknix-bundle" "${TARGET_DIR}"
cd "${TARGET_DIR}"
INSTALL_SDL_DOCTOR="${INSTALL_SDL_DOCTOR}" ./install.sh
rm -f "${SCRIPT_PATH}"
echo "RAOfflineProxy installed."
exit 0
__RAOFFLINEPROXY_PAYLOAD_BELOW__
EOF

base64 < "${TEMP_TARBALL}" | fold -w 76 >> "${INSTALLER_PATH}"
chmod +x "${INSTALLER_PATH}"

rm -f "${TEMP_TARBALL}"
rm -rf "${BUILD_DIR}"

echo "Created ${INSTALLER_PATH}"
