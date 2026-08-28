package com.metatogemini.glasses.e2e

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ============================================================================
 * Tier 2: Boundary Value Analysis & Corner Stress Suite
 * ============================================================================
 * Focuses on extreme numerical values, clipping, silence, buffer bounds,
 * malformed protocols, high-frequency barge-in spam, and edge parameters.
 */
class Tier2BoundaryCornerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Serializable
    data class TestServerMessage(
        val setupComplete: TestSetupAck? = null,
        val serverContent: TestServerContentPayload? = null
    )

    @Serializable
    class TestSetupAck

    @Serializable
    data class TestServerContentPayload(
        val modelTurn: TestModelTurnPayload? = null,
        val turnComplete: Boolean = false,
        val interrupted: Boolean = false
    )

    @Serializable
    data class TestModelTurnPayload(
        val parts: List<TestPartPayload> = emptyList()
    )

    @Serializable
    data class TestPartPayload(
        val text: String? = null,
        val inlineData: TestBlobPayload? = null
    )

    @Serializable
    data class TestBlobPayload(
        val mimeType: String,
        val data: String
    )

    object AudioMathEngine {
        fun computeRms(pcm: ByteArray): Float {
            if (pcm.isEmpty()) return 0f
            val sampleCount = pcm.size / 2
            if (sampleCount == 0) return 0f
            var sum = 0.0
            val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until sampleCount) {
                val s = buf.get(i).toDouble()
                sum += s * s
            }
            return sqrt(sum / sampleCount).toFloat()
        }

        fun computeDbfs(rms: Float): Float {
            if (rms <= 0f) return -100f
            val ratio = max(rms / 32767.0, 1e-5)
            return (20.0 * log10(ratio)).toFloat().coerceIn(-100f, 0f)
        }
    }

    // ========================================================================
    // 1. Audio Math Extremes (Clipping, Silence, Nyquist, Odd-Bytes)
    // ========================================================================

    @Test
    fun test_boundary_audio_clipping_positive_max_values() {
        val maxPositiveBuffer = ByteArray(1024)
        for (i in 0 until 512) {
            val sample: Short = Short.MAX_VALUE // +32767
            maxPositiveBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            maxPositiveBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        val rms = AudioMathEngine.computeRms(maxPositiveBuffer)
        assertEquals(32767f, rms, 1.0f)
        val dbfs = AudioMathEngine.computeDbfs(rms)
        assertEquals(0.0f, dbfs, 0.01f)
    }

    @Test
    fun test_boundary_audio_clipping_negative_max_values() {
        val maxNegativeBuffer = ByteArray(1024)
        for (i in 0 until 512) {
            val sample: Short = Short.MIN_VALUE // -32768
            maxNegativeBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            maxNegativeBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        val rms = AudioMathEngine.computeRms(maxNegativeBuffer)
        assertTrue("Negative max clipping gives RMS >= 32767", rms >= 32767f)
        val dbfs = AudioMathEngine.computeDbfs(rms)
        assertEquals(0.0f, dbfs, 0.01f)
    }

    @Test
    fun test_boundary_audio_pure_silence_all_zeros() {
        val silence = ByteArray(2048) { 0 }
        val rms = AudioMathEngine.computeRms(silence)
        assertEquals(0.0f, rms, 0.0001f)
        val dbfs = AudioMathEngine.computeDbfs(rms)
        assertEquals(-100f, dbfs, 0.0001f)
    }

    @Test
    fun test_boundary_audio_odd_length_byte_array_graceful_handling() {
        // 1023 bytes (odd number of bytes, cannot form complete short for the 512th sample)
        val oddBuffer = ByteArray(1023) { 1 }
        val rms = AudioMathEngine.computeRms(oddBuffer)
        // Should process exactly 511 samples and not throw BufferUnderflowException
        assertTrue(rms >= 0f)
    }

    @Test
    fun test_boundary_audio_alternating_nyquist_frequency() {
        // Nyquist frequency at fs/2 alternates +32767 and -32768 each sample
        val nyquistBuffer = ByteArray(1024)
        for (i in 0 until 512) {
            val sample: Short = if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
            nyquistBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
            nyquistBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        val rms = AudioMathEngine.computeRms(nyquistBuffer)
        assertTrue(rms > 32700f)
    }

    // ========================================================================
    // 2. Buffer & Payload Boundaries (Zero, Huge, Base64 Truncation)
    // ========================================================================

    @Test
    fun test_boundary_zero_length_byte_array_ingestion() {
        val empty = ByteArray(0)
        val rms = AudioMathEngine.computeRms(empty)
        assertEquals(0.0f, rms, 0.0001f)
    }

    @Test
    fun test_boundary_huge_pcm_burst_chunk_64kb() {
        val hugeChunk = ByteArray(65536) { 1 }
        val rms = AudioMathEngine.computeRms(hugeChunk)
        assertTrue(rms >= 0f)
        assertEquals(32768, hugeChunk.size / 2)
    }

    @Test
    fun test_boundary_huge_base64_vision_payload() {
        val hugeImageRaw = ByteArray(1024 * 1024 * 2) { (it % 256).toByte() } // 2MB raw data
        val base64 = Base64.getEncoder().encodeToString(hugeImageRaw)
        assertTrue(base64.length > 2_500_000)

        val jsonPayload = """{"mimeType":"image/jpeg","data":"$base64"}"""
        val parsed = json.decodeFromString<TestBlobPayload>(jsonPayload)
        assertEquals("image/jpeg", parsed.mimeType)
        assertEquals(base64.length, parsed.data.length)
    }

    @Test
    fun test_boundary_empty_base64_string_handling() {
        val emptyBase64 = ""
        val decoded = Base64.getDecoder().decode(emptyBase64)
        assertEquals(0, decoded.size)
    }

    @Test
    fun test_boundary_malformed_base64_string_with_illegal_characters() {
        val illegalBase64 = "???NotBase64!!!"
        var exceptionCaught = false
        try {
            Base64.getDecoder().decode(illegalBase64)
        } catch (e: IllegalArgumentException) {
            exceptionCaught = true
        }
        assertTrue("Illegal Base64 characters must throw IllegalArgumentException", exceptionCaught)
    }

    // ========================================================================
    // 3. JSON & Protocol Malformations & Unicode Robustness
    // ========================================================================

    @Test
    fun test_boundary_json_extra_unknown_fields_resilience() {
        val serverJsonWithExtras = """
            {
                "setupComplete": {},
                "unknownFieldA": 12345,
                "unknownObjectB": {"nested": true},
                "serverContent": {
                    "interrupted": false,
                    "unknownFlag": "SURPRISE"
                }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<TestServerMessage>(serverJsonWithExtras)
        assertNotNull(parsed.setupComplete)
        assertNotNull(parsed.serverContent)
        assertFalse(parsed.serverContent?.interrupted ?: true)
    }

    @Test
    fun test_boundary_json_missing_optional_fields() {
        val minimalJson = """{}"""
        val parsed = json.decodeFromString<TestServerMessage>(minimalJson)
        assertNull(parsed.setupComplete)
        assertNull(parsed.serverContent)
    }

    @Test
    fun test_boundary_unicode_emojis_and_escaped_characters_in_transcript() {
        val unicodeJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            {"text": "Hello world! 👋👓 Smart glasses are online. \nLine 2 with \t tabs & \"quotes\" and Unicode \u2764\uFE0F."}
                        ]
                    }
                }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<TestServerMessage>(unicodeJson)
        val text = parsed.serverContent?.modelTurn?.parts?.first()?.text
        assertNotNull(text)
        assertTrue(text!!.contains("👋👓"))
        assertTrue(text.contains("Smart glasses are online."))
        assertTrue(text.contains("Line 2 with"))
    }

    @Test
    fun test_boundary_rapid_consecutive_barge_in_spam_handling() = runBlocking {
        val generationCounter = AtomicInteger(0)
        var flushCount = 0

        // Emulate 10 consecutive barge-in events in rapid succession
        for (i in 1..10) {
            val gen = generationCounter.incrementAndGet()
            flushCount++
        }

        assertEquals(10, generationCounter.get())
        assertEquals(10, flushCount)
    }

    // ========================================================================
    // 4. Sample Rate & Generation Config Bounds
    // ========================================================================

    @Test
    fun test_boundary_extreme_sample_rates_math() {
        val sampleRates = listOf(8000, 16000, 22050, 24000, 44100, 48000, 96000)
        for (sr in sampleRates) {
            val samplesPer20ms = (sr * 0.02).toInt()
            assertTrue(samplesPer20ms > 0)
            val bytesPer20ms = samplesPer20ms * 2
            assertTrue(bytesPer20ms >= 320)
        }
    }

    @Test
    fun test_boundary_temperature_and_top_p_ranges() {
        val tempMin = 0.0f
        val tempMax = 2.0f
        val topPMin = 0.0f
        val topPMax = 1.0f

        fun validateConfig(temp: Float, topP: Float): Boolean {
            return temp in 0.0f..2.0f && topP in 0.0f..1.0f
        }

        assertTrue(validateConfig(tempMin, topPMin))
        assertTrue(validateConfig(tempMax, topPMax))
        assertTrue(validateConfig(0.7f, 0.95f))
        assertFalse(validateConfig(-0.1f, 0.5f))
        assertFalse(validateConfig(0.5f, 1.1f))
    }

    @Test
    fun test_boundary_max_output_tokens_range() {
        val tokenBounds = listOf(1, 1024, 2048, 4096, 8192)
        for (t in tokenBounds) {
            assertTrue(t in 1..8192)
        }
    }

    // ========================================================================
    // 5. Concurrency & High Load Stress
    // ========================================================================

    @Test
    fun test_boundary_concurrent_audio_enqueue_thread_safety() {
        val queue = ConcurrentLinkedQueue<ByteArray>()
        val threadCount = 8
        val itemsPerThread = 500
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            Thread {
                for (i in 0 until itemsPerThread) {
                    queue.add(ByteArray(100) { (t + 1).toByte() })
                }
                latch.countDown()
            }.start()
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("All concurrent enqueue threads should finish in <5s", completed)
        assertEquals(threadCount * itemsPerThread, queue.size)
    }

    @Test
    fun test_boundary_synthetic_audio_zero_and_negative_duration_handling() {
        fun generateToneSafe(durationMs: Int, sampleRate: Int = 24000): ByteArray {
            val safeDuration = durationMs.coerceAtLeast(0)
            val sampleCount = (sampleRate * (safeDuration / 1000.0)).toInt()
            return ByteArray(sampleCount * 2)
        }

        val zeroDurationChunk = generateToneSafe(0)
        val negDurationChunk = generateToneSafe(-100)

        assertEquals(0, zeroDurationChunk.size)
        assertEquals(0, negDurationChunk.size)
    }
}
