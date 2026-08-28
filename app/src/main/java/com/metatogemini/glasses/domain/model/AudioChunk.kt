package com.metatogemini.glasses.domain.model

data class AudioChunk(
    val data: ByteArray,
    val sampleRate: Int = 16000,
    val timestampMs: Long = System.currentTimeMillis(),
    val rms: Float = 0.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunk
        if (!data.contentEquals(other.data)) return false
        if (sampleRate != other.sampleRate) return false
        if (timestampMs != other.timestampMs) return false
        if (rms != other.rms) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + rms.hashCode()
        return result
    }
}
