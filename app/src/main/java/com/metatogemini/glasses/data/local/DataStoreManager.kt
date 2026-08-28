package com.metatogemini.glasses.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.domain.model.SessionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = Constants.DATASTORE_PREFERENCES_NAME
)

/**
 * Manager class responsible for persisting and retrieving user preferences and session configuration.
 */
class DataStoreManager(
    private val context: Context,
    private val dataStore: DataStore<Preferences> = context.dataStore
) {

    val sessionConfigFlow: Flow<SessionConfig> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                AppLogger.e(TAG, "Error reading DataStore preferences", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            mapPreferencesToSessionConfig(preferences)
        }

    suspend fun getSessionConfig(): SessionConfig {
        return sessionConfigFlow.first()
    }

    suspend fun updateApiKey(apiKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.API_KEY] = apiKey
        }
    }

    suspend fun updateModel(model: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MODEL] = model
        }
    }

    suspend fun updateVoice(voice: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.VOICE] = voice
        }
    }

    suspend fun updateSystemInstruction(instruction: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.SYSTEM_INSTRUCTION] = instruction
        }
    }

    suspend fun updateMockMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.IS_MOCK_MODE] = enabled
        }
    }

    suspend fun updateLoopbackMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.IS_LOOPBACK_MODE] = enabled
        }
    }

    suspend fun updateCameraFps(fps: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.CAMERA_FPS] = fps
        }
    }

    suspend fun updateAudioSampleRate(sampleRate: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.AUDIO_SAMPLE_RATE] = sampleRate
        }
    }

    suspend fun updateJpegQuality(quality: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.JPEG_QUALITY] = quality
        }
    }

    suspend fun updatePreferBluetoothSco(preferSco: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.PREFER_BLUETOOTH_SCO] = preferSco
        }
    }

    suspend fun updateAutoAnalyzeGlassesPhotos(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.AUTO_ANALYZE_GLASSES_PHOTOS] = enabled
        }
    }

    suspend fun updateSessionConfig(config: SessionConfig) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.API_KEY] = config.apiKey
            preferences[PreferenceKeys.MODEL] = config.model
            preferences[PreferenceKeys.VOICE] = config.voice
            preferences[PreferenceKeys.SYSTEM_INSTRUCTION] = config.systemInstruction
            preferences[PreferenceKeys.IS_MOCK_MODE] = config.isMockMode
            preferences[PreferenceKeys.IS_LOOPBACK_MODE] = config.isLoopbackMode
            preferences[PreferenceKeys.CAMERA_FPS] = config.cameraFps
            preferences[PreferenceKeys.AUDIO_SAMPLE_RATE] = config.audioSampleRate
            preferences[PreferenceKeys.AUTO_ANALYZE_GLASSES_PHOTOS] = config.autoAnalyzeGlassesPhotos
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    private fun mapPreferencesToSessionConfig(preferences: Preferences): SessionConfig {
        return SessionConfig(
            apiKey = preferences[PreferenceKeys.API_KEY] ?: "",
            model = preferences[PreferenceKeys.MODEL] ?: Constants.DEFAULT_MODEL,
            voice = preferences[PreferenceKeys.VOICE] ?: Constants.DEFAULT_VOICE,
            systemInstruction = preferences[PreferenceKeys.SYSTEM_INSTRUCTION]
                ?: Constants.DEFAULT_SYSTEM_INSTRUCTION,
            isMockMode = preferences[PreferenceKeys.IS_MOCK_MODE] ?: false,
            isLoopbackMode = preferences[PreferenceKeys.IS_LOOPBACK_MODE] ?: false,
            cameraFps = preferences[PreferenceKeys.CAMERA_FPS] ?: Constants.DEFAULT_CAMERA_FPS,
            audioSampleRate = preferences[PreferenceKeys.AUDIO_SAMPLE_RATE]
                ?: Constants.SAMPLE_RATE_CAPTURE_HZ,
            autoAnalyzeGlassesPhotos = preferences[PreferenceKeys.AUTO_ANALYZE_GLASSES_PHOTOS] ?: true
        )
    }

    companion object {
        private const val TAG = "DataStoreManager"
    }
}
