package com.metatogemini.glasses.domain.model

data class SnapshotResponse(
    val text: String,
    val latencyMs: Long = 0L,
    val finishReason: String? = "STOP"
)
