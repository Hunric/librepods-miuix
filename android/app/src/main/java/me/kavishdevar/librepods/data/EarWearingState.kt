package me.kavishdevar.librepods.data

/** A fresh observation for a single control connection, or no usable observation yet. */
internal enum class EarWearingState(val allowsSettingsWrites: Boolean) {
    UNKNOWN(true),
    NOT_WORN(false),
    WORN(true),
}

/** Ignores short or unrelated frames; unsupported variants are an UNKNOWN observation. */
internal fun parseEarWearingState(data: ByteArray): EarWearingState? {
    val prefix = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x06)
    if (data.size < 8 || !data.copyOfRange(0, prefix.size).contentEquals(prefix)) return null
    if (data.size != 8 || data[5] != 0x00.toByte()) return EarWearingState.UNKNOWN

    val first = data[6]
    val second = data[7]
    return when {
        first == 0x00.toByte() || second == 0x00.toByte() -> EarWearingState.WORN
        (first == 0x01.toByte() || first == 0x02.toByte()) &&
            (second == 0x01.toByte() || second == 0x02.toByte()) -> EarWearingState.NOT_WORN
        else -> EarWearingState.UNKNOWN
    }
}
