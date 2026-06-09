/* cdfs_chd - a trimmed port of libretro-common's formats/cdfs/cdfs.c that
 * reads cooked 2048-byte data sectors out of a CHD disc image.
 *
 * The upstream cdfs sits on top of the `intfstream` abstraction and also
 * implements ISO-9660 directory walking. RAOfflineProxy only ever needs the
 * "raw cooked sector" path for CHD (rc_hash's default cdreader already handles
 * .cue/.bin/.iso/.gdi via the file reader), so this version talks to
 * `chdstream_t` directly and drops intfstream + the directory walker. The
 * sector-size detection and cdfs_read_file cooking logic are kept faithful to
 * upstream so produced hashes match RetroArch exactly.
 */
#ifndef RAO_CDFS_CHD_H
#define RAO_CDFS_CHD_H

#include <stdint.h>
#include <streams/chd_stream.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct cdfs_track_t
{
   chdstream_t* stream;
   unsigned int stream_sector_size;
   unsigned int stream_sector_header_size;
   unsigned int first_sector_offset;
   unsigned int first_sector_index;
} cdfs_track_t;

typedef struct cdfs_file_t
{
   struct cdfs_track_t* track;
   int first_sector;
   int current_sector;
   int sector_buffer_valid;
   unsigned int current_sector_offset;
   unsigned int size;
   unsigned int pos;
   uint8_t sector_buffer[2048];
} cdfs_file_t;

/* opens a specific track (1-based) or a CHDSTREAM_TRACK_* special value */
cdfs_track_t* cdfs_open_chd_track(const char* path, int32_t track_index);

/* opens the primary (largest) data track */
cdfs_track_t* cdfs_open_data_track(const char* path);

/* path must be NULL: opens the whole track for raw cooked-sector reads */
int cdfs_open_file(cdfs_file_t* file, cdfs_track_t* track, const char* path);

int64_t cdfs_read_file(cdfs_file_t* file, void* buffer, uint64_t len);

void cdfs_seek_sector(cdfs_file_t* file, unsigned int sector);

uint32_t cdfs_get_num_sectors(cdfs_file_t* file);

uint32_t cdfs_get_first_sector(cdfs_file_t* file);

void cdfs_close_file(cdfs_file_t* file);

void cdfs_close_track(cdfs_track_t* track);

#ifdef __cplusplus
}
#endif

#endif /* RAO_CDFS_CHD_H */
