/* See cdfs_chd.h. Cooking logic mirrors libretro-common/formats/cdfs/cdfs.c
 * (MIT, (C) 2010-2020 The RetroArch team), retargeted from intfstream onto
 * chdstream_t. */
#include "cdfs_chd.h"

#include <stdio.h>  /* SEEK_SET */
#include <stdlib.h>
#include <string.h>

static void cdfs_determine_sector_size(cdfs_track_t* track)
{
   uint8_t buffer[32];
   const int toc_sector = 16;

   /* The boot record / primary volume descriptor is always at sector 16 and
    * contains a "CD001" marker. Its byte offset distinguishes the layout:
    *   MODE2/2352 (CDROM-XA): "CD001" 25 bytes into the sector (24B header)
    *   MODE1/2352           : "CD001" 17 bytes into the sector (16B header)
    */
   chdstream_seek(track->stream,
         (int64_t)toc_sector * 2352 + track->first_sector_offset, SEEK_SET);
   if (chdstream_read(track->stream, buffer, sizeof(buffer)) != (ssize_t)sizeof(buffer))
      return;

   if (   buffer[25] == 0x43
       && buffer[26] == 0x44
       && buffer[27] == 0x30
       && buffer[28] == 0x30
       && buffer[29] == 0x31)
   {
      track->stream_sector_size        = 2352;
      track->stream_sector_header_size = 24;
   }
   else if (buffer[17] == 0x43
       &&    buffer[18] == 0x44
       &&    buffer[19] == 0x30
       &&    buffer[20] == 0x30
       &&    buffer[21] == 0x31)
   {
      track->stream_sector_size        = 2352;
      track->stream_sector_header_size = 16;
   }
   else
   {
      /* ISO-9660 sync pattern 00 FF*10 00; format may predate CD001 */
      if (   buffer[ 0] == 0
          && buffer[ 1] == 0xFF
          && buffer[ 2] == 0xFF
          && buffer[ 3] == 0xFF
          && buffer[ 4] == 0xFF
          && buffer[ 5] == 0xFF
          && buffer[ 6] == 0xFF
          && buffer[ 7] == 0xFF
          && buffer[ 8] == 0xFF
          && buffer[ 9] == 0xFF
          && buffer[10] == 0xFF
          && buffer[11] == 0)
      {
         track->stream_sector_size        = 2352;
         track->stream_sector_header_size = 16;
      }
   }
}

static void cdfs_seek_track_sector(cdfs_track_t* track, unsigned int sector)
{
   chdstream_seek(track->stream,
           (int64_t)sector * track->stream_sector_size
         + track->stream_sector_header_size
         + track->first_sector_offset, SEEK_SET);
}

void cdfs_seek_sector(cdfs_file_t* file, unsigned int sector)
{
   /* only allowed if open_file was called with a NULL path */
   if (file->first_sector == 0)
   {
      if (file->current_sector != (int)sector)
      {
         file->current_sector      = (int)sector;
         file->sector_buffer_valid = 0;
      }

      file->pos                    = sector * 2048;
      file->current_sector_offset  = 0;
   }
}

uint32_t cdfs_get_num_sectors(cdfs_file_t* file)
{
   uint32_t frame_size = chdstream_get_frame_size(file->track->stream);
   if (frame_size == 0)
   {
      frame_size = file->track->stream_sector_size;
      if (frame_size == 0)
         frame_size = 1; /* prevent divide by 0 if sector size is unknown */
   }
   return (uint32_t)(chdstream_get_size(file->track->stream) / frame_size);
}

uint32_t cdfs_get_first_sector(cdfs_file_t* file)
{
   return file->track->first_sector_index;
}

int cdfs_open_file(cdfs_file_t* file, cdfs_track_t* track, const char* path)
{
   if (!file || !track)
      return 0;

   memset(file, 0, sizeof(*file));

   file->track          = track;
   file->current_sector = -1;
   file->first_sector   = -1;

   /* This trimmed cdfs only supports the raw cooked-sector mode (NULL path);
    * rc_hash never asks it to resolve a file by name. */
   if (!path && file->track->stream_sector_size)
   {
      file->first_sector = 0;
      file->size         = (unsigned int)((chdstream_get_size(
               file->track->stream) / file->track->stream_sector_size)
         * 2048);
      return 1;
   }

   return (file->first_sector >= 0);
}

int64_t cdfs_read_file(cdfs_file_t* file, void* buffer, uint64_t len)
{
   int bytes_read = 0;

   if (!file || file->first_sector < 0 || !buffer)
      return 0;

   if (len > file->size - file->pos)
      len = file->size - file->pos;

   if (len == 0)
      return 0;

   if (file->sector_buffer_valid)
   {
      size_t remaining = 2048 - file->current_sector_offset;
      if (remaining > 0)
      {
         if (remaining >= len)
         {
            memcpy(buffer,
                  &file->sector_buffer[file->current_sector_offset],
                  (size_t)len);
            file->current_sector_offset += len;
            return len;
         }

         memcpy(buffer,
               &file->sector_buffer[file->current_sector_offset], remaining);
         buffer      = (char*)buffer + remaining;
         bytes_read += remaining;
         len        -= remaining;

         file->current_sector_offset += remaining;
      }

      ++file->current_sector;
      file->current_sector_offset = 0;
      file->sector_buffer_valid   = 0;
   }
   else if (file->current_sector < file->first_sector)
   {
      file->current_sector        = file->first_sector;
      file->current_sector_offset = 0;
   }

   while (len >= 2048)
   {
      cdfs_seek_track_sector(file->track, file->current_sector);
      chdstream_read(file->track->stream, buffer, 2048);

      buffer      = (char*)buffer + 2048;
      bytes_read += 2048;

      ++file->current_sector;

      len        -= 2048;
   }

   if (len > 0)
   {
      cdfs_seek_track_sector(file->track, file->current_sector);
      chdstream_read(file->track->stream, file->sector_buffer, 2048);
      memcpy(buffer, file->sector_buffer, (size_t)len);
      file->current_sector_offset = (unsigned int)len;
      file->sector_buffer_valid   = 1;

      bytes_read += len;
   }

   file->pos += bytes_read;
   return bytes_read;
}

void cdfs_close_file(cdfs_file_t* file)
{
   if (file)
      file->first_sector = -1;
}

static cdfs_track_t* cdfs_wrap_stream(
      chdstream_t* stream,
      unsigned first_sector_offset,
      unsigned first_sector_index)
{
   cdfs_track_t* track = NULL;

   if (!stream)
      return NULL;

   track                      = (cdfs_track_t*)calloc(1, sizeof(*track));
   if (!track)
   {
      chdstream_close(stream);
      return NULL;
   }
   track->stream              = stream;
   track->first_sector_offset = first_sector_offset;
   track->first_sector_index  = first_sector_index;

   cdfs_determine_sector_size(track);

   return track;
}

cdfs_track_t* cdfs_open_chd_track(const char* path, int32_t track_index)
{
   cdfs_track_t* track;
   chdstream_t* stream = chdstream_open(path, track_index);
   if (!stream)
      return NULL;

   track = cdfs_wrap_stream(stream,
         chdstream_get_track_start(stream),
         chdstream_get_first_track_sector(stream));

   if (track && track->stream_sector_header_size == 0)
   {
      track->stream_sector_size = chdstream_get_frame_size(stream);

      if (track->stream_sector_size == 2352)
         track->stream_sector_header_size = 16;
      else if (track->stream_sector_size == 2336)
         track->stream_sector_header_size = 8;
   }

   return track;
}

cdfs_track_t* cdfs_open_data_track(const char* path)
{
   return cdfs_open_chd_track(path, CHDSTREAM_TRACK_PRIMARY);
}

void cdfs_close_track(cdfs_track_t* track)
{
   if (track)
   {
      if (track->stream)
         chdstream_close(track->stream);

      free(track);
   }
}
