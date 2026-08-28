package com.metatogemini.glasses.domain.model

data class SnapshotRequest(
    val imageBytes: ByteArray,
    val prompt: String = "Describe what you see in detail.",
    val mimeType: String = "image/jpeg"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SnapshotRequest
        if (!imageBytes.contentEquals(other.imageBytes)) return false
        if (prompt != other.prompt) return false
        if (mimeType != other.mimeType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + prompt.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}
