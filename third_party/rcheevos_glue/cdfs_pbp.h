/* cdfs_pbp - reads cooked 2048-byte data sectors out of the PS1 disc image
 * embedded in a PSP "POPS" PBP/EBOOT (DATA.PSAR PSISOIMG/PSTITLEIMG).
 *
 * rcheevos' default cdreader only understands raw .cue/.bin/.iso/.gdi, and its
 * extension table maps .pbp to PSP (a whole-file hash). A PS1 game wrapped in a
 * PBP must instead be hashed by its primary executable (rc_hash_psx), which
 * needs a cdreader that can decompress the PSISOIMG block stream. This provides
 * exactly that, mirroring DuckStation's CDImagePBP layout so produced hashes
 * match RetroAchievements.
 */
#ifndef RAO_CDFS_PBP_H
#define RAO_CDFS_PBP_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct pbp_disc_t pbp_disc_t;

/* Opens the first PS1 disc inside a PBP. Returns NULL when the file is not a
 * PBP, contains no PS1 POPS image (e.g. a real PSP EBOOT), or is encrypted. */
pbp_disc_t* pbp_open(const char* path);

/* Copies the cooked 2048-byte user data of the given absolute sector into
 * buffer. Returns bytes copied (<= min(requested_bytes, 2048)), 0 on failure. */
size_t pbp_read_sector(pbp_disc_t* disc, uint32_t sector, void* buffer, size_t requested_bytes);

uint32_t pbp_first_sector(pbp_disc_t* disc);

void pbp_close(pbp_disc_t* disc);

#ifdef __cplusplus
}
#endif

#endif /* RAO_CDFS_PBP_H */
