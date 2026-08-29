package com.metatogemini.glasses.core.common

object Constants {
    // Audio Configuration
    const val SAMPLE_RATE_CAPTURE_HZ: Int = 16000
    const val SAMPLE_RATE_PLAYBACK_HZ: Int = 24000
    const val AUDIO_CHANNELS_MONO: Int = 1
    const val BYTES_PER_SAMPLE_PCM_16BIT: Int = 2
    const val CAPTURE_CHUNK_SAMPLES: Int = 512
    const val CAPTURE_CHUNK_BYTES: Int = CAPTURE_CHUNK_SAMPLES * BYTES_PER_SAMPLE_PCM_16BIT // 1024 bytes

    // Gemini API Endpoints
    const val GEMINI_LIVE_WS_BASE_URL: String =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val GEMINI_REST_BASE_URL: String =
        "https://generativelanguage.googleapis.com/v1beta"

    // Model & Voice Defaults
    const val DEFAULT_MODEL: String = "gemini-2.0-flash"
    const val DEFAULT_VOICE: String = "Puck"
    val SUPPORTED_MODELS: List<String> = listOf(
        "gemini-2.0-flash",
        "gemini-2.0-flash-realtime-exp",
        "gemini-2.5-flash",
        "gemini-1.5-flash",
        "gemini-1.5-pro"
    )
    val SUPPORTED_VOICES: List<String> = listOf(
        "Puck",
        "Charon",
        "Kore",
        "Fenrir",
        "Aoede"
    )

    const val DEFAULT_SYSTEM_INSTRUCTION: String =
        "You are a multimodal AI assistant integrated into smart glasses. Answer concisely, clearly, and conversationally."

    // Camera & Vision Configuration
    const val DEFAULT_CAMERA_FPS: Int = 1
    const val DEFAULT_JPEG_QUALITY: Int = 80
    const val MAX_IMAGE_WIDTH: Int = 1280
    const val MAX_IMAGE_HEIGHT: Int = 720
    const val MIME_TYPE_JPEG: String = "image/jpeg"
    const val MIME_TYPE_PCM: String = "audio/pcm;rate=16000"

    // Storage
    const val DATASTORE_PREFERENCES_NAME: String = "gemini_glasses_preferences"
}
