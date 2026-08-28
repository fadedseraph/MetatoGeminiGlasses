package com.metatogemini.glasses.data.repository

import com.metatogemini.glasses.data.local.DataStoreManager
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Implementation of [SettingsRepository] delegating persistence to [DataStoreManager].
 */
class SettingsRepositoryImpl(
    private val dataStoreManager: DataStoreManager
) : SettingsRepository {

    override val sessionConfig: Flow<SessionConfig> = dataStoreManager.sessionConfigFlow

    override suspend fun getSessionConfig(): SessionConfig {
        return dataStoreManager.getSessionConfig()
    }

    override suspend fun updateApiKey(apiKey: String) {
        dataStoreManager.updateApiKey(apiKey)
    }

    override suspend fun updateModel(model: String) {
        dataStoreManager.updateModel(model)
    }

    override suspend fun updateVoice(voice: String) {
        dataStoreManager.updateVoice(voice)
    }

    override suspend fun updateSystemInstruction(instruction: String) {
        dataStoreManager.updateSystemInstruction(instruction)
    }

    override suspend fun updateMockMode(enabled: Boolean) {
        dataStoreManager.updateMockMode(enabled)
    }

    override suspend fun updateLoopbackMode(enabled: Boolean) {
        dataStoreManager.updateLoopbackMode(enabled)
    }

    override suspend fun updateCameraFps(fps: Int) {
        dataStoreManager.updateCameraFps(fps)
    }

    override suspend fun updateAutoAnalyzeGlassesPhotos(enabled: Boolean) {
        dataStoreManager.updateAutoAnalyzeGlassesPhotos(enabled)
    }

    override suspend fun updateConfig(config: SessionConfig) {
        dataStoreManager.updateSessionConfig(config)
    }
}
