package com.metatogemini.glasses.data

import com.metatogemini.glasses.data.network.dto.DtoMappers.toRestRequest
import com.metatogemini.glasses.data.network.dto.DtoMappers.toSnapshotResponse
import com.metatogemini.glasses.data.network.dto.GeminiRestContent
import com.metatogemini.glasses.data.network.dto.GeminiRestGenerationConfig
import com.metatogemini.glasses.data.network.dto.GeminiRestPart
import com.metatogemini.glasses.data.network.dto.GeminiRestRequest
import com.metatogemini.glasses.data.network.dto.GeminiRestResponse
import com.metatogemini.glasses.domain.model.SnapshotRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiRestSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun `test REST request serialization with image and prompt`() {
        val fakeJpeg = byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70)
        val request = SnapshotRequest(
            imageBytes = fakeJpeg,
            prompt = "What is in this picture?",
            mimeType = "image/jpeg"
        )

        val restRequest = request.toRestRequest(
            systemInstruction = "Analyze visual scene accurately.",
            generationConfig = GeminiRestGenerationConfig(temperature = 0.2f, maxOutputTokens = 512)
        )

        val jsonString = json.encodeToString(restRequest)
        assertTrue(jsonString.contains("What is in this picture?"))
        assertTrue(jsonString.contains("image/jpeg"))
        assertTrue(jsonString.contains("Analyze visual scene accurately."))

        val deserialized = json.decodeFromString<GeminiRestRequest>(jsonString)
        assertEquals("user", deserialized.contents.first().role)
        assertEquals(2, deserialized.contents.first().parts.size)
        assertEquals("What is in this picture?", deserialized.contents.first().parts[0].text)
        assertNotNull(deserialized.contents.first().parts[1].inlineData)
        assertEquals(0.2f, deserialized.generationConfig?.temperature)
    }

    @Test
    fun `test REST response deserialization with candidates and token usage`() {
        val jsonString = """
            {
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                {
                                    "text": "A close-up view of smart glasses on a wooden table."
                                }
                            ],
                            "role": "model"
                        },
                        "finishReason": "STOP",
                        "index": 0
                    }
                ],
                "usageMetadata": {
                    "promptTokenCount": 258,
                    "candidatesTokenCount": 14,
                    "totalTokenCount": 272
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<GeminiRestResponse>(jsonString)
        assertNotNull(response.candidates)
        assertEquals(1, response.candidates?.size)
        assertEquals(
            "A close-up view of smart glasses on a wooden table.",
            response.candidates?.first()?.content?.parts?.first()?.text
        )
        assertEquals(272, response.usageMetadata?.totalTokenCount)

        val domainResponse = response.toSnapshotResponse(latencyMs = 450L)
        assertEquals("A close-up view of smart glasses on a wooden table.", domainResponse.text)
        assertEquals(450L, domainResponse.latencyMs)
        assertEquals("STOP", domainResponse.finishReason)
    }

    @Test
    fun `test REST error response parsing and mapping`() {
        val jsonString = """
            {
                "error": {
                    "code": 400,
                    "message": "API key not valid. Please pass a valid API key.",
                    "status": "INVALID_ARGUMENT"
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<GeminiRestResponse>(jsonString)
        assertNotNull(response.error)
        assertEquals(400, response.error?.code)
        assertEquals("API key not valid. Please pass a valid API key.", response.error?.message)

        val domainResponse = response.toSnapshotResponse(latencyMs = 120L)
        assertEquals("API key not valid. Please pass a valid API key.", domainResponse.text)
        assertEquals("ERROR", domainResponse.finishReason)
    }

    @Test
    fun `test empty candidate response fallback`() {
        val jsonString = """
            {
                "candidates": []
            }
        """.trimIndent()

        val response = json.decodeFromString<GeminiRestResponse>(jsonString)
        val domainResponse = response.toSnapshotResponse()
        assertEquals("No description generated.", domainResponse.text)
    }
}
