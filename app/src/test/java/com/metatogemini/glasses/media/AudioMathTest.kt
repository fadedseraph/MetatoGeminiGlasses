package com.metatogemini.glasses.media

import com.metatogemini.glasses.media.util.AudioMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioMathTest {

    @Test
    fun `calculateRms with empty ByteArray returns zero state`() {
        val state = AudioMath.calculateRms(ByteArray(0))
        assertEquals(0f, state.rms, 0.001f)
        assertEquals(0f, state.normalizedRms, 0.001f)
        assertEquals(-100f, state.db, 0.001f)
        assertEquals(0f, state.normalizedDb, 0.001f)
    }

    @Test
    fun `calculateRms with empty ShortArray returns zero state`() {
        val state = AudioMath.calculateRms(ShortArray(0))
        assertEquals(0f, state.rms, 0.001f)
        assertEquals(0f, state.normalizedRms, 0.001f)
        assertEquals(-100f, state.db, 0.001f)
        assertEquals(0f, state.normalizedDb, 0.001f)
    }

    @Test
    fun `calculateRms with silence returns zero amplitude`() {
        val silenceBytes = ByteArray(1024) { 0 }
        val state = AudioMath.calculateRms(silenceBytes)

        assertEquals(0.0f, state.rms, 0.0001f)
        assertEquals(0.0f, state.normalizedRms, 0.0001f)
        assertEquals(-100.0f, state.db, 0.0001f)
        assertEquals(0.0f, state.normalizedDb, 0.0001f)
    }

    @Test
    fun `calculateRms with full scale positive DC signal returns maximum values`() {
        val shorts = ShortArray(512) { Short.MAX_VALUE }
        val pcmBytes = AudioMath.shortArrayToByteArrayLe(shorts)
        val state = AudioMath.calculateRms(pcmBytes)

        assertEquals(32767.0f, state.rms, 1.0f)
        assertEquals(1.0f, state.normalizedRms, 0.001f)
        assertEquals(0.0f, state.db, 0.01f)
        assertEquals(1.0f, state.normalizedDb, 0.01f)
    }

    @Test
    fun `calculateRms with full scale negative DC signal returns maximum values`() {
        val shorts = ShortArray(512) { -32767 }
        val pcmBytes = AudioMath.shortArrayToByteArrayLe(shorts)
        val state = AudioMath.calculateRms(pcmBytes)

        assertEquals(32767.0f, state.rms, 1.0f)
        assertEquals(1.0f, state.normalizedRms, 0.001f)
        assertEquals(0.0f, state.db, 0.01f)
        assertEquals(1.0f, state.normalizedDb, 0.01f)
    }

    @Test
    fun `calculateRms with 16kHz sine wave produces expected RMS and dBFS`() {
        // Full-scale sine wave has RMS = Peak / sqrt(2) ≈ 32767 / 1.41421356 ≈ 23169.9
        // dBFS ≈ 20 * log10(1 / sqrt(2)) ≈ -3.01 dBFS
        val sineBytes = AudioMath.generateSineWavePcm(
            frequencyHz = 1000.0,
            sampleRateHz = 16000,
            durationMs = 100, // 100ms = 1600 samples
            amplitude = 1.0
        )

        val state = AudioMath.calculateRms(sineBytes)

        assertTrue("RMS should be close to 23169 (actual: ${state.rms})", abs(state.rms - 23169f) < 500f)
        assertTrue("Normalized RMS should be ~0.707 (actual: ${state.normalizedRms})", abs(state.normalizedRms - 0.707f) < 0.02f)
        assertTrue("dBFS should be ~-3.01 dB (actual: ${state.db})", abs(state.db - (-3.01f)) < 0.5f)
        assertTrue("Normalized dB should be ~0.95 (actual: ${state.normalizedDb})", state.normalizedDb in 0.90f..1.0f)
    }

    @Test
    fun `short and byte array conversions roundtrip without loss`() {
        val originalShorts = shortArrayOf(0, 1, -1, 32767, -32768, 12345, -23456, 42)
        val bytes = AudioMath.shortArrayToByteArrayLe(originalShorts)
        val recoveredShorts = AudioMath.byteArrayToShortArrayLe(bytes)

        assertArrayEquals(originalShorts, recoveredShorts)
    }

    @Test
    fun `generateSineWavePcm produces correct byte buffer length`() {
        val sampleRate = 16000
        val durationMs = 250 // 0.25s -> 4000 samples -> 8000 bytes
        val bytes = AudioMath.generateSineWavePcm(
            frequencyHz = 440.0,
            sampleRateHz = sampleRate,
            durationMs = durationMs
        )

        assertEquals(8000, bytes.size)
    }

    @Test
    fun `calculateRms with odd byte length ignores trailing partial byte`() {
        val shorts = ShortArray(10) { 1000 }
        val normalBytes = AudioMath.shortArrayToByteArrayLe(shorts)
        val oddBytes = normalBytes + byteArrayOf(0x7F) // append single dangling byte

        val stateNormal = AudioMath.calculateRms(normalBytes)
        val stateOdd = AudioMath.calculateRms(oddBytes)

        assertEquals(stateNormal.rms, stateOdd.rms, 0.001f)
    }
}
