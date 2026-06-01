package com.raofflineproxy.proxy.hash

import java.io.File

private const val TAG = "RAProxy/PsxChdHash"

internal class PsxChdRomDataSource private constructor(
    private val handle: Long,
    override val length: Long
) : RomDataSource {
    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        if (offset < 0L || length <= 0 || offset >= this.length) {
            return -1
        }
        return runCatching {
            CdChdNativeBridge.read(handle, offset, buffer, length)
        }.getOrDefault(-1)
    }

    override fun close() {
        CdChdNativeBridge.close(handle)
    }

    companion object {
        fun open(file: File): PsxChdRomDataSource? {
            if (!CdChdNativeBridge.isAvailable()) {
                logWarn(TAG, "Native bridge unavailable for ${file.name}")
                return null
            }

            var handle = 0L
            return runCatching {
                handle = CdChdNativeBridge.open(file.absolutePath)
                val length = CdChdNativeBridge.length(handle)
                if (length <= 0L) {
                    error("Invalid CHD logical length=$length for ${file.name}")
                }
                logInfo(TAG, "Opened ${file.name} length=$length")
                PsxChdRomDataSource(handle, length)
            }.getOrElse {
                if (handle != 0L) {
                    CdChdNativeBridge.close(handle)
                }
                logWarn(TAG, "Failed to open ${file.name}: ${it.message ?: it::class.java.simpleName}")
                null
            }
        }
    }
}
