/* Fallback strlcpy/strlcat for platforms whose libc lacks them (older glibc).
 * On macOS/BSD/Android these are unused (the header forwards to libc). */
#include "compat/strl.h"

#if !(defined(__APPLE__) || defined(__ANDROID__) || defined(__BSD__) || \
    (defined(__GLIBC__) && (__GLIBC__ > 2 || (__GLIBC__ == 2 && __GLIBC_MINOR__ >= 38))))

size_t rao_strlcpy(char *dest, const char *source, size_t size)
{
   size_t src_size = 0;
   const char *p   = source;

   if (size)
   {
      while (--size && *p)
      {
         *dest++ = *p++;
         src_size++;
      }
      *dest = '\0';
   }

   while (*p++)
      src_size++;

   return src_size;
}

size_t rao_strlcat(char *dest, const char *source, size_t size)
{
   size_t len = strlen(dest);

   dest += len;

   if (len > size)
      size = 0;
   else
      size -= len;

   return len + rao_strlcpy(dest, source, size);
}

#endif
