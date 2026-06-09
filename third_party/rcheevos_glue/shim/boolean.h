/* Minimal shim for libretro-common's <boolean.h>, used by the vendored
 * chd_stream.c. RAOfflineProxy only ever builds this for C compilers that
 * provide <stdbool.h>, so just forward to it. */
#ifndef __LIBRETRO_SDK_BOOLEAN_H
#define __LIBRETRO_SDK_BOOLEAN_H

#include <stdbool.h>

#endif
