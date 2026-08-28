package com.metatogemini.glasses.domain.model

import com.metatogemini.glasses.core.common.Constants

data class SessionConfig(
    val apiKey: String = "",
    val model: String = Constants.DEFAULT_MODEL,
    val voice: String = Constants.DEFAULT_VOICE,
    val systemInstruction: String = Constants.DEFAULT_SYSTEM_INSTRUCTION,
    val isMockMode: Boolean = false,
    val isLoopbackMode: Boolean = false,
    val cameraFps: Int = Constants.DEFAULT_CAMERA_FPS,
    val audioSampleRate: Int = Constants.SAMPLE_RATE_CAPTURE_HZ,
    val autoAnalyzeGlassesPhotos: Boolean = true
)
