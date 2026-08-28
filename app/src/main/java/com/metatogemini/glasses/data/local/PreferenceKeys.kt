package com.metatogemini.glasses.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * DataStore preference keys for storing user settings and session configuration.
 */
object PreferenceKeys {
    val API_KEY = stringPreferencesKey("gemini_api_key")
    val MODEL = stringPreferencesKey("gemini_model")
    val VOICE = stringPreferencesKey("gemini_voice")
    val SYSTEM_INSTRUCTION = stringPreferencesKey("system_instruction")
    val IS_MOCK_MODE = booleanPreferencesKey("is_mock_mode")
    val IS_LOOPBACK_MODE = booleanPreferencesKey("is_loopback_mode")
    val CAMERA_FPS = intPreferencesKey("camera_fps")
    val AUDIO_SAMPLE_RATE = intPreferencesKey("audio_sample_rate")
    val JPEG_QUALITY = intPreferencesKey("jpeg_quality")
    val PREFER_BLUETOOTH_SCO = booleanPreferencesKey("prefer_bluetooth_sco")
    val AUTO_ANALYZE_GLASSES_PHOTOS = booleanPreferencesKey("auto_analyze_glasses_photos")
}
