#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LIBCHDR_ROOT="${REPO_DIR}/third_party/libchdr"
OUT_DIR="${SCRIPT_DIR}/native"
OUT_PATH="${OUT_DIR}/libchdr.so"
ZIG_BIN="${ZIG_BIN:-/opt/homebrew/bin/zig}"
TARGET="${TARGET:-aarch64-linux-gnu.2.17}"

mkdir -p "${OUT_DIR}"

"${ZIG_BIN}" cc \
  -target "${TARGET}" \
  -shared \
  -Os \
  -s \
  -fPIC \
  -DWANT_RAW_DATA_SECTOR=1 \
  -DWANT_SUBCODE=1 \
  -DVERIFY_BLOCK_CRC=1 \
  -I"${LIBCHDR_ROOT}/include" \
  -I"${LIBCHDR_ROOT}/src" \
  -I"${LIBCHDR_ROOT}/deps/miniz-3.1.1" \
  -I"${LIBCHDR_ROOT}/deps/lzma-25.01/include" \
  -I"${LIBCHDR_ROOT}/deps/zstd-1.5.7" \
  -o "${OUT_PATH}" \
  "${LIBCHDR_ROOT}/src/libchdr_bitstream.c" \
  "${LIBCHDR_ROOT}/src/libchdr_cdrom.c" \
  "${LIBCHDR_ROOT}/src/libchdr_chd.c" \
  "${LIBCHDR_ROOT}/src/libchdr_codec_cdfl.c" \
  "${LIBCHDR_ROOT}/src/libchdr_codec_cdlz.c" \
  "${LIBCHDR_ROOT}/src/libchdr_codec_cdzl.c" \
  "${LIBCHDR_ROOT}/src/libchdr_codec_cdzs.c" \
  "${LIBCHDR_ROOT}/src/libchdr_codec_flac.c" \
  "${LIBCHDR_ROOT}/src/libchdr_codec_huff.c" \
  "${LIBCHDR_ROOT}/src/libchdr_codec_lzma.c" \
  "${LIBCHDR_ROOT}/src/libchdr_codec_zlib.c" \
  "${LIBCHDR_ROOT}/src/libchdr_codec_zstd.c" \
  "${LIBCHDR_ROOT}/src/libchdr_flac.c" \
  "${LIBCHDR_ROOT}/src/libchdr_huffman.c" \
  "${LIBCHDR_ROOT}/deps/miniz-3.1.1/miniz.c" \
  "${LIBCHDR_ROOT}/deps/lzma-25.01/src/LzmaDec.c" \
  "${LIBCHDR_ROOT}/deps/zstd-1.5.7/zstddeclib.c"

echo "Created ${OUT_PATH}"
