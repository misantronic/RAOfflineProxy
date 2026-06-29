/* See cdfs_pbp.h. Layout and decompression mirror DuckStation's CDImagePBP
 * (GPLv3) so produced PS1 hashes match RetroAchievements exactly. */
#define _FILE_OFFSET_BITS 64

#include "cdfs_pbp.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "miniz.h"

#define PBP_DATA_PSAR_OFFSET     0x24
#define PSISOIMG_PGD_OFFSET      0x400
#define PSISOIMG_ISO_OFFSET      0xBFC
#define PSISOIMG_BLOCK_TABLE     0x4000
#define PSTITLE_DISC_TABLE       0x200
#define BLOCK_TABLE_NUM_ENTRIES  32256
#define BLOCK_TABLE_ENTRY_SIZE   0x20
#define DECOMPRESSED_BLOCK_SIZE  37632u /* 16 * 2352 */
#define RAW_SECTOR_SIZE          2352
#define SECTORS_PER_BLOCK        16
#define COOKED_SECTOR_SIZE       2048
#define PGD_MAGIC                0x44475000u /* "\0PGD" - encrypted, unsupported */

typedef struct {
   int64_t  offset; /* absolute file offset of the block */
   uint32_t size;   /* compressed size; == DECOMPRESSED_BLOCK_SIZE when stored raw */
} pbp_block_t;

struct pbp_disc_t {
   FILE*        file;
   pbp_block_t* blocks;
   uint32_t     block_count;
   uint32_t     sector_count;
   uint32_t     header_size;   /* cooked offset within a raw sector: 24 or 16 */
   int          cached_block;  /* index currently in cache, -1 if none */
   mz_stream    zs;
   int          zs_inited;
   uint8_t      cache[DECOMPRESSED_BLOCK_SIZE];
};

static int read_u32_le(FILE* f, int64_t off, uint32_t* out)
{
   uint8_t b[4];
   if (fseeko(f, (off_t)off, SEEK_SET) != 0)
      return 0;
   if (fread(b, 1, 4, f) != 4)
      return 0;
   *out = (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
   return 1;
}

static int pbp_load_block(pbp_disc_t* disc, uint32_t block_index)
{
   pbp_block_t* block;

   if ((int)block_index == disc->cached_block)
      return 1;
   if (block_index >= disc->block_count)
      return 0;

   block = &disc->blocks[block_index];
   if (fseeko(disc->file, (off_t)block->offset, SEEK_SET) != 0)
      return 0;

   if (block->size == DECOMPRESSED_BLOCK_SIZE)
   {
      if (fread(disc->cache, 1, DECOMPRESSED_BLOCK_SIZE, disc->file) != DECOMPRESSED_BLOCK_SIZE)
         return 0;
   }
   else
   {
      uint8_t* compressed = (uint8_t*)malloc(block->size);
      int err;

      if (!compressed)
         return 0;
      if (fread(compressed, 1, block->size, disc->file) != block->size)
      {
         free(compressed);
         return 0;
      }

      disc->zs.next_in   = compressed;
      disc->zs.avail_in  = (unsigned int)block->size;
      disc->zs.next_out  = disc->cache;
      disc->zs.avail_out = DECOMPRESSED_BLOCK_SIZE;

      if (mz_inflateReset(&disc->zs) != MZ_OK)
      {
         free(compressed);
         return 0;
      }
      err = mz_inflate(&disc->zs, MZ_FINISH);
      free(compressed);
      if (err != MZ_STREAM_END)
         return 0;
   }

   disc->cached_block = (int)block_index;
   return 1;
}

static int pbp_read_raw_sector(pbp_disc_t* disc, uint32_t sector, uint8_t* out2352)
{
   uint32_t block_index = sector / SECTORS_PER_BLOCK;
   uint32_t in_block    = sector % SECTORS_PER_BLOCK;

   if (!pbp_load_block(disc, block_index))
      return 0;

   memcpy(out2352, disc->cache + (size_t)in_block * RAW_SECTOR_SIZE, RAW_SECTOR_SIZE);
   return 1;
}

static int pbp_locate_disc(FILE* f, int64_t* iso_header_start)
{
   uint8_t magic[16];
   uint32_t psar_off;

   if (fread(magic, 1, 4, f) != 4)
      return 0;
   if (magic[0] != 0x00 || magic[1] != 'P' || magic[2] != 'B' || magic[3] != 'P')
      return 0;

   if (!read_u32_le(f, PBP_DATA_PSAR_OFFSET, &psar_off))
      return 0;

   if (fseeko(f, (off_t)psar_off, SEEK_SET) != 0 || fread(magic, 1, 16, f) != 16)
      return 0;

   if (memcmp(magic, "PSTITLEIMG000000", 16) == 0)
   {
      /* multi-disc: first disc offset (relative to DATA.PSAR) lives at +0x200 */
      uint32_t disc_rel;
      if (!read_u32_le(f, (int64_t)psar_off + PSTITLE_DISC_TABLE, &disc_rel) || disc_rel == 0)
         return 0;
      *iso_header_start = (int64_t)psar_off + (int64_t)disc_rel;

      if (fseeko(f, (off_t)*iso_header_start, SEEK_SET) != 0 || fread(magic, 1, 12, f) != 12)
         return 0;
      return memcmp(magic, "PSISOIMG0000", 12) == 0;
   }

   if (memcmp(magic, "PSISOIMG0000", 12) == 0)
   {
      *iso_header_start = (int64_t)psar_off;
      return 1;
   }

   return 0; /* not a PS1 POPS image (likely a real PSP EBOOT) */
}

static void pbp_detect_header_size(pbp_disc_t* disc)
{
   uint8_t raw[RAW_SECTOR_SIZE];

   disc->header_size = 24; /* MODE2/2352 (CD-ROM XA), the PS1 norm */

   /* The primary volume descriptor at sector 16 carries a "CD001" marker whose
    * byte offset reveals the raw sector layout (24-byte vs 16-byte header). */
   if (pbp_read_raw_sector(disc, 16, raw))
   {
      if (memcmp(raw + 25, "CD001", 5) == 0)
         disc->header_size = 24;
      else if (memcmp(raw + 17, "CD001", 5) == 0)
         disc->header_size = 16;
   }
}

pbp_disc_t* pbp_open(const char* path)
{
   FILE* f;
   int64_t iso_header_start = 0;
   uint32_t pgd = 0;
   uint32_t iso_offset = 0;
   uint32_t count = 0;
   uint32_t i;
   pbp_disc_t* disc;

   if (!path)
      return NULL;

   f = fopen(path, "rb");
   if (!f)
      return NULL;

   if (!pbp_locate_disc(f, &iso_header_start))
   {
      fclose(f);
      return NULL;
   }

   if (read_u32_le(f, iso_header_start + PSISOIMG_PGD_OFFSET, &pgd) && pgd == PGD_MAGIC)
   {
      fclose(f); /* encrypted image: cannot read the filesystem */
      return NULL;
   }

   if (!read_u32_le(f, iso_header_start + PSISOIMG_ISO_OFFSET, &iso_offset))
   {
      fclose(f);
      return NULL;
   }

   disc = (pbp_disc_t*)calloc(1, sizeof(*disc));
   if (!disc)
   {
      fclose(f);
      return NULL;
   }
   disc->file         = f;
   disc->cached_block = -1;
   disc->blocks       = (pbp_block_t*)malloc(sizeof(pbp_block_t) * BLOCK_TABLE_NUM_ENTRIES);
   if (!disc->blocks)
   {
      pbp_close(disc);
      return NULL;
   }

   if (fseeko(f, (off_t)(iso_header_start + PSISOIMG_BLOCK_TABLE), SEEK_SET) != 0)
   {
      pbp_close(disc);
      return NULL;
   }

   for (i = 0; i < BLOCK_TABLE_NUM_ENTRIES; ++i)
   {
      uint8_t entry[BLOCK_TABLE_ENTRY_SIZE];
      uint32_t block_off;
      uint32_t block_size;

      if (fread(entry, 1, sizeof(entry), f) != sizeof(entry))
         break;

      block_off  = (uint32_t)entry[0] | ((uint32_t)entry[1] << 8) | ((uint32_t)entry[2] << 16) | ((uint32_t)entry[3] << 24);
      block_size = (uint32_t)entry[4] | ((uint32_t)entry[5] << 8);

      if (block_size == 0)
         break; /* end of valid blocks */

      disc->blocks[i].offset = iso_header_start + (int64_t)iso_offset + (int64_t)block_off;
      disc->blocks[i].size   = block_size;
      count                  = i + 1;
   }

   if (count == 0)
   {
      pbp_close(disc);
      return NULL;
   }
   disc->block_count  = count;
   disc->sector_count = count * SECTORS_PER_BLOCK;

   if (mz_inflateInit2(&disc->zs, -MZ_DEFAULT_WINDOW_BITS) != MZ_OK)
   {
      pbp_close(disc);
      return NULL;
   }
   disc->zs_inited = 1;

   pbp_detect_header_size(disc);
   return disc;
}

size_t pbp_read_sector(pbp_disc_t* disc, uint32_t sector, void* buffer, size_t requested_bytes)
{
   uint8_t raw[RAW_SECTOR_SIZE];
   size_t copy;

   if (!disc || !buffer || sector >= disc->sector_count)
      return 0;
   if (!pbp_read_raw_sector(disc, sector, raw))
      return 0;

   copy = (requested_bytes < COOKED_SECTOR_SIZE) ? requested_bytes : COOKED_SECTOR_SIZE;
   memcpy(buffer, raw + disc->header_size, copy);
   return copy;
}

uint32_t pbp_first_sector(pbp_disc_t* disc)
{
   (void)disc;
   return 0;
}

void pbp_close(pbp_disc_t* disc)
{
   if (!disc)
      return;
   if (disc->zs_inited)
      mz_inflateEnd(&disc->zs);
   if (disc->blocks)
      free(disc->blocks);
   if (disc->file)
      fclose(disc->file);
   free(disc);
}
