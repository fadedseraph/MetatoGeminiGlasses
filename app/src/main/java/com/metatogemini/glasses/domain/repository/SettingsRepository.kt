package com.metatogemini.glasses.domain.repository

import com.metatogemini.glasses.domain.model.SessionConfig
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val sessionConfig: Flow<SessionConfig>
    suspend fun getSessionConfig(): SessionConfig
    suspend fun updateApiKey(apiKey: String)
    suspend fun updateModel(model: String)
    suspend fun updateVoice(voice: String)
    suspend fun updateSystemInstruction(instruction: String)
    suspend fun updateMockMode(enabled: Boolean)
    suspend fun updateLoopbackMode(enabled: Boolean)
    suspend fun updateCameraFps(fps: Int)
    suspend fun updateAutoAnalyzeGlassesPhotos(enabled: Boolean)
    suspend fun updateConfig(config: SessionConfig)
}
