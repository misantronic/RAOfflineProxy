package com.raofflineproxy.proxy.hash

/** Reads a big-endian 32-bit int from [bytes] at [offset], or null if out of range. */
internal fun readBigEndianInt(bytes: ByteArray, offset: Int = 0): Int? {
    if (offset + 4 > bytes.size) return null
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}

/** Reads a little-endian 32-bit int from [bytes] at [offset], or 0 if out of range. */
internal fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
    if (offset + 4 > bytes.size) return 0
    return (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

/** Reads exactly [size] bytes from [dataSource] starting at [offset], or null on EOF. */
internal fun readBytes(dataSource: RomDataSource, offset: Long, size: Int): ByteArray? {
    if (size < 0) return null
    val buffer = ByteArray(size)
    var totalRead = 0
    while (totalRead < size) {
        val chunk = ByteArray(minOf(8192, size - totalRead))
        val read = dataSource.read(offset + totalRead, chunk, chunk.size)
        if (read <= 0) return null
        chunk.copyInto(buffer, destinationOffset = totalRead, endIndex = read)
        totalRead += read
    }
    return buffer
}
