package com.metatogemini.glasses.domain.usecase

import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.repository.LiveSessionRepository
import com.metatogemini.glasses.domain.repository.SettingsRepository

/**
 * UseCase to initiate a live bidirectional streaming session with Gemini Live API.
 */
class StartLiveSessionUseCase(
    private val liveSessionRepository: LiveSessionRepository,
    private val settingsRepository: SettingsRepository
) {

    suspend operator fun invoke(customConfig: SessionConfig? = null) {
        val config = customConfig ?: settingsRepository.getSessionConfig()
        liveSessionRepository.startSession(config)
    }
}
