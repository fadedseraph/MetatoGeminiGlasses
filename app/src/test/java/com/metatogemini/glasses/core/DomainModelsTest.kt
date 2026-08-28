package com.metatogemini.glasses.core

import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.domain.model.AudioChunk
import com.metatogemini.glasses.domain.model.AudioRoute
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import com.metatogemini.glasses.domain.model.StreamMetrics
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelsTest {

    @Test
    fun `AudioChunk equality and property tests`() {
        val bytes1 = byteArrayOf(1, 2, 3, 4)
        val bytes2 = byteArrayOf(1, 2, 3, 4)
        val timestamp = 1000L

        val chunk1 = AudioChunk(data = bytes1, sampleRate = 16000, timestampMs = timestamp, rms = 0.5f)
        val chunk2 = AudioChunk(data = bytes2, sampleRate = 16000, timestampMs = timestamp, rms = 0.5f)

        assertEquals(chunk1, chunk2)
        assertEquals(chunk1.hashCode(), chunk2.hashCode())
        assertEquals(16000, chunk1.sampleRate)
        assertEquals(0.5f, chunk1.rms, 0.0001f)
    }

    @Test
    fun `GeminiMessage variants maintain data correctly`() {
        val setupComplete: GeminiMessage = GeminiMessage.SetupComplete
        assertNotNull(setupComplete)

        val audioBytes = byteArrayOf(10, 20, 30)
        val audioMsg = GeminiMessage.AudioData(pcmBytes = audioBytes, sampleRate = 24000)
        assertEquals(24000, audioMsg.sampleRate)
        assertArrayEquals(audioBytes, audioMsg.pcmBytes)

        val textMsg = GeminiMessage.TextData(text = "Vision analysis result", isFromUser = false)
        assertEquals("Vision analysis result", textMsg.text)
        assertFalse(textMsg.isFromUser)

        val interruption = GeminiMessage.Interruption(timestampMs = 12345L)
        assertEquals(12345L, interruption.timestampMs)

        val turnComplete = GeminiMessage.TurnComplete(isComplete = true)
        assertTrue(turnComplete.isComplete)

        val errorMsg = GeminiMessage.Error(RuntimeException("API Rate Limit"), "Rate limit exceeded")
        assertEquals("Rate limit exceeded", errorMsg.message)
    }

    @Test
    fun `ConnectionState hierarchy covers all lifecycle states`() {
        val disconnected: ConnectionState = ConnectionState.Disconnected
        val connecting: ConnectionState = ConnectionState.Connecting
        val connected: ConnectionState = ConnectionState.Connected
        val reconnecting: ConnectionState = ConnectionState.Reconnecting(attempt = 2)
        val failed: ConnectionState = ConnectionState.Failed(reason = "Timeout")

        assertEquals(2, (reconnecting as ConnectionState.Reconnecting).attempt)
        assertEquals("Timeout", (failed as ConnectionState.Failed).reason)
        assertNotNull(disconnected)
        assertNotNull(connecting)
        assertNotNull(connected)
    }

    @Test
    fun `SessionConfig default values match constants`() {
        val config = SessionConfig()

        assertEquals("", config.apiKey)
        assertEquals(Constants.DEFAULT_MODEL, config.model)
        assertEquals(Constants.DEFAULT_VOICE, config.voice)
        assertEquals(Constants.DEFAULT_SYSTEM_INSTRUCTION, config.systemInstruction)
        assertFalse(config.isMockMode)
        assertFalse(config.isLoopbackMode)
        assertEquals(Constants.DEFAULT_CAMERA_FPS, config.cameraFps)
        assertEquals(Constants.SAMPLE_RATE_CAPTURE_HZ, config.audioSampleRate)
    }

    @Test
    fun `AudioRoute provides accurate display names`() {
        val speaker = AudioRoute.Speaker
        val earpiece = AudioRoute.Earpiece
        val btHeadset = AudioRoute.BluetoothHeadset("Sony WH-1000XM5")
        val smartGlasses = AudioRoute.SmartGlasses("Ray-Ban Meta")
        val unknown = AudioRoute.Unknown

        assertEquals("Built-in Speaker", speaker.displayName)
        assertEquals("Earpiece", earpiece.displayName)
        assertEquals("Sony WH-1000XM5", btHeadset.displayName)
        assertEquals("Ray-Ban Meta", smartGlasses.displayName)
        assertEquals("Unknown Audio Route", unknown.displayName)
    }

    @Test
    fun `SnapshotRequest and Response model verification`() {
        val imgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val req1 = SnapshotRequest(imageBytes = imgBytes, prompt = "What is in front of me?")
        val req2 = SnapshotRequest(imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), prompt = "What is in front of me?")

        assertEquals(req1, req2)
        assertEquals(req1.hashCode(), req2.hashCode())

        val res = SnapshotResponse(text = "You are looking at a monitor.", latencyMs = 250L, finishReason = "STOP")
        assertEquals("You are looking at a monitor.", res.text)
        assertEquals(250L, res.latencyMs)
        assertEquals("STOP", res.finishReason)
    }

    @Test
    fun `StreamMetrics records performance statistics`() {
        val metrics = StreamMetrics(
            latencyMs = 120L,
            cameraFps = 2.0f,
            micRms = 0.45f,
            speakerRms = 0.80f,
            bytesSent = 1048576L,
            bytesReceived = 2097152L
        )

        assertEquals(120L, metrics.latencyMs)
        assertEquals(2.0f, metrics.cameraFps, 0.001f)
        assertEquals(0.45f, metrics.micRms, 0.001f)
        assertEquals(0.80f, metrics.speakerRms, 0.001f)
        assertEquals(1048576L, metrics.bytesSent)
        assertEquals(2097152L, metrics.bytesReceived)
    }
}
