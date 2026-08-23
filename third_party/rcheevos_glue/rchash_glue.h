/* rchash_glue - the single entry point RAOfflineProxy uses to identify a ROM.
 *
 * Wraps rcheevos' rc_hash iterator so one call covers every supported format
 * (cartridge, disc, zip, playlist). CHD discs are routed through the vendored
 * chd_stream + cdfs_chd layer (libchdr), mirroring RetroArch's cheevos.c.
 *
 * Bound from Kotlin via JNI (RcHashNativeBridge) and from Python via ctypes.
 */
#ifndef RAO_RCHASH_GLUE_H
#define RAO_RCHASH_GLUE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Generates RetroAchievements hash candidates for the file at `path`.
 *
 * `out_hashes` is a caller-allocated flat buffer of `max_hashes * 33` bytes;
 * candidate i is written as a NUL-terminated 32-char hex string at
 * out_hashes + i * 33, in rc_hash iterator order (most-likely console first).
 *
 * Returns the number of candidates written (0 if the file could not be
 * hashed). Duplicate hashes are collapsed.
 */
int raproxy_hash_file(const char* path, char* out_hashes, int max_hashes);

/* Random-access callbacks over a logical (decompressed) disc image. */
typedef long long (*raproxy_ds_size_fn)(void* ctx);
typedef int (*raproxy_ds_read_fn)(void* ctx, long long offset, void* buffer, int bytes);

/* Hashes a GameCube/Wii disc whose bytes are supplied by the given random-access
 * callbacks (used for container formats — RVZ/CISO/GCZ/WBFS — that the caller
 * decompresses on the fly). rc_hash reads the *raw* disc layout through these,
 * so the callbacks must present the decompressed image. Tries GameCube then Wii.
 *
 * `out_hashes` is the same flat `max_hashes * 33` buffer as raproxy_hash_file.
 * Returns the number of candidates written.
 */
int raproxy_hash_disc_datasource(void* ctx, raproxy_ds_size_fn size_fn,
                                 raproxy_ds_read_fn read_fn,
                                 char* out_hashes, int max_hashes);

/* Lists the file entries of the .7z at `path` as consecutive NUL-terminated
 * UTF-8 strings in `out_names`. Returns the entry count, or -1 if the archive
 * could not be read or does not fit in the supplied buffer.
 *
 * rc_hash cannot read 7z: it maps the extension to arcade, which hashes the
 * filename. Callers use this plus raproxy_hash_7z_entry to hash a console ROM
 * stored inside a .7z, mirroring what they already do for .zip.
 */
int raproxy_7z_list_entries(const char* path, char* out_names,
                            int out_names_bytes, int max_entries);

/* Decompresses `entry_name` out of the .7z at `path` and hashes its contents,
 * picking the console from the entry's own filename. Returns the number of
 * candidates written to `out_hashes` (the same flat `max_hashes * 33` buffer as
 * raproxy_hash_file), or 0 if the entry could not be extracted — callers then
 * fall back to the arcade hash of the archive name.
 */
int raproxy_hash_7z_entry(const char* path, const char* entry_name,
                          char* out_hashes, int max_hashes);

#ifdef __cplusplus
}
#endif

#endif /* RAO_RCHASH_GLUE_H */
