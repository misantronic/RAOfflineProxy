cmake_minimum_required(VERSION 3.22.1)

project(raofflineproxy_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY_ROOT "${CMAKE_SOURCE_DIR}/../../../../third_party")
set(LIBCHDR_ROOT "${THIRD_PARTY_ROOT}/libchdr")
set(RCHEEVOS_ROOT "${THIRD_PARTY_ROOT}/rcheevos")
set(RCHASH_GLUE_ROOT "${THIRD_PARTY_ROOT}/rcheevos_glue")

# Unified ROM/disc hasher: rcheevos' rc_hash + the libchdr-backed CHD reader
# (rcheevos_glue) + libchdr, plus the JNI shim. Replaces the previous
# hand-written per-format hashing and the CHD-only chd_jni bridge.
add_library(raproxy_rchash SHARED
    rchash_jni.cpp
    # rcheevos rc_hash (ROM + disc + zip; encrypted/3DS excluded)
    ${RCHEEVOS_ROOT}/src/rhash/hash.c
    ${RCHEEVOS_ROOT}/src/rhash/hash_rom.c
    ${RCHEEVOS_ROOT}/src/rhash/hash_disc.c
    ${RCHEEVOS_ROOT}/src/rhash/hash_zip.c
    ${RCHEEVOS_ROOT}/src/rhash/cdreader.c
    ${RCHEEVOS_ROOT}/src/rhash/md5.c
    ${RCHEEVOS_ROOT}/src/rc_compat.c
    # libchdr-backed CHD cooked-sector reader + glue
    ${RCHASH_GLUE_ROOT}/chd_stream.c
    ${RCHASH_GLUE_ROOT}/cdfs_chd.c
    ${RCHASH_GLUE_ROOT}/cdfs_pbp.c
    ${RCHASH_GLUE_ROOT}/strl_compat.c
    ${RCHASH_GLUE_ROOT}/rchash_glue.c
    # libchdr
    ${LIBCHDR_ROOT}/src/libchdr_bitstream.c
    ${LIBCHDR_ROOT}/src/libchdr_cdrom.c
    ${LIBCHDR_ROOT}/src/libchdr_chd.c
    ${LIBCHDR_ROOT}/src/libchdr_codec_cdfl.c
    ${LIBCHDR_ROOT}/src/libchdr_codec_cdlz.c
    ${LIBCHDR_ROOT}/src/libchdr_codec_cdzl.c
    ${LIBCHDR_ROOT}/src/libchdr_codec_cdzs.c
    ${LIBCHDR_ROOT}/src/libchdr_codec_flac.c
    ${LIBCHDR_ROOT}/src/libchdr_codec_huff.c
    ${LIBCHDR_ROOT}/src/libchdr_codec_lzma.c
    ${LIBCHDR_ROOT}/src/libchdr_codec_zlib.c
    ${LIBCHDR_ROOT}/src/libchdr_codec_zstd.c
    ${LIBCHDR_ROOT}/src/libchdr_flac.c
    ${LIBCHDR_ROOT}/src/libchdr_huffman.c
    ${LIBCHDR_ROOT}/deps/miniz-3.1.1/miniz.c
    ${LIBCHDR_ROOT}/deps/lzma-25.01/src/LzmaDec.c
    ${LIBCHDR_ROOT}/deps/zstd-1.5.7/zstddeclib.c
)

target_include_directories(raproxy_rchash PRIVATE
    ${RCHEEVOS_ROOT}/include
    ${RCHEEVOS_ROOT}/src
    ${RCHEEVOS_ROOT}/src/rhash
    ${RCHASH_GLUE_ROOT}
    ${RCHASH_GLUE_ROOT}/shim
    ${LIBCHDR_ROOT}/include
    ${LIBCHDR_ROOT}/src
    ${LIBCHDR_ROOT}/deps/miniz-3.1.1
    ${LIBCHDR_ROOT}/deps/lzma-25.01/include
    ${LIBCHDR_ROOT}/deps/zstd-1.5.7
)

target_compile_definitions(raproxy_rchash PRIVATE
    RC_HASH_NO_ENCRYPTED
    WANT_RAW_DATA_SECTOR=1
    WANT_SUBCODE=1
    VERIFY_BLOCK_CRC=1
)

find_library(log-lib log)

target_link_libraries(raproxy_rchash PRIVATE ${log-lib})
