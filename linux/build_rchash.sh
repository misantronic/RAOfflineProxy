#!/usr/bin/env bash
# Builds libraproxy_rchash.so: rcheevos' rc_hash + the libchdr-backed CHD
# reader (third_party/rcheevos_glue) + libchdr, statically linked into one
# shared library that rom_hashing.py loads via ctypes. This replaces the
# standalone libchdr.so the Python hasher used to load directly.
#
# Driven by env vars so each distro's build_bundle.sh can reuse it:
#   TARGET   - zig cross-compile target (required), e.g. aarch64-linux-gnu.2.17
#   OUT_DIR  - directory to write libraproxy_rchash.so into (required)
#   ZIG_BIN  - path to zig (default: /opt/homebrew/bin/zig)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

: "${TARGET:?TARGET must be set (zig cross-compile triple)}"
: "${OUT_DIR:?OUT_DIR must be set}"
ZIG_BIN="${ZIG_BIN:-/opt/homebrew/bin/zig}"

RC="${REPO_DIR}/third_party/rcheevos"
GLUE="${REPO_DIR}/third_party/rcheevos_glue"
CHDR="${REPO_DIR}/third_party/libchdr"
OUT_PATH="${OUT_DIR}/libraproxy_rchash.so"

mkdir -p "${OUT_DIR}"

"${ZIG_BIN}" cc \
  -target "${TARGET}" \
  -shared -Os -s -fPIC \
  -DRC_HASH_NO_ENCRYPTED \
  -DWANT_RAW_DATA_SECTOR=1 \
  -DWANT_SUBCODE=1 \
  -DVERIFY_BLOCK_CRC=1 \
  -I"${RC}/include" \
  -I"${RC}/src" \
  -I"${RC}/src/rhash" \
  -I"${GLUE}" \
  -I"${GLUE}/shim" \
  -I"${CHDR}/include" \
  -I"${CHDR}/src" \
  -I"${CHDR}/deps/miniz-3.1.1" \
  -I"${CHDR}/deps/lzma-25.01/include" \
  -I"${CHDR}/deps/zstd-1.5.7" \
  -o "${OUT_PATH}" \
  "${RC}/src/rhash/hash.c" \
  "${RC}/src/rhash/hash_rom.c" \
  "${RC}/src/rhash/hash_disc.c" \
  "${RC}/src/rhash/hash_zip.c" \
  "${RC}/src/rhash/cdreader.c" \
  "${RC}/src/rhash/md5.c" \
  "${RC}/src/rc_compat.c" \
  "${GLUE}/chd_stream.c" \
  "${GLUE}/cdfs_chd.c" \
  "${GLUE}/strl_compat.c" \
  "${GLUE}/rchash_glue.c" \
  "${CHDR}/src/libchdr_bitstream.c" \
  "${CHDR}/src/libchdr_cdrom.c" \
  "${CHDR}/src/libchdr_chd.c" \
  "${CHDR}/src/libchdr_codec_cdfl.c" \
  "${CHDR}/src/libchdr_codec_cdlz.c" \
  "${CHDR}/src/libchdr_codec_cdzl.c" \
  "${CHDR}/src/libchdr_codec_cdzs.c" \
  "${CHDR}/src/libchdr_codec_flac.c" \
  "${CHDR}/src/libchdr_codec_huff.c" \
  "${CHDR}/src/libchdr_codec_lzma.c" \
  "${CHDR}/src/libchdr_codec_zlib.c" \
  "${CHDR}/src/libchdr_codec_zstd.c" \
  "${CHDR}/src/libchdr_flac.c" \
  "${CHDR}/src/libchdr_huffman.c" \
  "${CHDR}/deps/miniz-3.1.1/miniz.c" \
  "${CHDR}/deps/lzma-25.01/src/LzmaDec.c" \
  "${CHDR}/deps/zstd-1.5.7/zstddeclib.c"

echo "Created ${OUT_PATH}"
