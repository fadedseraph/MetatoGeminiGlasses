package com.metatogemini.glasses.domain.usecase

import com.metatogemini.glasses.domain.repository.LiveSessionRepository

/**
 * UseCase for streaming JPEG camera frames to Gemini Live WebSocket.
 */
class SendVideoFrameUseCase(
    private val liveSessionRepository: LiveSessionRepository
) {

    suspend operator fun invoke(jpegData: ByteArray) {
        liveSessionRepository.sendVideoFrame(jpegData)
    }
}
