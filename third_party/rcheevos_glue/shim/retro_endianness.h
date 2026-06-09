/* Minimal shim for libretro-common's <retro_endianness.h>. The vendored
 * chd_stream.c only uses SWAP16 (for byte-swapping audio frames). */
#ifndef __LIBRETRO_SDK_ENDIANNESS_H
#define __LIBRETRO_SDK_ENDIANNESS_H

#include <stdint.h>

static inline uint16_t SWAP16(uint16_t x)
{
   return (uint16_t)(((x & 0x00ff) << 8) | ((x & 0xff00) >> 8));
}

#endif
