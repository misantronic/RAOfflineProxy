#include "rchash_glue.h"

#include <stdlib.h>
#include <string.h>

#include "rc_hash.h"

#include "cdfs_chd.h"
#include "cdfs_pbp.h"
#include "sevenzip.h"

/* ---- CHD cdreader hooks (mirrors RetroArch cheevos.c rc_hash_handle_chd_*) ---- */

static int rao_path_is_chd(const char* path)
{
   const char* dot = path ? strrchr(path, '.') : NULL;
   if (!dot)
      return 0;
   return (dot[1] == 'c' || dot[1] == 'C')
       && (dot[2] == 'h' || dot[2] == 'H')
       && (dot[3] == 'd' || dot[3] == 'D')
       &&  dot[4] == '\0';
}

static int rao_path_is_pbp(const char* path)
{
   const char* dot = path ? strrchr(path, '.') : NULL;
   if (!dot)
      return 0;
   return (dot[1] == 'p' || dot[1] == 'P')
       && (dot[2] == 'b' || dot[2] == 'B')
       && (dot[3] == 'p' || dot[3] == 'P')
       &&  dot[4] == '\0';
}

/* ---- PBP cdreader hooks (PS1 disc embedded in a POPS PBP, see cdfs_pbp) ---- */

static size_t rao_pbp_read_sector(void* track_handle, uint32_t sector,
      void* buffer, size_t requested_bytes)
{
   return pbp_read_sector((pbp_disc_t*)track_handle, sector, buffer, requested_bytes);
}

static uint32_t rao_pbp_first_track_sector(void* track_handle)
{
   return pbp_first_sector((pbp_disc_t*)track_handle);
}

static void rao_pbp_close_track(void* track_handle)
{
   pbp_close((pbp_disc_t*)track_handle);
}

static void* rao_chd_open_track(const char* path, uint32_t track)
{
   cdfs_track_t* cdfs_track;

   switch (track)
   {
      case RC_HASH_CDTRACK_FIRST_DATA:
         cdfs_track = cdfs_open_data_track(path);
         break;
      case RC_HASH_CDTRACK_LAST:
         cdfs_track = cdfs_open_chd_track(path, CHDSTREAM_TRACK_LAST);
         break;
      case RC_HASH_CDTRACK_LARGEST:
         cdfs_track = cdfs_open_chd_track(path, CHDSTREAM_TRACK_PRIMARY);
         break;
      default:
         cdfs_track = cdfs_open_chd_track(path, (int32_t)track);
         break;
   }

   if (cdfs_track)
   {
      cdfs_file_t* file = (cdfs_file_t*)malloc(sizeof(cdfs_file_t));
      if (file && cdfs_open_file(file, cdfs_track, NULL))
         return file; /* file owns cdfs_track now */

      free(file);
      cdfs_close_track(cdfs_track);
   }

   return NULL;
}

static size_t rao_chd_read_sector(void* track_handle, uint32_t sector,
      void* buffer, size_t requested_bytes)
{
   cdfs_file_t* file = (cdfs_file_t*)track_handle;
   uint32_t track_sectors = cdfs_get_num_sectors(file);

   sector -= cdfs_get_first_sector(file);
   if (sector >= track_sectors)
      return 0;

   cdfs_seek_sector(file, sector);
   return (size_t)cdfs_read_file(file, buffer, requested_bytes);
}

static uint32_t rao_chd_first_track_sector(void* track_handle)
{
   return cdfs_get_first_sector((cdfs_file_t*)track_handle);
}

static void rao_chd_close_track(void* track_handle)
{
   cdfs_file_t* file = (cdfs_file_t*)track_handle;
   if (file)
   {
      cdfs_close_track(file->track);
      cdfs_close_file(file);
      free(file);
   }
}

/* open_track_iterator: route CHD through libchdr, everything else (cue/bin/iso/
 * gdi) through rcheevos' default cdreader over the default file reader. */
static void* rao_cd_open_track(const char* path, uint32_t track,
      const rc_hash_iterator_t* iterator)
{
   rc_hash_callbacks_t* callbacks = (rc_hash_callbacks_t*)&iterator->callbacks;

   if (rao_path_is_chd(path))
   {
      callbacks->cdreader.read_sector        = rao_chd_read_sector;
      callbacks->cdreader.close_track        = rao_chd_close_track;
      callbacks->cdreader.first_track_sector = rao_chd_first_track_sector;
      return rao_chd_open_track(path, track);
   }
   else if (rao_path_is_pbp(path))
   {
      callbacks->cdreader.read_sector        = rao_pbp_read_sector;
      callbacks->cdreader.close_track        = rao_pbp_close_track;
      callbacks->cdreader.first_track_sector = rao_pbp_first_track_sector;
      return pbp_open(path);
   }
   else
   {
      struct rc_hash_cdreader cdreader;
      rc_hash_get_default_cdreader(&cdreader);
      return cdreader.open_track_iterator(path, track, iterator);
   }
}

static void rao_ensure_hooks(void)
{
   static int installed = 0;
   struct rc_hash_cdreader cdreader;

   if (installed)
      return;

   rc_hash_get_default_cdreader(&cdreader);
   cdreader.open_track_iterator = rao_cd_open_track;
   rc_hash_init_custom_cdreader(&cdreader);

   installed = 1;
}

/* ---- datasource-backed filereader (for decompressed GC/Wii containers) ---- */

typedef struct {
   void* ctx;
   raproxy_ds_size_fn size_fn;
   raproxy_ds_read_fn read_fn;
   int64_t pos;
} rao_ds_file;

/* rc_hash's filereader has no userdata on open(), so the pending datasource is
 * stashed here. Hashing is single-threaded per call, so this is safe. */
static rao_ds_file* g_pending_ds = NULL;

static void* rao_ds_open(const char* path)
{
   rao_ds_file* file = g_pending_ds;
   (void)path;
   if (file)
      file->pos = 0;
   return file;
}

static void rao_ds_seek(void* handle, int64_t offset, int origin)
{
   rao_ds_file* file = (rao_ds_file*)handle;
   if (!file)
      return;
   switch (origin)
   {
      case SEEK_SET: file->pos = offset; break;
      case SEEK_CUR: file->pos += offset; break;
      case SEEK_END: file->pos = file->size_fn(file->ctx) + offset; break;
      default: break;
   }
}

static int64_t rao_ds_tell(void* handle)
{
   rao_ds_file* file = (rao_ds_file*)handle;
   return file ? file->pos : 0;
}

static size_t rao_ds_read(void* handle, void* buffer, size_t requested_bytes)
{
   rao_ds_file* file = (rao_ds_file*)handle;
   int got;
   if (!file)
      return 0;
   got = file->read_fn(file->ctx, file->pos, buffer, (int)requested_bytes);
   if (got <= 0)
      return 0;
   file->pos += got;
   return (size_t)got;
}

static void rao_ds_close(void* handle)
{
   (void)handle; /* the rao_ds_file is owned by the caller */
}

/* ---- candidate buffer helpers ---- */

/* Appends `candidate` unless it is empty, already present, or the buffer is
 * full. Returns the new count. */
static int rao_append_candidate(char* out_hashes, int count, int max_hashes,
                                const char* candidate)
{
   int i;

   if (count >= max_hashes || candidate[0] == '\0')
      return count;

   for (i = 0; i < count; ++i)
   {
      if (memcmp(out_hashes + i * 33, candidate, 33) == 0)
         return count;
   }

   memcpy(out_hashes + count * 33, candidate, 33);
   return count + 1;
}

/* Drains an iterator into the candidate buffer, in rc_hash's own order. */
static int rao_collect_candidates(rc_hash_iterator_t* iterator, char* out_hashes,
                                  int max_hashes)
{
   int count = 0;

   while (count < max_hashes)
   {
      char candidate[33];

      if (!rc_hash_iterate(candidate, iterator) || candidate[0] == '\0')
         break;

      count = rao_append_candidate(out_hashes, count, max_hashes, candidate);
   }

   return count;
}

int raproxy_hash_disc_datasource(void* ctx, raproxy_ds_size_fn size_fn,
                                 raproxy_ds_read_fn read_fn,
                                 char* out_hashes, int max_hashes)
{
   static const uint32_t consoles[2] = { RC_CONSOLE_GAMECUBE, RC_CONSOLE_WII };
   rc_hash_iterator_t iterator;
   rao_ds_file file;
   int count = 0;
   int i;

   if (!ctx || !size_fn || !read_fn || !out_hashes || max_hashes <= 0)
      return 0;

   file.ctx = ctx;
   file.size_fn = size_fn;
   file.read_fn = read_fn;
   file.pos = 0;

   rao_ensure_hooks();

   rc_hash_initialize_iterator(&iterator, "disc.iso", NULL, 0);
   iterator.callbacks.filereader.open  = rao_ds_open;
   iterator.callbacks.filereader.seek  = rao_ds_seek;
   iterator.callbacks.filereader.tell  = rao_ds_tell;
   iterator.callbacks.filereader.read  = rao_ds_read;
   iterator.callbacks.filereader.close = rao_ds_close;

   for (i = 0; i < 2 && count < max_hashes; ++i)
   {
      char candidate[33];

      g_pending_ds = &file;
      file.pos = 0;

      if (!rc_hash_generate(candidate, consoles[i], &iterator))
         continue;

      count = rao_append_candidate(out_hashes, count, max_hashes, candidate);
   }

   g_pending_ds = NULL;
   rc_hash_destroy_iterator(&iterator);
   return count;
}

/* ---- public entry point ---- */

int raproxy_hash_file(const char* path, char* out_hashes, int max_hashes)
{
   rc_hash_iterator_t iterator;
   int count = 0;

   if (!path || !out_hashes || max_hashes <= 0)
      return 0;

   rao_ensure_hooks();

   rc_hash_initialize_iterator(&iterator, path, NULL, 0);
   count = rao_collect_candidates(&iterator, out_hashes, max_hashes);
   rc_hash_destroy_iterator(&iterator);

   /* rc_hash's extension table maps .pbp to PSP only (a whole-file hash), so a
    * PS1 game wrapped in a PBP never gets its executable-based PlayStation hash.
    * Generate that candidate explicitly via the PBP-aware cdreader hook above. */
   if (count < max_hashes && rao_path_is_pbp(path))
   {
      rc_hash_iterator_t psx_iterator;
      char candidate[33];

      rc_hash_initialize_iterator(&psx_iterator, path, NULL, 0);
      if (rc_hash_generate(candidate, RC_CONSOLE_PLAYSTATION, &psx_iterator))
         count = rao_append_candidate(out_hashes, count, max_hashes, candidate);
      rc_hash_destroy_iterator(&psx_iterator);
   }

   return count;
}

int raproxy_7z_list_entries(const char* path, char* out_names,
                            int out_names_bytes, int max_entries)
{
   return rao_7z_list_entries(path, out_names, out_names_bytes, max_entries);
}

int raproxy_hash_7z_entry(const char* path, const char* entry_name,
                          char* out_hashes, int max_hashes)
{
   rc_hash_iterator_t iterator;
   unsigned char* block = NULL;
   size_t offset = 0;
   size_t size = 0;
   int count;

   if (!path || !entry_name || !out_hashes || max_hashes <= 0)
      return 0;

   rao_ensure_hooks();

   if (!rao_7z_extract_entry(path, entry_name, &block, &offset, &size))
      return 0;

   /* The entry name carries the extension rc_hash needs to pick a console; the
    * buffer carries the data it would otherwise read from disk. */
   rc_hash_initialize_iterator(&iterator, entry_name, block + offset, size);
   count = rao_collect_candidates(&iterator, out_hashes, max_hashes);
   rc_hash_destroy_iterator(&iterator);

   rao_7z_free(block);
   return count;
}
