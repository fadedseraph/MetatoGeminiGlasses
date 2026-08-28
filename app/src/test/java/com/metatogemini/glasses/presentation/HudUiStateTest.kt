package com.metatogemini.glasses.presentation

import com.metatogemini.glasses.domain.model.AudioRoute
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotResponse
import com.metatogemini.glasses.media.audio.AudioRmsState
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioState
import com.metatogemini.glasses.presentation.model.HudUiState
import com.metatogemini.glasses.presentation.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudUiStateTest {

    @Test
    fun defaultState_hasExpectedInitialValues() {
        val state = HudUiState()

        assertEquals(ConnectionState.Disconnected, state.connectionState)
        assertFalse(state.isConnected)
        assertFalse(state.isConnecting)
        assertFalse(state.isMicMuted)
        assertTrue(state.isCameraEnabled)
        assertFalse(state.isStreamingFrames)
        assertEquals(AudioRoute.Speaker, state.audioRoute)
        assertEquals(BluetoothAudioState.Disconnected, state.bluetoothState)
        assertEquals("", state.currentTranscript)
        assertFalse(state.isAssistantSpeaking)
        assertTrue(state.subtitleHistory.isEmpty())
        assertFalse(state.isSettingsOpen)
        assertFalse(state.isSnapshotInspectorOpen)
        assertNull(state.latestSnapshotResult)
        assertNull(state.errorMessage)
    }

    @Test
    fun isConnected_returnsTrueOnlyWhenConnected() {
        val disconnected = HudUiState(connectionState = ConnectionState.Disconnected)
        val connecting = HudUiState(connectionState = ConnectionState.Connecting)
        val connected = HudUiState(connectionState = ConnectionState.Connected)
        val failed = HudUiState(connectionState = ConnectionState.Failed(null, "Error"))

        assertFalse(disconnected.isConnected)
        assertFalse(connecting.isConnected)
        assertTrue(connected.isConnected)
        assertFalse(failed.isConnected)
    }

    @Test
    fun isConnecting_returnsTrueForConnectingAndReconnecting() {
        val disconnected = HudUiState(connectionState = ConnectionState.Disconnected)
        val connecting = HudUiState(connectionState = ConnectionState.Connecting)
        val reconnecting = HudUiState(connectionState = ConnectionState.Reconnecting(2))
        val connected = HudUiState(connectionState = ConnectionState.Connected)

        assertFalse(disconnected.isConnecting)
        assertTrue(connecting.isConnecting)
        assertTrue(reconnecting.isConnecting)
        assertFalse(connected.isConnecting)
    }

    @Test
    fun isMockMode_reflectsSessionConfig() {
        val realState = HudUiState(sessionConfig = SessionConfig(isMockMode = false))
        val mockState = HudUiState(sessionConfig = SessionConfig(isMockMode = true))

        assertFalse(realState.isMockMode)
        assertTrue(mockState.isMockMode)
    }

    @Test
    fun isLoopbackMode_reflectsSessionConfig() {
        val normalState = HudUiState(sessionConfig = SessionConfig(isLoopbackMode = false))
        val loopbackState = HudUiState(sessionConfig = SessionConfig(isLoopbackMode = true))

        assertFalse(normalState.isLoopbackMode)
        assertTrue(loopbackState.isLoopbackMode)
    }

    @Test
    fun subtitleEntry_handlesUserAndAssistantSpeech() {
        val userEntry = SubtitleEntry(text = "Hello Gemini", isFromUser = true)
        val assistantEntry = SubtitleEntry(text = "Hello wearer!", isFromUser = false)
        val interruptedEntry = SubtitleEntry(text = "Wait...", isFromUser = false, isInterrupted = true)

        assertTrue(userEntry.isFromUser)
        assertFalse(userEntry.isInterrupted)
        assertEquals("Hello Gemini", userEntry.text)

        assertFalse(assistantEntry.isFromUser)
        assertFalse(assistantEntry.isInterrupted)

        assertTrue(interruptedEntry.isInterrupted)
    }

    @Test
    fun copyAndEquality_handlesComplexStateAndByteArrays() {
        val imgBytes1 = byteArrayOf(1, 2, 3, 4)
        val imgBytes2 = byteArrayOf(1, 2, 3, 4)
        val imgBytes3 = byteArrayOf(5, 6, 7)

        val state1 = HudUiState(
            latestSnapshotBytes = imgBytes1,
            latestSnapshotResult = SnapshotResponse(text = "Scene analysis", latencyMs = 120L),
            currentTranscript = "Streaming...",
            micRms = AudioRmsState(rms = 100f, normalizedRms = 0.5f)
        )

        val state2 = state1.copy(latestSnapshotBytes = imgBytes2)
        val state3 = state1.copy(latestSnapshotBytes = imgBytes3)

        assertEquals(state1, state2)
        assertEquals(state1.hashCode(), state2.hashCode())
        assertNotEquals(state1, state3)
    }
}
