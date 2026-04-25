package com.raofflineproxy.proxy.hash

import com.raofflineproxy.toHexString
import java.security.MessageDigest

private const val MAX_HASH_BYTES = 64L * 1024L * 1024L
private const val PSP_PARAM_PATH = "PSP_GAME\\PARAM.SFO"
private const val PSP_EBOOT_PATH = "PSP_GAME\\SYSDIR\\EBOOT.BIN"
private const val TAG = "RAProxy/PspHash"

internal object PspRomHashStrategy : RomHashStrategy {
    override fun matches(fileName: String): Boolean = hasExtension(fileName, "iso", "pbp")

    override fun hash(input: RomHashInput): String? {
        if (hasExtension(input.fileName, "pbp")) {
            return GenericMd5RomHashStrategy.hash(input)
        }

        val openDataSource = input.openDataSource ?: return null
        return openDataSource().use { dataSource ->
            if (dataSource == null) {
                logWarn(TAG, "No data source for ${input.fileName}")
                return@use null
            }

            val layout = detectIsoSectorLayout(dataSource)
            if (layout == null) {
                logInfo(TAG, "No ISO layout detected for ${input.fileName}")
                return@use null
            }
            logInfo(TAG, "Detected ISO layout rawSectorSize=${layout.rawSectorSize} dataOffset=${layout.dataOffset} sectorBias=${layout.sectorBias} file=${input.fileName}")

            val paramRecord = findFileRecord(dataSource, layout, PSP_PARAM_PATH)
            if (paramRecord == null) {
                logInfo(TAG, "Missing $PSP_PARAM_PATH in ${input.fileName}")
                return@use null
            }

            val ebootRecord = findFileRecord(dataSource, layout, PSP_EBOOT_PATH)
            if (ebootRecord == null) {
                logInfo(TAG, "Missing $PSP_EBOOT_PATH in ${input.fileName}")
                return@use null
            }

            logInfo(TAG, "Found PSP files paramSector=${paramRecord.sector} paramSize=${paramRecord.size} ebootSector=${ebootRecord.sector} ebootSize=${ebootRecord.size}")

            val digest = MessageDigest.getInstance("MD5")
            if (!updateDigestFromRecord(digest, dataSource, layout, paramRecord)) return@use null
            if (!updateDigestFromRecord(digest, dataSource, layout, ebootRecord)) return@use null
            digest.digest().toHexString()
        }
    }

    private fun updateDigestFromRecord(
        digest: MessageDigest,
        dataSource: RomDataSource,
        layout: SectorLayout,
        record: IsoFileRecord
    ): Boolean {
        var remaining = record.size.coerceAtMost(MAX_HASH_BYTES)
        var sectorIndex = record.sector
        while (remaining > 0) {
            val sector = readSector(dataSource, layout, sectorIndex)
            if (sector == null) {
                logWarn(TAG, "Failed reading sector=$sectorIndex remaining=$remaining")
                return false
            }
            val count = minOf(remaining.toInt(), sector.size)
            digest.update(sector, 0, count)
            remaining -= count
            sectorIndex++
        }
        return true
    }
}
