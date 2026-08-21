#include "sevenzip.h"

#include <string.h>

#include "7z.h"
#include "7zAlloc.h"
#include "7zCrc.h"
#include "7zFile.h"

/* A ROM that needs more than this is not a cartridge, and decompressing it
 * would be a bad idea on a 128 MB handheld. Disc images inside archives are
 * not supported by any of the callers. */
#define RAO_7Z_MAX_ENTRY_BYTES ((size_t)128 * 1024 * 1024)
#define RAO_7Z_LOOK_BUF_BYTES ((size_t)1 << 16)

static const ISzAlloc g_rao_alloc = { SzAlloc, SzFree };
static const ISzAlloc g_rao_alloc_temp = { SzAllocTemp, SzFreeTemp };

typedef struct
{
   CFileInStream stream;
   CLookToRead2 look;
   CSzArEx db;
   int stream_open;
   int db_open;
} rao_7z_archive;

static void rao_7z_crc_init(void)
{
   static int initialized = 0;
   if (!initialized)
   {
      CrcGenerateTable();
      initialized = 1;
   }
}

/* Returns the number of bytes written (excluding the terminator), or -1 if the
 * name does not fit. Unpaired surrogates are passed through as-is; they can't
 * match a caller's extension check either way. */
static int rao_utf16_to_utf8(const UInt16* src, char* dest, size_t dest_size)
{
   size_t pos = 0;

   for (;;)
   {
      unsigned value = *src++;

      if (value == 0)
         break;

      if (value >= 0xD800 && value < 0xDC00)
      {
         const unsigned low = *src;
         if (low >= 0xDC00 && low < 0xE000)
         {
            ++src;
            value = 0x10000 + ((value - 0xD800) << 10) + (low - 0xDC00);
         }
      }

      if (value < 0x80)
      {
         if (pos + 1 >= dest_size)
            return -1;
         dest[pos++] = (char)value;
      }
      else if (value < 0x800)
      {
         if (pos + 2 >= dest_size)
            return -1;
         dest[pos++] = (char)(0xC0 | (value >> 6));
         dest[pos++] = (char)(0x80 | (value & 0x3F));
      }
      else if (value < 0x10000)
      {
         if (pos + 3 >= dest_size)
            return -1;
         dest[pos++] = (char)(0xE0 | (value >> 12));
         dest[pos++] = (char)(0x80 | ((value >> 6) & 0x3F));
         dest[pos++] = (char)(0x80 | (value & 0x3F));
      }
      else
      {
         if (pos + 4 >= dest_size)
            return -1;
         dest[pos++] = (char)(0xF0 | (value >> 18));
         dest[pos++] = (char)(0x80 | ((value >> 12) & 0x3F));
         dest[pos++] = (char)(0x80 | ((value >> 6) & 0x3F));
         dest[pos++] = (char)(0x80 | (value & 0x3F));
      }
   }

   if (pos >= dest_size)
      return -1;

   dest[pos] = '\0';
   return (int)pos;
}

static void rao_7z_close(rao_7z_archive* archive)
{
   if (archive->db_open)
   {
      SzArEx_Free(&archive->db, &g_rao_alloc);
      archive->db_open = 0;
   }
   if (archive->look.buf)
   {
      ISzAlloc_Free(&g_rao_alloc, archive->look.buf);
      archive->look.buf = NULL;
   }
   if (archive->stream_open)
   {
      File_Close(&archive->stream.file);
      archive->stream_open = 0;
   }
}

static int rao_7z_open(rao_7z_archive* archive, const char* path)
{
   memset(archive, 0, sizeof(*archive));

   rao_7z_crc_init();

   if (InFile_Open(&archive->stream.file, path) != 0)
      return 0;
   archive->stream_open = 1;

   FileInStream_CreateVTable(&archive->stream);
   LookToRead2_CreateVTable(&archive->look, False);

   archive->look.buf = (Byte*)ISzAlloc_Alloc(&g_rao_alloc, RAO_7Z_LOOK_BUF_BYTES);
   if (!archive->look.buf)
   {
      rao_7z_close(archive);
      return 0;
   }

   archive->look.bufSize = RAO_7Z_LOOK_BUF_BYTES;
   archive->look.realStream = &archive->stream.vt;
   LookToRead2_INIT(&archive->look);

   SzArEx_Init(&archive->db);
   if (SzArEx_Open(&archive->db, &archive->look.vt, &g_rao_alloc, &g_rao_alloc_temp) != SZ_OK)
   {
      rao_7z_close(archive);
      return 0;
   }
   archive->db_open = 1;

   return 1;
}

/* Reads entry `index`'s name as UTF-8. Returns 0 if it does not fit in `dest`. */
static int rao_7z_entry_name(const CSzArEx* db, UInt32 index, char* dest, size_t dest_size)
{
   UInt16 name_utf16[512];
   const size_t name_len = SzArEx_GetFileNameUtf16(db, index, NULL);

   if (name_len == 0 || name_len > sizeof(name_utf16) / sizeof(name_utf16[0]))
      return 0;

   SzArEx_GetFileNameUtf16(db, index, name_utf16);
   return rao_utf16_to_utf8(name_utf16, dest, dest_size) >= 0;
}

int rao_7z_list_entries(const char* path, char* out_names, int out_names_bytes,
                        int max_entries)
{
   rao_7z_archive archive;
   UInt32 index;
   int count = 0;
   int used = 0;

   if (!path || !out_names || out_names_bytes <= 0 || max_entries <= 0)
      return -1;

   if (!rao_7z_open(&archive, path))
      return -1;

   for (index = 0; index < archive.db.NumFiles; ++index)
   {
      int written;

      if (SzArEx_IsDir(&archive.db, index))
         continue;

      if (count >= max_entries)
      {
         rao_7z_close(&archive);
         return -1;
      }

      if (!rao_7z_entry_name(&archive.db, index, out_names + used,
                             (size_t)(out_names_bytes - used)))
      {
         rao_7z_close(&archive);
         return -1;
      }

      written = (int)strlen(out_names + used);
      used += written + 1;
      ++count;
   }

   rao_7z_close(&archive);
   return count;
}

int rao_7z_extract_entry(const char* path, const char* entry_name,
                         unsigned char** out_data, size_t* out_offset,
                         size_t* out_size)
{
   rao_7z_archive archive;
   UInt32 index;
   UInt32 block_index = 0xFFFFFFFF;
   Byte* block = NULL;
   size_t block_size = 0;
   size_t offset = 0;
   size_t processed = 0;
   int found = 0;

   if (!path || !entry_name || !out_data || !out_offset || !out_size)
      return 0;

   *out_data = NULL;
   *out_offset = 0;
   *out_size = 0;

   if (!rao_7z_open(&archive, path))
      return 0;

   for (index = 0; index < archive.db.NumFiles; ++index)
   {
      char name[1024];

      if (SzArEx_IsDir(&archive.db, index))
         continue;

      if (!rao_7z_entry_name(&archive.db, index, name, sizeof(name)))
         continue;

      if (strcmp(name, entry_name) == 0)
      {
         found = 1;
         break;
      }
   }

   if (!found || SzArEx_GetFileSize(&archive.db, index) > RAO_7Z_MAX_ENTRY_BYTES)
   {
      rao_7z_close(&archive);
      return 0;
   }

   if (SzArEx_Extract(&archive.db, &archive.look.vt, index, &block_index, &block,
                      &block_size, &offset, &processed, &g_rao_alloc,
                      &g_rao_alloc_temp) != SZ_OK || processed == 0)
   {
      if (block)
         ISzAlloc_Free(&g_rao_alloc, block);
      rao_7z_close(&archive);
      return 0;
   }

   rao_7z_close(&archive);

   *out_data = (unsigned char*)block;
   *out_offset = offset;
   *out_size = processed;
   return 1;
}

void rao_7z_free(unsigned char* data)
{
   if (data)
      ISzAlloc_Free(&g_rao_alloc, data);
}
