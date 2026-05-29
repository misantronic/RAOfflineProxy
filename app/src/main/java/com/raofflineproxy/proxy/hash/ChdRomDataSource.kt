package com.raofflineproxy.proxy.hash

import java.io.File

internal class ChdRomDataSource private constructor(
    private val handle: Long,
    override val length: Long
) : RomDataSource {
    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        if (offset < 0L || length <= 0 || offset >= this.length) {
            return -1
        }
        return runCatching {
            ChdNativeBridge.read(handle, offset, buffer, length)
        }.getOrDefault(-1)
    }

    override fun close() {
        ChdNativeBridge.close(handle)
    }

    companion object {
        fun open(file: File): ChdRomDataSource? {
            if (!ChdNativeBridge.isAvailable()) {
                return null
            }

            var handle = 0L
            return runCatching {
                handle = ChdNativeBridge.open(file.absolutePath)
                ChdRomDataSource(handle, ChdNativeBridge.length(handle))
            }.getOrElse {
                if (handle != 0L) {
                    ChdNativeBridge.close(handle)
                }
                null
            }
        }
    }
}
