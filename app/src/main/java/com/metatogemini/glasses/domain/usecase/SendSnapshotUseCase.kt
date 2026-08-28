package com.metatogemini.glasses.domain.usecase

import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import com.metatogemini.glasses.domain.repository.SettingsRepository
import com.metatogemini.glasses.domain.repository.SnapshotRepository

/**
 * UseCase to capture a high-resolution snapshot and send it to Gemini Multimodal REST API.
 */
class SendSnapshotUseCase(
    private val snapshotRepository: SnapshotRepository,
    private val settingsRepository: SettingsRepository
) {

    suspend operator fun invoke(
        request: SnapshotRequest,
        customConfig: SessionConfig? = null
    ): Result<SnapshotResponse> {
        val config = customConfig ?: settingsRepository.getSessionConfig()
        return snapshotRepository.analyzeSnapshot(request, config)
    }

    suspend operator fun invoke(
        imageBytes: ByteArray,
        prompt: String = "Describe what you see in detail."
    ): Result<SnapshotResponse> {
        val request = SnapshotRequest(imageBytes = imageBytes, prompt = prompt)
        val config = settingsRepository.getSessionConfig()
        return snapshotRepository.analyzeSnapshot(request, config)
    }
}
