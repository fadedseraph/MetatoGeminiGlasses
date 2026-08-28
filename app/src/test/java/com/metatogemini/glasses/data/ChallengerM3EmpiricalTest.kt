package com.metatogemini.glasses.data

import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.data.network.dto.DtoMappers
import com.metatogemini.glasses.data.network.dto.DtoMappers.toGeminiMessages
import com.metatogemini.glasses.data.network.dto.DtoMappers.toRestRequest
import com.metatogemini.glasses.data.network.dto.DtoMappers.toSnapshotResponse
import com.metatogemini.glasses.data.network.dto.GeminiClientContent
import com.metatogemini.glasses.data.network.dto.GeminiLiveBlob
import com.metatogemini.glasses.data.network.dto.GeminiLiveClientMessage
import com.metatogemini.glasses.data.network.dto.GeminiLiveContent
import com.metatogemini.glasses.data.network.dto.GeminiLiveGenerationConfig
import com.metatogemini.glasses.data.network.dto.GeminiLivePart
import com.metatogemini.glasses.data.network.dto.GeminiLivePrebuiltVoiceConfig
import com.metatogemini.glasses.data.network.dto.GeminiLiveServerContent
import com.metatogemini.glasses.data.network.dto.GeminiLiveServerMessage
import com.metatogemini.glasses.data.network.dto.GeminiLiveServerPart
import com.metatogemini.glasses.data.network.dto.GeminiLiveSetup
import com.metatogemini.glasses.data.network.dto.GeminiLiveSpeechConfig
import com.metatogemini.glasses.data.network.dto.GeminiLiveVoiceConfig
import com.metatogemini.glasses.data.network.dto.GeminiRealtimeInput
import com.metatogemini.glasses.data.network.dto.GeminiRestGenerationConfig
import com.metatogemini.glasses.data.network.dto.GeminiRestRequest
import com.metatogemini.glasses.data.network.dto.GeminiRestResponse
import com.metatogemini.glasses.data.network.dto.GeminiRestSafetySetting
import com.metatogemini.glasses.data.network.rest.GeminiRestClient
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SnapshotRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Adversarial Challenger empirical test suite for Milestone 3:
 * - Live Bidi and REST JSON serialization roundtrips
 * - Malformed, unexpected, partial, and high-volume JSON edge cases
 * - Dynamic model URL and identifier routing
 * - Base64 boundary math and error isolation
 * - REST Client HTTP mocking & dynamic endpoint validation
 */
class ChallengerM3EmpiricalTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    // =========================================================================
    // 1. LIVE BIDI PAYLOAD ROUNDTRIP TESTS
    // =========================================================================

    @Test
    fun `empirical challenge - Live setup serialization preserves all complex config fields and unicode`() {
        val complexInstruction = "Smart Glasses AI Assistant: \uD83D\uDC53 \"Quotes\", newlines \n\n and UTF-8: \u00E9\u00E8\u00E0\u4E2D\u6587"
        val setupPayload = GeminiLiveClientMessage(
            setup = GeminiLiveSetup(
                model = "models/gemini-2.0-flash-exp",
                generationConfig = GeminiLiveGenerationConfig(
                    responseModalities = listOf("AUDIO", "TEXT"),
                    speechConfig = GeminiLiveSpeechConfig(
                        voiceConfig = GeminiLiveVoiceConfig(
                            prebuiltVoiceConfig = GeminiLivePrebuiltVoiceConfig(voiceName = "Fenrir")
                        )
                    ),
                    temperature = 0.85f,
                    topP = 0.92f,
                    topK = 40,
                    maxOutputTokens = 2048
                ),
                systemInstruction = GeminiLiveContent(
                    role = "system",
                    parts = listOf(GeminiLivePart(text = complexInstruction))
                )
            )
        )

        val serialized = json.encodeToString(setupPayload)
        assertTrue(serialized.contains("Fenrir"))
        assertTrue(serialized.contains("models/gemini-2.0-flash-exp"))

        val deserialized = json.decodeFromString<GeminiLiveClientMessage>(serialized)
        assertEquals("models/gemini-2.0-flash-exp", deserialized.setup?.model)
        assertEquals("Fenrir", deserialized.setup?.generationConfig?.speechConfig?.voiceConfig?.prebuiltVoiceConfig?.voiceName)
        assertEquals(0.85f, deserialized.setup?.generationConfig?.temperature)
        assertEquals(0.92f, deserialized.setup?.generationConfig?.topP)
        assertEquals(40, deserialized.setup?.generationConfig?.topK)
        assertEquals(2048, deserialized.setup?.generationConfig?.maxOutputTokens)
        assertEquals(complexInstruction, deserialized.setup?.systemInstruction?.parts?.first()?.text)
    }

    @Test
    fun `empirical challenge - Live realtime audio and video chunks base64 roundtrip under stress`() {
        val random = Random(42)
        // Simulate 16kHz PCM audio chunk (1024 bytes)
        val audioBytes = ByteArray(1024).apply { random.nextBytes(this) }
        val audioB64 = DtoMappers.encodeBase64(audioBytes)

        // Simulate JPEG video frame (32 KB)
        val jpegBytes = ByteArray(32 * 1024).apply { random.nextBytes(this) }
        val jpegB64 = DtoMappers.encodeBase64(jpegBytes)

        val realtimeMessage = GeminiLiveClientMessage(
            realtimeInput = GeminiRealtimeInput(
                mediaChunks = listOf(
                    GeminiLiveBlob(mimeType = "audio/pcm;rate=16000", data = audioB64),
                    GeminiLiveBlob(mimeType = "image/jpeg", data = jpegB64)
                )
            )
        )

        val serialized = json.encodeToString(realtimeMessage)
        val deserialized = json.decodeFromString<GeminiLiveClientMessage>(serialized)

        assertNotNull(deserialized.realtimeInput)
        assertEquals(2, deserialized.realtimeInput?.mediaChunks?.size)

        val decodedAudio = DtoMappers.decodeBase64(deserialized.realtimeInput!!.mediaChunks[0].data)
        val decodedJpeg = DtoMappers.decodeBase64(deserialized.realtimeInput!!.mediaChunks[1].data)

        assertArrayEquals(audioBytes, decodedAudio)
        assertArrayEquals(jpegBytes, decodedJpeg)
    }

    @Test
    fun `empirical challenge - Server message with mixed text, 24kHz audio, and interruption`() {
        val pcmAudio = byteArrayOf(12, 34, 56, 78, 90)
        val pcmBase64 = DtoMappers.encodeBase64(pcmAudio)

        val serverJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            { "text": "Recognized object: " },
                            { "text": "Smart sunglasses." },
                            { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "$pcmBase64" } }
                        ]
                    },
                    "interrupted": true,
                    "turnComplete": false
                }
            }
        """.trimIndent()

        val serverMsg = json.decodeFromString<GeminiLiveServerMessage>(serverJson)
        val domainEvents = serverMsg.toGeminiMessages()

        // 1 Interruption + 2 Text + 1 Audio = 4 domain events
        assertEquals(4, domainEvents.size)
        assertTrue(domainEvents[0] is GeminiMessage.Interruption)
        assertTrue(domainEvents[1] is GeminiMessage.TextData)
        assertEquals("Recognized object: ", (domainEvents[1] as GeminiMessage.TextData).text)
        assertTrue(domainEvents[2] is GeminiMessage.TextData)
        assertEquals("Smart sunglasses.", (domainEvents[2] as GeminiMessage.TextData).text)
        assertTrue(domainEvents[3] is GeminiMessage.AudioData)
        assertArrayEquals(pcmAudio, (domainEvents[3] as GeminiMessage.AudioData).pcmBytes)
    }

    // =========================================================================
    // 2. MALFORMED & UNEXPECTED JSON RESILIENCE TESTS
    // =========================================================================

    @Test
    fun `empirical challenge - Malformed Base64 in server audio is gracefully isolated to GeminiMessage Error`() {
        val serverJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            { "text": "Valid text prefix." },
                            { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "%%%NotValidBase64@@@" } }
                        ]
                    },
                    "turnComplete": true
                }
            }
        """.trimIndent()

        val serverMsg = json.decodeFromString<GeminiLiveServerMessage>(serverJson)
        val domainEvents = serverMsg.toGeminiMessages()

        assertEquals(3, domainEvents.size) // Text, Error (from failed b64), TurnComplete
        assertTrue(domainEvents[0] is GeminiMessage.TextData)
        assertTrue(domainEvents[1] is GeminiMessage.Error)
        assertTrue((domainEvents[1] as GeminiMessage.Error).message?.contains("Failed to decode") == true)
        assertTrue(domainEvents[2] is GeminiMessage.TurnComplete)
    }

    @Test
    fun `empirical challenge - Server payload with future unmapped Gemini fields ignores unknown keys safely`() {
        val futureJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            { "text": "Hello user!", "thought": "Internal thinking process", "executableCode": { "language": "PYTHON", "code": "print(1)" } }
                        ]
                    },
                    "groundingMetadata": { "webSearchQueries": ["smart glasses 2026"] },
                    "usageMetadata": { "promptTokens": 100, "totalTokens": 150 },
                    "turnComplete": true
                },
                "sessionState": "ACTIVE",
                "customDiagnostics": { "latencyNanos": 12000000 }
            }
        """.trimIndent()

        val serverMsg = json.decodeFromString<GeminiLiveServerMessage>(futureJson)
        assertNotNull(serverMsg.serverContent)
        val domainEvents = serverMsg.toGeminiMessages()
        assertEquals(2, domainEvents.size) // TextData + TurnComplete
        assertEquals("Hello user!", (domainEvents[0] as GeminiMessage.TextData).text)
        assertTrue((domainEvents[1] as GeminiMessage.TurnComplete).isComplete)
    }

    @Test
    fun `empirical challenge - Completely empty or null-heavy JSON does not throw and produces empty domain events`() {
        val emptyJson = "{}"
        val serverMsg = json.decodeFromString<GeminiLiveServerMessage>(emptyJson)
        assertNull(serverMsg.setupComplete)
        assertNull(serverMsg.serverContent)
        assertNull(serverMsg.toolCall)

        val domainEvents = serverMsg.toGeminiMessages()
        assertTrue(domainEvents.isEmpty())
    }

    @Test(expected = SerializationException::class)
    fun `empirical challenge - Invalid JSON syntax throws SerializationException cleanly`() {
        val brokenJson = "{\"serverContent\": {\"modelTurn\": { \"parts\": [ "
        json.decodeFromString<GeminiLiveServerMessage>(brokenJson)
    }

    // =========================================================================
    // 3. REST PAYLOAD SERIALIZATION & DYNAMIC ROUTING TESTS
    // =========================================================================

    @Test
    fun `empirical challenge - REST request serialization with safety settings and system instruction`() {
        val fakeJpeg = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val req = SnapshotRequest(
            imageBytes = fakeJpeg,
            prompt = "What brand is this frame?",
            mimeType = "image/jpeg"
        )

        val restRequest = req.toRestRequest(
            systemInstruction = "Identify eyewear models precisely.",
            generationConfig = GeminiRestGenerationConfig(
                temperature = 0.1f,
                topK = 20,
                topP = 0.9f,
                maxOutputTokens = 512,
                stopSequences = listOf("END_OF_ANALYSIS")
            )
        )

        val serialized = json.encodeToString(restRequest)
        val deserialized = json.decodeFromString<GeminiRestRequest>(serialized)

        assertEquals("user", deserialized.contents.first().role)
        assertEquals("Identify eyewear models precisely.", deserialized.systemInstruction?.parts?.first()?.text)
        assertEquals(0.1f, deserialized.generationConfig?.temperature)
        assertEquals(listOf("END_OF_ANALYSIS"), deserialized.generationConfig?.stopSequences)
    }

    @Test
    fun `empirical challenge - REST response multi-candidate and empty fallback resilience`() {
        // Multi candidate response
        val multiCandidateJson = """
            {
                "candidates": [
                    {
                        "content": {
                            "parts": [{ "text": "Candidate 1 primary observation." }]
                        },
                        "finishReason": "STOP"
                    },
                    {
                        "content": {
                            "parts": [{ "text": "Candidate 2 secondary observation." }]
                        },
                        "finishReason": "STOP"
                    }
                ]
            }
        """.trimIndent()

        val parsedMulti = json.decodeFromString<GeminiRestResponse>(multiCandidateJson)
        val domainMulti = parsedMulti.toSnapshotResponse(latencyMs = 350L)
        assertEquals("Candidate 1 primary observation.", domainMulti.text)
        assertEquals("STOP", domainMulti.finishReason)
        assertEquals(350L, domainMulti.latencyMs)

        // Empty parts list fallback
        val emptyPartsJson = """
            {
                "candidates": [
                    {
                        "content": { "parts": [] },
                        "finishReason": "MAX_TOKENS"
                    }
                ]
            }
        """.trimIndent()

        val parsedEmpty = json.decodeFromString<GeminiRestResponse>(emptyPartsJson)
        val domainEmpty = parsedEmpty.toSnapshotResponse()
        assertEquals("No description generated.", domainEmpty.text)
        assertEquals("MAX_TOKENS", domainEmpty.finishReason)
    }

    // =========================================================================
    // 4. REST CLIENT DYNAMIC MODEL ROUTING EMPIRICAL TESTS
    // =========================================================================

    @Test
    fun `empirical challenge - GeminiRestClient dynamically routes to correct model endpoint URLs`() = runTest {
        val mockHttpClient = mockk<OkHttpClient>(relaxed = true)
        val restClient = GeminiRestClient(
            okHttpClient = mockHttpClient,
            json = json,
            dispatchersProvider = DefaultDispatchersProvider()
        )

        val capturedRequests = mutableListOf<Request>()
        val mockCall = mockk<Call>()
        every { mockHttpClient.newCall(capture(capturedRequests)) } returns mockCall
        every { mockCall.execute() } answers {
            Response.Builder()
                .request(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=test-key").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""
                    {
                        "candidates": [
                            { "content": { "parts": [{ "text": "Vision OK" }] }, "finishReason": "STOP" }
                        ]
                    }
                """.trimIndent().toResponseBody("application/json".toMediaType()))
                .build()
        }

        val testCases = listOf(
            "gemini-2.0-flash-exp" to "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=test-api-key",
            "models/gemini-2.0-flash-exp" to "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=test-api-key",
            "gemini-1.5-flash" to "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=test-api-key",
            "gemini-1.5-pro" to "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent?key=test-api-key",
            "custom-vision-v3" to "https://generativelanguage.googleapis.com/v1beta/models/custom-vision-v3:generateContent?key=test-api-key"
        )

        for ((inputModel, expectedUrl) in testCases) {
            val req = SnapshotRequest(imageBytes = byteArrayOf(10, 20, 30))
            val result = restClient.analyzeSnapshot(
                apiKey = "test-api-key",
                model = inputModel,
                request = req
            )

            assertTrue(result is Result.Success)
            assertEquals(expectedUrl, capturedRequests.last().url.toString())
        }
    }

    @Test
    fun `empirical challenge - GeminiRestClient handles non-200 HTTP codes with structured error parsing`() = runTest {
        val mockHttpClient = mockk<OkHttpClient>(relaxed = true)
        val restClient = GeminiRestClient(
            okHttpClient = mockHttpClient,
            json = json,
            dispatchersProvider = DefaultDispatchersProvider()
        )

        val mockCall = mockk<Call>()
        val errorResponseJson = """
            {
                "error": {
                    "code": 429,
                    "message": "Resource has been exhausted (e.g. check quota).",
                    "status": "RESOURCE_EXHAUSTED"
                }
            }
        """.trimIndent()

        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=key").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .body(errorResponseJson.toResponseBody("application/json".toMediaType()))
            .build()

        every { mockHttpClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns mockResponse

        val result = restClient.analyzeSnapshot(
            apiKey = "key",
            model = "gemini-2.0-flash-exp",
            request = SnapshotRequest(imageBytes = byteArrayOf(1, 2, 3))
        )

        assertTrue(result is Result.Error)
        val errorResult = result as Result.Error
        assertEquals("Resource has been exhausted (e.g. check quota).", errorResult.message)
    }

    @Test
    fun `empirical challenge - Base64 overload decode error handling`() = runTest {
        val restClient = GeminiRestClient(
            okHttpClient = mockk(relaxed = true),
            json = json,
            dispatchersProvider = DefaultDispatchersProvider()
        )

        val invalidB64 = "!!Invalid-Base-64-String!!"
        val result = restClient.analyzeSnapshot(
            apiKey = "valid-key",
            model = "gemini-2.0-flash-exp",
            jpegBase64 = invalidB64
        )

        assertTrue(result is Result.Error)
        assertEquals("Invalid base64 JPEG payload", (result as Result.Error).message)
    }
}
