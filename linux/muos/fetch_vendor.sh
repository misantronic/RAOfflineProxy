#!/usr/bin/env bash
# Downloads the muOS pygame vendor directory from PyPI.
#
# Fetches pygame 2.6.1 manylinux aarch64 wheels for both Python 3.11 and 3.12
# and merges their extension modules into a single vendor/pygame directory.
# Because Python selects .so files by ABI tag at import time, both versions
# coexist peacefully: Python 3.11 loads base.cpython-311-*.so and Python 3.12
# loads base.cpython-312-*.so from the same directory.
#
# The shared pygame.libs/ (SDL2, libjpeg, etc.) are identical between wheels,
# so only one copy is kept.
#
# Usage:
#   linux/muos/fetch_vendor.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENDOR_DIR="${SCRIPT_DIR}/vendor"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

PYGAME_VERSION="2.6.1"
PYTHON_VERSIONS=("311" "312")

echo "Fetching pygame ${PYGAME_VERSION} vendor (aarch64, cp311 + cp312)..."

for PYVER in "${PYTHON_VERSIONS[@]}"; do
    WHEEL_FILE="pygame-${PYGAME_VERSION}-cp${PYVER}-cp${PYVER}-manylinux_2_17_aarch64.manylinux2014_aarch64.whl"
    WHEEL_URL="https://files.pythonhosted.org/packages/source/p/pygame/${WHEEL_FILE}"

    echo "  Downloading ${WHEEL_FILE}..."
    pip3 download "pygame==${PYGAME_VERSION}" \
        --platform "manylinux_2_17_aarch64" \
        --python-version "${PYVER}" \
        --only-binary :all: \
        --no-deps \
        -d "${TMP_DIR}" \
        -q

    echo "  Extracting..."
    mkdir -p "${TMP_DIR}/extracted_${PYVER}"
    unzip -q "${TMP_DIR}/pygame-${PYGAME_VERSION}-cp${PYVER}-cp${PYVER}-manylinux_2_17_aarch64.manylinux2014_aarch64.whl" \
        -d "${TMP_DIR}/extracted_${PYVER}"
done

echo "Assembling vendor directory..."

# Start fresh
rm -rf "${VENDOR_DIR}"
mkdir -p "${VENDOR_DIR}"

# Use Python 3.11's extract as the base (pure Python files, pygame.libs, etc.)
cp -R "${TMP_DIR}/extracted_311/pygame" "${VENDOR_DIR}/pygame"
cp -R "${TMP_DIR}/extracted_311/pygame.libs" "${VENDOR_DIR}/pygame.libs"

# Merge 3.12 .so extensions on top (3.11 ones are already there from the base copy)
find "${TMP_DIR}/extracted_312/pygame" -name "*.cpython-312-aarch64-linux-gnu.so" | while read -r src; do
    rel="${src#${TMP_DIR}/extracted_312/pygame/}"
    dest_dir="${VENDOR_DIR}/pygame/$(dirname "${rel}")"
    mkdir -p "${dest_dir}"
    cp "${src}" "${dest_dir}/"
done

echo ""
echo "Done. vendor/pygame now contains:"
echo "  $(find "${VENDOR_DIR}/pygame" -name "*.cpython-311-aarch64-linux-gnu.so" | wc -l | tr -d ' ') cpython-311 extensions"
echo "  $(find "${VENDOR_DIR}/pygame" -name "*.cpython-312-aarch64-linux-gnu.so" | wc -l | tr -d ' ') cpython-312 extensions"
echo "  $(ls "${VENDOR_DIR}/pygame.libs" | wc -l | tr -d ' ') shared libraries in pygame.libs/"
