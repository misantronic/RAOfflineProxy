cmake_minimum_required(VERSION 3.22.1)

project(raofflineproxy_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LIBCHDR_ROOT "${CMAKE_SOURCE_DIR}/../../../../third_party/libchdr")

add_library(ra_chd_reader SHARED
    chd_jni.cpp
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

target_include_directories(ra_chd_reader PRIVATE
    ${LIBCHDR_ROOT}/include
    ${LIBCHDR_ROOT}/src
    ${LIBCHDR_ROOT}/deps/miniz-3.1.1
    ${LIBCHDR_ROOT}/deps/lzma-25.01/include
    ${LIBCHDR_ROOT}/deps/zstd-1.5.7
)

target_compile_definitions(ra_chd_reader PRIVATE
    WANT_RAW_DATA_SECTOR=1
    WANT_SUBCODE=1
    VERIFY_BLOCK_CRC=1
)

find_library(log-lib log)

target_link_libraries(ra_chd_reader PRIVATE ${log-lib})
