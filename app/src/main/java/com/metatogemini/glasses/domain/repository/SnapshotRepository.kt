package com.metatogemini.glasses.domain.repository

import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse

interface SnapshotRepository {
    suspend fun analyzeSnapshot(
        request: SnapshotRequest,
        config: SessionConfig
    ): Result<SnapshotResponse>
}
