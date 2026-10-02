#!/usr/bin/env bash
# Builds the sqlite3 Python module KNULLI's Python lacks (its build drops both the sqlite3
# package and the _sqlite3 extension), so the app can use SQLite instead of the JSON fallback.
#
# The extension is CPython 3.12.8's own Modules/_sqlite compiled for aarch64 with SQLite 3.47.0
# embedded (nothing to load from the firmware). Python's headers come from python-build-standalone,
# whose own builds have _sqlite3 compiled into libpython, so there is no ready-made file to copy.
# Every input is pinned by checksum or commit.
#
# Output: runtime-cache/sqlite-aarch64/{sqlite3/,_sqlite3.cpython-312-aarch64-linux-gnu.so},
# which build_bundle.sh ships as app/vendor/sqlite. Needs zig, git, curl, tar, unzip.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE_DIR="${SCRIPT_DIR}/runtime-cache"
OUT_DIR="${CACHE_DIR}/sqlite-aarch64"
MODULE_NAME="_sqlite3.cpython-312-aarch64-linux-gnu.so"
ZIG="${ZIG_BIN:-/opt/homebrew/bin/zig}"

PBS_BASE="https://github.com/astral-sh/python-build-standalone/releases/download/20241206"
PBS_ASSET="cpython-3.12.8+20241206-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz"
PBS_SHA256="de4dacd5b182523e3567d547f0e072af0502a52ac2e8244ae14f58049daf7a66"
CPYTHON_TAG="v3.12.8"
CPYTHON_COMMIT="2dc476bcb9142cd25d7e1d52392b73a3dcdf1756"
SQLITE_URL="https://www.sqlite.org/2024/sqlite-amalgamation-3470000.zip"
SQLITE_SHA256="2842fddbb1cc33f66c7da998a57535f14a6bfee159676a07bb4bf3e59375d93e"

if [ -f "${OUT_DIR}/${MODULE_NAME}" ] && [ -f "${OUT_DIR}/sqlite3/__init__.py" ]; then
  echo "sqlite3 module already built: ${OUT_DIR}"
  exit 0
fi

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

download() {
  local url="$1" target="$2" expected="$3"
  if [ ! -f "${target}" ]; then
    curl -sSL --fail --output "${target}" "${url}"
  fi
  if [ "$(sha256_of "${target}")" != "${expected}" ]; then
    echo "Checksum mismatch for ${target}" >&2
    rm -f "${target}"
    exit 1
  fi
}

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT
mkdir -p "${CACHE_DIR}"

download "${PBS_BASE}/${PBS_ASSET//+/%2B}" "${CACHE_DIR}/${PBS_ASSET}" "${PBS_SHA256}"
download "${SQLITE_URL}" "${CACHE_DIR}/sqlite-amalgamation-3470000.zip" "${SQLITE_SHA256}"

tar -xzf "${CACHE_DIR}/${PBS_ASSET}" -C "${WORK_DIR}" python/include 2>/dev/null
unzip -q "${CACHE_DIR}/sqlite-amalgamation-3470000.zip" -d "${WORK_DIR}"

git clone -q --depth 1 --branch "${CPYTHON_TAG}" --filter=blob:none --sparse \
  https://github.com/python/cpython.git "${WORK_DIR}/cpython" 2>/dev/null
git -C "${WORK_DIR}/cpython" sparse-checkout set Modules/_sqlite Lib/sqlite3 >/dev/null 2>&1
if [ "$(git -C "${WORK_DIR}/cpython" rev-parse HEAD)" != "${CPYTHON_COMMIT}" ]; then
  echo "CPython ${CPYTHON_TAG} is not the pinned commit ${CPYTHON_COMMIT}" >&2
  exit 1
fi

mkdir -p "${WORK_DIR}/out"
# No extension loading (it would need libdl), and hidden symbols keep SQLite's C API private to
# this module.
"${ZIG}" cc -target aarch64-linux-gnu.2.17 -shared -fPIC -s -O2 -DNDEBUG \
  -fvisibility=hidden -DSQLITE_THREADSAFE=1 -DSQLITE_OMIT_LOAD_EXTENSION \
  -Wno-deprecated-declarations -Wno-unused-parameter -Wno-unused-but-set-variable \
  -I "${WORK_DIR}/python/include/python3.12" \
  -I "${WORK_DIR}/cpython/Modules/_sqlite" \
  -I "${WORK_DIR}"/sqlite-amalgamation-* \
  "${WORK_DIR}"/cpython/Modules/_sqlite/*.c \
  "${WORK_DIR}"/sqlite-amalgamation-*/sqlite3.c \
  -o "${WORK_DIR}/out/${MODULE_NAME}"

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"
cp "${WORK_DIR}/out/${MODULE_NAME}" "${OUT_DIR}/${MODULE_NAME}"
cp -R "${WORK_DIR}/cpython/Lib/sqlite3" "${OUT_DIR}/sqlite3"
find "${OUT_DIR}" -name "__pycache__" -type d -prune -exec rm -rf {} +

echo "Built ${OUT_DIR}"
