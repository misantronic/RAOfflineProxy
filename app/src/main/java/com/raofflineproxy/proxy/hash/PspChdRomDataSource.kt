package com.raofflineproxy.proxy.hash

import java.io.File

private const val TAG = "RAProxy/PspChdHash"

internal class PspChdRomDataSource private constructor(
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
        fun open(file: File): PspChdRomDataSource? {
            if (!ChdNativeBridge.isAvailable()) {
                logWarn(TAG, "Native bridge unavailable for ${file.name}")
                return null
            }

            var handle = 0L
            return runCatching {
                handle = ChdNativeBridge.open(file.absolutePath)
                val length = ChdNativeBridge.length(handle)
                if (length <= 0L) {
                    error("Invalid CHD logical length=$length for ${file.name}")
                }
                logInfo(TAG, "Opened ${file.name} length=$length")
                PspChdRomDataSource(handle, length)
            }.getOrElse {
                if (handle != 0L) {
                    ChdNativeBridge.close(handle)
                }
                logWarn(TAG, "Failed to open ${file.name}: ${it.message ?: it::class.java.simpleName}")
                null
            }
        }
    }
}
