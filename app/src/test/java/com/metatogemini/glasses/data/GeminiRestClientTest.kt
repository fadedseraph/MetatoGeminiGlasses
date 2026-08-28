package com.metatogemini.glasses.data

import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.data.network.rest.GeminiRestClient
import com.metatogemini.glasses.domain.model.SnapshotRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiRestClientTest {

    private val okHttpClient: OkHttpClient = mockk(relaxed = true)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val dispatchersProvider = DefaultDispatchersProvider()
    private lateinit var restClient: GeminiRestClient

    @Before
    fun setup() {
        restClient = GeminiRestClient(
            okHttpClient = okHttpClient,
            json = json,
            dispatchersProvider = dispatchersProvider
        )
    }

    @Test
    fun `analyzeSnapshot with blank API key returns Error immediately`() = runTest {
        val request = SnapshotRequest(imageBytes = byteArrayOf(1, 2, 3))
        val result = restClient.analyzeSnapshot(apiKey = "", model = "gemini-2.0-flash-exp", request = request)

        assertTrue(result is Result.Error)
        assertEquals("API key is missing", (result as Result.Error).message)
    }

    @Test
    fun `analyzeSnapshot with empty image bytes returns Error immediately`() = runTest {
        val request = SnapshotRequest(imageBytes = byteArrayOf())
        val result = restClient.analyzeSnapshot(apiKey = "valid-key", model = "gemini-2.0-flash-exp", request = request)

        assertTrue(result is Result.Error)
        assertEquals("Image data is empty", (result as Result.Error).message)
    }

    @Test
    fun `analyzeSnapshot with successful HTTP response returns Success with content`() = runTest {
        val request = SnapshotRequest(imageBytes = byteArrayOf(1, 2, 3, 4), prompt = "Test prompt")
        val jsonResponseBody = """
            {
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                { "text": "Multimodal analysis of scene." }
                            ]
                        },
                        "finishReason": "STOP"
                    }
                ]
            }
        """.trimIndent()

        val mockCall = mockk<Call>()
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=key").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(jsonResponseBody.toResponseBody("application/json".toMediaType()))
            .build()

        every { okHttpClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns mockResponse

        val result = restClient.analyzeSnapshot(apiKey = "key", model = "gemini-2.0-flash-exp", request = request)

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals("Multimodal analysis of scene.", data.text)
        assertEquals("STOP", data.finishReason)
    }

    @Test
    fun `analyzeSnapshot with HTTP 403 error returns Error result`() = runTest {
        val request = SnapshotRequest(imageBytes = byteArrayOf(1, 2, 3))
        val errorJson = """
            {
                "error": {
                    "code": 403,
                    "message": "Permission denied for model.",
                    "status": "PERMISSION_DENIED"
                }
            }
        """.trimIndent()

        val mockCall = mockk<Call>()
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=key").build())
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .body(errorJson.toResponseBody("application/json".toMediaType()))
            .build()

        every { okHttpClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns mockResponse

        val result = restClient.analyzeSnapshot(apiKey = "key", model = "gemini-2.0-flash-exp", request = request)

        assertTrue(result is Result.Error)
        assertEquals("Permission denied for model.", (result as Result.Error).message)
    }
}
