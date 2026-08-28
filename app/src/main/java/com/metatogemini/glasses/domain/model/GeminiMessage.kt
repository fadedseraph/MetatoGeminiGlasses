package com.metatogemini.glasses.domain.model

sealed interface GeminiMessage {
    data object SetupComplete : GeminiMessage

    data class AudioData(
        val pcmBytes: ByteArray,
        val sampleRate: Int = 24000
    ) : GeminiMessage {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AudioData
            if (!pcmBytes.contentEquals(other.pcmBytes)) return false
            if (sampleRate != other.sampleRate) return false
            return true
        }

        override fun hashCode(): Int {
            var result = pcmBytes.contentHashCode()
            result = 31 * result + sampleRate
            return result
        }
    }

    data class TextData(
        val text: String,
        val isFromUser: Boolean = false
    ) : GeminiMessage

    data class Interruption(
        val timestampMs: Long = System.currentTimeMillis()
    ) : GeminiMessage

    data class TurnComplete(
        val isComplete: Boolean = true
    ) : GeminiMessage

    data class Error(
        val throwable: Throwable,
        val message: String? = throwable.message
    ) : GeminiMessage
}
