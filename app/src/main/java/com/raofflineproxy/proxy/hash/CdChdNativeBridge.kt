package com.raofflineproxy.proxy.hash

import java.io.IOException

internal object CdChdNativeBridge {
    private val available by lazy {
        runCatching {
            System.loadLibrary("ra_chd_reader")
            true
        }.getOrDefault(false)
    }

    fun isAvailable(): Boolean = available

    @Throws(IOException::class)
    fun open(path: String): Long {
        check(available) { "CD CHD native bridge unavailable" }
        return nativeOpen(path)
    }

    fun length(handle: Long): Long = nativeLength(handle)

    @Throws(IOException::class)
    fun read(handle: Long, offset: Long, buffer: ByteArray, requestedLength: Int): Int =
        nativeRead(handle, offset, buffer, requestedLength)

    fun close(handle: Long) {
        if (handle != 0L) {
            nativeClose(handle)
        }
    }

    @JvmStatic private external fun nativeOpen(path: String): Long

    @JvmStatic private external fun nativeLength(handle: Long): Long

    @JvmStatic private external fun nativeRead(handle: Long, offset: Long, buffer: ByteArray, requestedLength: Int): Int

    @JvmStatic private external fun nativeClose(handle: Long)
}
