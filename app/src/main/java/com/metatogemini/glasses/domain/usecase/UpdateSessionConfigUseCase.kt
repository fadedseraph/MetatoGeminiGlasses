package com.metatogemini.glasses.domain.usecase

import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.repository.SettingsRepository

/**
 * UseCase to update application settings and session configuration in persistent storage.
 */
class UpdateSessionConfigUseCase(
    private val settingsRepository: SettingsRepository
) {

    suspend operator fun invoke(config: SessionConfig) {
        settingsRepository.updateConfig(config)
    }

    suspend fun updateApiKey(apiKey: String) {
        settingsRepository.updateApiKey(apiKey)
    }

    suspend fun updateModel(model: String) {
        settingsRepository.updateModel(model)
    }

    suspend fun updateVoice(voice: String) {
        settingsRepository.updateVoice(voice)
    }

    suspend fun updateSystemInstruction(instruction: String) {
        settingsRepository.updateSystemInstruction(instruction)
    }

    suspend fun updateMockMode(enabled: Boolean) {
        settingsRepository.updateMockMode(enabled)
    }

    suspend fun updateLoopbackMode(enabled: Boolean) {
        settingsRepository.updateLoopbackMode(enabled)
    }

    suspend fun updateCameraFps(fps: Int) {
        settingsRepository.updateCameraFps(fps)
    }

    suspend fun updateAutoAnalyzeGlassesPhotos(enabled: Boolean) {
        settingsRepository.updateAutoAnalyzeGlassesPhotos(enabled)
    }
}
