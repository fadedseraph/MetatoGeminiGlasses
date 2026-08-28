package com.metatogemini.glasses.domain.model

data class StreamMetrics(
    val latencyMs: Long = 0L,
    val cameraFps: Float = 0.0f,
    val micRms: Float = 0.0f,
    val speakerRms: Float = 0.0f,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L
)
