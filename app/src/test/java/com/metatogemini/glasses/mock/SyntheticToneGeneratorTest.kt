package com.metatogemini.glasses.mock

import com.metatogemini.glasses.media.util.AudioMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SyntheticToneGeneratorTest {

    private lateinit var generator: SyntheticToneGenerator

    @Before
    fun setup() {
        generator = SyntheticToneGenerator()
    }

    @Test
    fun `generateTone24k produces correct buffer size for 24kHz 16-bit Mono`() {
        // 200ms at 24000 Hz = 4800 samples = 9600 bytes
        val bytes200ms = generator.generateTone24k(durationMs = 200)
        assertEquals(9600, bytes200ms.size)

        // 100ms at 24000 Hz = 2400 samples = 4800 bytes
        val bytes100ms = generator.generateTone24k(durationMs = 100)
        assertEquals(4800, bytes100ms.size)

        // 50ms at 24000 Hz = 1200 samples = 2400 bytes
        val bytes50ms = generator.generateTone24k(durationMs = 50)
        assertEquals(2400, bytes50ms.size)
    }

    @Test
    fun `generateTone handles zero or negative duration gracefully`() {
        val zeroBytes = generator.generateTone24k(durationMs = 0)
        assertEquals(0, zeroBytes.size)

        val negativeBytes = generator.generateTone24k(durationMs = -50)
        assertEquals(0, negativeBytes.size)
    }

    @Test
    fun `generateTone handles invalid sample rate or frequency`() {
        val invalidSampleRate = generator.generateTone(sampleRateHz = 0, durationMs = 100)
        assertEquals(0, invalidSampleRate.size)

        val invalidFreq = generator.generateTone(frequencyHz = 0.0, durationMs = 100)
        assertEquals(0, invalidFreq.size)
    }

    @Test
    fun `generateTone with zero amplitude produces pure silence`() {
        val silentBytes = generator.generateTone24k(durationMs = 100, amplitude = 0.0)
        assertEquals(4800, silentBytes.size)

        val shorts = AudioMath.byteArrayToShortArrayLe(silentBytes)
        assertTrue("All samples should be 0", shorts.all { it == 0.toShort() })

        val rms = AudioMath.calculateRms(silentBytes)
        assertEquals(0.0f, rms.rms, 0.001f)
    }

    @Test
    fun `raised-cosine envelope produces smooth boundaries at attack and decay`() {
        val durationMs = 200
        val sampleRate = 24000
        val attackMs = 20
        val decayMs = 20

        val pcmBytes = generator.generateTone(
            sampleRateHz = sampleRate,
            durationMs = durationMs,
            frequencyHz = 440.0,
            amplitude = 1.0,
            attackMs = attackMs,
            decayMs = decayMs
        )

        val shorts = AudioMath.byteArrayToShortArrayLe(pcmBytes)
        val totalSamples = shorts.size
        assertEquals(4800, totalSamples)

        // First sample should start at 0
        assertEquals(0.toShort(), shorts[0])

        // Attack window is 480 samples. Samples within first 20 samples should be small
        val firstFewSamplesMax = (0 until 20).maxOf { abs(shorts[it].toInt()) }
        val sustainMax = (1000 until 3800).maxOf { abs(shorts[it].toInt()) }

        assertTrue(
            "Envelope boundary should ramp up smoothly (first 20 samples max: $firstFewSamplesMax vs sustain max: $sustainMax)",
            firstFewSamplesMax < sustainMax * 0.15
        )

        // Sustain region should achieve full amplitude close to Short.MAX_VALUE
        assertTrue(
            "Sustain region peak should be near Short.MAX_VALUE (actual: $sustainMax)",
            sustainMax > 32000
        )

        // Decay window ends smoothly near 0
        val lastFewSamplesMax = ((totalSamples - 20) until totalSamples).maxOf { abs(shorts[it].toInt()) }
        assertTrue(
            "Envelope decay should ramp down smoothly (last 20 samples max: $lastFewSamplesMax vs sustain max: $sustainMax)",
            lastFewSamplesMax < sustainMax * 0.15
        )
    }

    @Test
    fun `amplitude parameter scales signal energy and RMS proportionally`() {
        val toneFull = generator.generateTone24k(durationMs = 200, frequencyHz = 440.0, amplitude = 1.0)
        val toneHalf = generator.generateTone24k(durationMs = 200, frequencyHz = 440.0, amplitude = 0.5)

        val rmsFull = AudioMath.calculateRms(toneFull)
        val rmsHalf = AudioMath.calculateRms(toneHalf)

        assertTrue("Full tone RMS should be positive", rmsFull.rms > 10000f)
        assertTrue("Half tone RMS should be positive", rmsHalf.rms > 5000f)

        val ratio = rmsHalf.rms / rmsFull.rms
        assertTrue("Half amplitude RMS ratio should be ~0.5 (actual: $ratio)", abs(ratio - 0.5f) < 0.05f)
    }

    @Test
    fun `generateChime24k produces concatenated notes and silence gaps`() {
        val frequencies = listOf(440.0, 554.37, 659.25) // A Major triad
        val noteDurationMs = 100 // 2400 samples each = 4800 bytes
        val gapMs = 20 // 480 samples each = 960 bytes

        val chimeBytes = generator.generateChime24k(
            frequencies = frequencies,
            noteDurationMs = noteDurationMs,
            gapMs = gapMs,
            amplitude = 0.5
        )

        // 3 notes * 4800 bytes + 2 gaps * 960 bytes = 14400 + 1920 = 16320 bytes
        val expectedSize = (3 * 4800) + (2 * 960)
        assertEquals(expectedSize, chimeBytes.size)

        val rms = AudioMath.calculateRms(chimeBytes)
        assertTrue("Chime audio should have non-zero RMS", rms.rms > 1000f)
    }

    @Test
    fun `generateSilence produces exact zero byte array`() {
        val silence = generator.generateSilence(durationMs = 150, sampleRateHz = 24000)
        // 150ms * 24000 = 3600 samples = 7200 bytes
        assertEquals(7200, silence.size)
        assertTrue(silence.all { it == 0.toByte() })
    }

    @Test
    fun `short duration shorter than attack plus decay handles envelope window safely`() {
        // 10ms at 24000Hz = 240 samples. Attack/decay requested as 20ms each (480 samples each)
        val shortTone = generator.generateTone24k(durationMs = 10, attackMs = 20, decayMs = 20)
        assertEquals(480, shortTone.size)

        val shorts = AudioMath.byteArrayToShortArrayLe(shortTone)
        assertEquals(240, shorts.size)
        assertEquals(0.toShort(), shorts[0])
    }
}
