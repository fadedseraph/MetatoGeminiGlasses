package com.metatogemini.glasses.domain.usecase

import com.metatogemini.glasses.domain.repository.LiveSessionRepository
import com.metatogemini.glasses.media.audio.AudioPlaybackManager

/**
 * UseCase to terminate an active live session and immediately stop audio output.
 */
class StopLiveSessionUseCase(
    private val liveSessionRepository: LiveSessionRepository,
    private val audioPlaybackManager: AudioPlaybackManager
) {

    suspend operator fun invoke() {
        audioPlaybackManager.stopAndClear()
        liveSessionRepository.stopSession()
    }
}
