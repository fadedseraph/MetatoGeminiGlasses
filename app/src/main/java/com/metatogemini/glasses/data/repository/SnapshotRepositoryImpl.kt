package com.metatogemini.glasses.data.repository

import com.metatogemini.glasses.core.common.DispatchersProvider
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.data.network.rest.GeminiRestClient
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import com.metatogemini.glasses.domain.repository.SnapshotRepository
import kotlinx.coroutines.withContext

/**
 * Implementation of [SnapshotRepository] utilizing [GeminiRestClient].
 */
class SnapshotRepositoryImpl(
    private val restClient: GeminiRestClient,
    private val dispatchersProvider: DispatchersProvider
) : SnapshotRepository {

    override suspend fun analyzeSnapshot(
        request: SnapshotRequest,
        config: SessionConfig
    ): Result<SnapshotResponse> = withContext(dispatchersProvider.io) {
        if (config.apiKey.isBlank()) {
            return@withContext Result.Error(
                IllegalArgumentException("API key must not be empty"),
                "API key is missing in SessionConfig"
            )
        }

        restClient.analyzeSnapshot(
            apiKey = config.apiKey,
            model = config.model,
            request = request,
            systemInstruction = config.systemInstruction
        )
    }
}
