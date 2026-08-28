package com.metatogemini.glasses.presentation.model

import com.metatogemini.glasses.domain.model.AudioRoute
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotResponse
import com.metatogemini.glasses.domain.model.StreamMetrics
import com.metatogemini.glasses.media.audio.AudioRmsState
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioState
import com.metatogemini.glasses.media.camera.CameraLens

/**
 * Represents an entry in the live subtitle history.
 */
data class SubtitleEntry(
    val text: String,
    val isFromUser: Boolean,
    val isInterrupted: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Complete UI State for the smart glasses HUD overlay and control screens.
 */
data class HudUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val sessionConfig: SessionConfig = SessionConfig(),
    val isMicMuted: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val currentLens: CameraLens = CameraLens.BACK,
    val isTorchEnabled: Boolean = false,
    val isStreamingFrames: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.Speaker,
    val bluetoothState: BluetoothAudioState = BluetoothAudioState.Disconnected,
    val micRms: AudioRmsState = AudioRmsState(),
    val speakerRms: AudioRmsState = AudioRmsState(),
    val currentTranscript: String = "",
    val isAssistantSpeaking: Boolean = false,
    val subtitleHistory: List<SubtitleEntry> = emptyList(),
    val lastInterruptionTimestamp: Long = 0L,
    val isSnapshotAnalyzing: Boolean = false,
    val latestSnapshotResult: SnapshotResponse? = null,
    val latestSnapshotBytes: ByteArray? = null,
    val isSettingsOpen: Boolean = false,
    val isSnapshotInspectorOpen: Boolean = false,
    val metrics: StreamMetrics = StreamMetrics(),
    val userNotice: String? = null,
    val errorMessage: String? = null
) {
    val isConnected: Boolean
        get() = connectionState is ConnectionState.Connected

    val isConnecting: Boolean
        get() = connectionState is ConnectionState.Connecting || connectionState is ConnectionState.Reconnecting

    val isMockMode: Boolean
        get() = sessionConfig.isMockMode

    val isLoopbackMode: Boolean
        get() = sessionConfig.isLoopbackMode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HudUiState

        if (connectionState != other.connectionState) return false
        if (sessionConfig != other.sessionConfig) return false
        if (isMicMuted != other.isMicMuted) return false
        if (isCameraEnabled != other.isCameraEnabled) return false
        if (currentLens != other.currentLens) return false
        if (isTorchEnabled != other.isTorchEnabled) return false
        if (isStreamingFrames != other.isStreamingFrames) return false
        if (audioRoute != other.audioRoute) return false
        if (bluetoothState != other.bluetoothState) return false
        if (micRms != other.micRms) return false
        if (speakerRms != other.speakerRms) return false
        if (currentTranscript != other.currentTranscript) return false
        if (isAssistantSpeaking != other.isAssistantSpeaking) return false
        if (subtitleHistory != other.subtitleHistory) return false
        if (lastInterruptionTimestamp != other.lastInterruptionTimestamp) return false
        if (isSnapshotAnalyzing != other.isSnapshotAnalyzing) return false
        if (latestSnapshotResult != other.latestSnapshotResult) return false
        if (isSettingsOpen != other.isSettingsOpen) return false
        if (isSnapshotInspectorOpen != other.isSnapshotInspectorOpen) return false
        if (metrics != other.metrics) return false
        if (userNotice != other.userNotice) return false
        if (errorMessage != other.errorMessage) return false
        if (latestSnapshotBytes != null) {
            if (other.latestSnapshotBytes == null) return false
            if (!latestSnapshotBytes.contentEquals(other.latestSnapshotBytes)) return false
        } else if (other.latestSnapshotBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = connectionState.hashCode()
        result = 31 * result + sessionConfig.hashCode()
        result = 31 * result + isMicMuted.hashCode()
        result = 31 * result + isCameraEnabled.hashCode()
        result = 31 * result + currentLens.hashCode()
        result = 31 * result + isTorchEnabled.hashCode()
        result = 31 * result + isStreamingFrames.hashCode()
        result = 31 * result + audioRoute.hashCode()
        result = 31 * result + bluetoothState.hashCode()
        result = 31 * result + micRms.hashCode()
        result = 31 * result + speakerRms.hashCode()
        result = 31 * result + currentTranscript.hashCode()
        result = 31 * result + isAssistantSpeaking.hashCode()
        result = 31 * result + subtitleHistory.hashCode()
        result = 31 * result + lastInterruptionTimestamp.hashCode()
        result = 31 * result + isSnapshotAnalyzing.hashCode()
        result = 31 * result + (latestSnapshotResult?.hashCode() ?: 0)
        result = 31 * result + (latestSnapshotBytes?.contentHashCode() ?: 0)
        result = 31 * result + isSettingsOpen.hashCode()
        result = 31 * result + isSnapshotInspectorOpen.hashCode()
        result = 31 * result + metrics.hashCode()
        result = 31 * result + (userNotice?.hashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}
