package com.raofflineproxy.proxy.hash

/**
 * JNI bridge to the unified rcheevos rc_hash hasher (libraproxy_rchash).
 *
 * One call covers every supported format — cartridge, disc (incl. CHD via the
 * bundled libchdr reader), `.cue`, `.m3u` — returning RetroAchievements hash
 * candidates in iterator order (most-likely console first). Zipped console ROMs
 * are extracted by the caller before hashing; see [hashRom].
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

    @JvmStatic private external fun nativeHashFile(path: String): Array<String>

    @JvmStatic private external fun nativeHashDiscDataSource(dataSource: RomDataSource): Array<String>
}
