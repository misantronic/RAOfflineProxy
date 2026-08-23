package com.raofflineproxy.proxy.hash

/**
 * JNI bridge to the unified rcheevos rc_hash hasher (libraproxy_rchash).
 *
 * One call covers every supported format — cartridge, disc (incl. CHD via the
 * bundled libchdr reader), `.cue`, `.m3u` — returning RetroAchievements hash
 * candidates in iterator order (most-likely console first). Zipped console ROMs
 * are extracted by the caller before hashing; see [hashRom].
 *
 * `.7z` is the exception rc_hash cannot read at all, so the same library
 * bundles a 7z reader: [list7zEntries] and [hash7zEntry] open the archive and
 * hash one entry by its contents.
 */
internal object RcHashNativeBridge {
    private val available by lazy {
        runCatching {
            System.loadLibrary("raproxy_rchash")
            true
        }.getOrDefault(false)
    }

    fun isAvailable(): Boolean = available

    /** Returns hash candidates for the file at [path], or an empty list on failure. */
    fun hashFile(path: String): List<String> {
        if (!available) return emptyList()
        return runCatching { nativeHashFile(path).toList() }.getOrDefault(emptyList())
    }

    /**
     * Returns GameCube/Wii hash candidates for a disc whose decompressed bytes
     * are served by [dataSource]. Used for container formats (RVZ/CISO/GCZ/WBFS,
     * raw GCM) that rc_hash can't decompress itself.
     */
    fun hashDiscDataSource(dataSource: RomDataSource): List<String> {
        if (!available) return emptyList()
        return runCatching { nativeHashDiscDataSource(dataSource).toList() }.getOrDefault(emptyList())
    }

    /** Names of the files inside the .7z at [path], empty if unreadable. */
    fun list7zEntries(path: String): List<String> {
        if (!available) return emptyList()
        return runCatching { nativeList7zEntries(path).toList() }.getOrDefault(emptyList())
    }

    /**
     * Hash candidates for [entryName] inside the .7z at [path], hashed by the
     * entry's own decompressed content. Empty when the entry cannot be
     * extracted — an unsupported codec (LZMA, LZMA2, PPMd and stored entries
     * are covered) or one above the native size cap.
     */
    fun hash7zEntry(path: String, entryName: String): List<String> {
        if (!available) return emptyList()
        return runCatching { nativeHash7zEntry(path, entryName).toList() }.getOrDefault(emptyList())
    }

    @JvmStatic private external fun nativeHashFile(path: String): Array<String>

    @JvmStatic private external fun nativeHashDiscDataSource(dataSource: RomDataSource): Array<String>

    @JvmStatic private external fun nativeList7zEntries(path: String): Array<String>

    @JvmStatic private external fun nativeHash7zEntry(path: String, entryName: String): Array<String>
}
