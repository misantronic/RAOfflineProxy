/* sevenzip - minimal .7z reader (vendored LZMA SDK, third_party/lzma-sdk).
 *
 * rc_hash has no 7z support: it maps the extension to the arcade console, whose
 * hash is the archive's filename. Hashing a console ROM stored inside a .7z
 * therefore requires decompressing it first, which is all this provides —
 * listing the entries, and extracting one of them by name.
 *
 * Entry selection (which inner file is "the ROM") stays with the callers, who
 * already implement that policy for .zip.
 */
#ifndef RAO_SEVENZIP_H
#define RAO_SEVENZIP_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Writes the archive's file entries (not directories) into `out_names` as
 * consecutive NUL-terminated UTF-8 strings.
 *
 * Returns the number of entries written, or -1 if the archive could not be read
 * or does not fit within `out_names_bytes` / `max_entries`. Truncation is an
 * error rather than a short result: callers decide what to do from the entry
 * list, so a partial list could silently change that decision.
 */
int rao_7z_list_entries(const char* path, char* out_names, int out_names_bytes,
                        int max_entries);

/* Decompresses the entry named `entry_name`.
 *
 * On success returns 1 and hands back the SDK's block buffer plus the entry's
 * window into it (`*out_data + *out_offset`, `*out_size` bytes) — 7z entries
 * share a solid block, so the block is returned whole rather than copied out.
 * Release it with rao_7z_free. Returns 0 on failure, leaving *out_data NULL.
 */
int rao_7z_extract_entry(const char* path, const char* entry_name,
                         unsigned char** out_data, size_t* out_offset,
                         size_t* out_size);

void rao_7z_free(unsigned char* data);

#ifdef __cplusplus
}
#endif

#endif /* RAO_SEVENZIP_H */
