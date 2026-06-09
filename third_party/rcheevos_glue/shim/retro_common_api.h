/* Minimal shim for libretro-common's <retro_common_api.h>. The vendored
 * chd_stream.{c,h} only need the C-linkage decoration macros and ssize_t. */
#ifndef LIBRETRO_COMMON_API_H
#define LIBRETRO_COMMON_API_H

#include <sys/types.h> /* ssize_t */

#ifdef __cplusplus
#define RETRO_BEGIN_DECLS extern "C" {
#define RETRO_END_DECLS }
#else
#define RETRO_BEGIN_DECLS
#define RETRO_END_DECLS
#endif

#endif
