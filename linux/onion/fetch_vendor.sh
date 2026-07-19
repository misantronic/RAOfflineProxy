#!/usr/bin/env bash
# Downloads the Onion pygame vendor directory: a cp39 armv7 pygame wheel
# (piwheels) plus the SDL2 stack it needs at runtime (SDL2/SDL2_ttf/SDL2_image
# built by steward-fu for the Miyoo Mini's "Mini" video driver, and a handful
# of glibc-side libs the standalone Python runtime needs but doesn't bundle).
#
# The "Mini" SDL2 driver only presents through the SDL_Renderer + streaming
# texture path (see menu_sdl.py's _init_onion_display) — its window-surface
# path silently no-ops. That's a property of the vendored SDL2 build, not
# something this fetch script needs to know about.
#
# Usage:
#   linux/onion/fetch_vendor.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENDOR_DIR="${SCRIPT_DIR}/vendor"
TOOLCHAIN_CACHE_DIR="${SCRIPT_DIR}/runtime-cache"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

PYGAME_WHEEL="pygame-2.6.1-cp39-cp39-linux_armv7l.whl"
PYGAME_WHEEL_URL="https://www.piwheels.org/simple/pygame/${PYGAME_WHEEL}"

SDL2_REPO_RAW="https://raw.githubusercontent.com/steward-fu/sdl2/master"
SDL2_FILES=(
  "prebuilt/640x480/libSDL2-2.0.so.0"
  "prebuilt/640x480/libEGL.so"
  "prebuilt/640x480/libGLESv2.so"
  "examples/libSDL2_ttf-2.0.so.0"
  "examples/libSDL2_image-2.0.so.0"
  "mini/lib/libshmvar.so"
)

TOOLCHAIN_ARCHIVE_NAME="mini_toolchain-v1.0.tar.gz"
TOOLCHAIN_ARCHIVE_URL="https://github.com/steward-fu/website/releases/download/miyoo-mini/${TOOLCHAIN_ARCHIVE_NAME}"
TOOLCHAIN_ARCHIVE_PATH="${TOOLCHAIN_CACHE_DIR}/${TOOLCHAIN_ARCHIVE_NAME}"
# Small subset of the glibc-side sysroot libs the standalone Python runtime
# and SDL2_ttf/SDL2_image need but don't bundle themselves. Extracted
# selectively (never a full unpack) because the archive's terminfo tree has
# entries that collide on macOS's case-insensitive filesystem.
TOOLCHAIN_SYSROOT_LIBS=(
  "mini/arm-buildroot-linux-gnueabihf/sysroot/usr/lib/libfreetype.so.6.17.4:libfreetype.so.6"
  "mini/arm-buildroot-linux-gnueabihf/sysroot/usr/lib/libpng16.so.16.37.0:libpng16.so.16"
  "mini/arm-buildroot-linux-gnueabihf/sysroot/usr/lib/libbz2.so.1.0.8:libbz2.so.1.0"
  "mini/arm-buildroot-linux-gnueabihf/sysroot/usr/lib/libz.so.1.2.11:libz.so.1"
  "mini/arm-buildroot-linux-gnueabihf/sysroot/usr/lib/libjson-c.so.5.1.0:libjson-c.so.5"
  "mini/arm-buildroot-linux-gnueabihf/sysroot/lib/libutil-2.28.so:libutil.so.1"
)

rm -rf "${VENDOR_DIR}"
mkdir -p "${VENDOR_DIR}/pygame.libs"

echo "Downloading ${PYGAME_WHEEL}..."
curl -L --fail -sS -o "${TMP_DIR}/pygame.whl" "${PYGAME_WHEEL_URL}"
mkdir -p "${TMP_DIR}/extracted"
unzip -q "${TMP_DIR}/pygame.whl" -d "${TMP_DIR}/extracted"
cp -R "${TMP_DIR}/extracted/pygame" "${VENDOR_DIR}/pygame"
rm -f "${VENDOR_DIR}"/pygame/_camera.cpython-39-*.so

echo "Downloading SDL2 stack from steward-fu/sdl2..."
for rel_path in "${SDL2_FILES[@]}"; do
  name="$(basename "${rel_path}")"
  echo "  ${name}"
  curl -L --fail -sS -o "${VENDOR_DIR}/pygame.libs/${name}" "${SDL2_REPO_RAW}/${rel_path}"
done

mkdir -p "${TOOLCHAIN_CACHE_DIR}"
if [ ! -f "${TOOLCHAIN_ARCHIVE_PATH}" ]; then
  echo "Downloading ${TOOLCHAIN_ARCHIVE_NAME} (one-time, ~700MB, cached under runtime-cache/)..."
  curl -L --fail -o "${TOOLCHAIN_ARCHIVE_PATH}" "${TOOLCHAIN_ARCHIVE_URL}"
else
  echo "Using cached ${TOOLCHAIN_ARCHIVE_PATH}"
fi

echo "Extracting sysroot libs..."
extract_paths=()
for entry in "${TOOLCHAIN_SYSROOT_LIBS[@]}"; do
  extract_paths+=("${entry%%:*}")
done
tar -xzf "${TOOLCHAIN_ARCHIVE_PATH}" -C "${TMP_DIR}" "${extract_paths[@]}"

for entry in "${TOOLCHAIN_SYSROOT_LIBS[@]}"; do
  src_path="${entry%%:*}"
  dest_name="${entry##*:}"
  cp "${TMP_DIR}/${src_path}" "${VENDOR_DIR}/pygame.libs/${dest_name}"
done

chmod +x "${VENDOR_DIR}"/pygame.libs/*.so*

echo ""
echo "Done. vendor/pygame contains $(find "${VENDOR_DIR}/pygame" -name '*.so' | wc -l | tr -d ' ') extension modules."
echo "vendor/pygame.libs contains:"
ls "${VENDOR_DIR}/pygame.libs"
