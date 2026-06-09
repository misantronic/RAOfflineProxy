/* Minimal shim for libretro-common's <compat/strl.h>.
 *
 * macOS/BSD and the Android NDK (bionic) provide strlcpy/strlcat in libc.
 * Older glibc (< 2.38) does not, so we provide our own implementation in
 * strl_compat.c, exposed under the rao_ prefix and aliased here to avoid
 * clashing with a libc declaration when one exists. */
#ifndef __LIBRETRO_SDK_COMPAT_STRL_H
#define __LIBRETRO_SDK_COMPAT_STRL_H

#include <string.h>

#if defined(__APPLE__) || defined(__ANDROID__) || defined(__BSD__) || \
    (defined(__GLIBC__) && (__GLIBC__ > 2 || (__GLIBC__ == 2 && __GLIBC_MINOR__ >= 38)))
/* libc already provides strlcpy/strlcat with the standard signatures. */
#else
size_t rao_strlcpy(char *dest, const char *source, size_t size);
size_t rao_strlcat(char *dest, const char *source, size_t size);
#define strlcpy rao_strlcpy
#define strlcat rao_strlcat
#endif

#endif
