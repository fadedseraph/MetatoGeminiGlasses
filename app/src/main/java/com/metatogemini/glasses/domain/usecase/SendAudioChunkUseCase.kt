package com.metatogemini.glasses.domain.usecase

import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.domain.model.AudioChunk
import com.metatogemini.glasses.domain.repository.LiveSessionRepository

/**
 * UseCase for streaming 16kHz PCM audio chunks to Gemini Live WebSocket.
 */
class SendAudioChunkUseCase(
    private val liveSessionRepository: LiveSessionRepository
) {

    suspend operator fun invoke(pcmData: ByteArray, sampleRate: Int = Constants.SAMPLE_RATE_CAPTURE_HZ) {
        liveSessionRepository.sendAudioChunk(pcmData, sampleRate)
    }

    suspend operator fun invoke(chunk: AudioChunk) {
        liveSessionRepository.sendAudioChunk(chunk.data, chunk.sampleRate)
    }
}
