package com.metatogemini.glasses.data.network.rest

import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.core.common.DispatchersProvider
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.data.network.dto.DtoMappers.toRestRequest
import com.metatogemini.glasses.data.network.dto.DtoMappers.toSnapshotResponse
import com.metatogemini.glasses.data.network.dto.GeminiRestResponse
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * REST Client for Gemini Multimodal Vision API (Snapshot analysis).
 * Supports dynamic model selection (gemini-2.0-flash-exp, gemini-1.5-flash, gemini-1.5-pro).
 */
class GeminiRestClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val dispatchersProvider: DispatchersProvider
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Executes multimodal vision query against Gemini REST API with the provided snapshot request.
     */
    suspend fun analyzeSnapshot(
        apiKey: String,
        model: String = Constants.DEFAULT_MODEL,
        request: SnapshotRequest,
        systemInstruction: String? = Constants.DEFAULT_SYSTEM_INSTRUCTION
    ): Result<SnapshotResponse> = withContext(dispatchersProvider.io) {
        if (apiKey.isBlank()) {
            return@withContext Result.Error(
                IllegalArgumentException("Gemini API key cannot be blank"),
                "API key is missing"
            )
        }

        if (request.imageBytes.isEmpty()) {
            return@withContext Result.Error(
                IllegalArgumentException("Snapshot image bytes cannot be empty"),
                "Image data is empty"
            )
        }

        val startTime = System.currentTimeMillis()
        val modelIdentifier = if (model.startsWith("models/")) model else "models/$model"
        val url = "${Constants.GEMINI_REST_BASE_URL}/$modelIdentifier:generateContent?key=$apiKey"

        val requestPayload = request.toRestRequest(systemInstruction = systemInstruction)
        val jsonBodyString = try {
            json.encodeToString(requestPayload)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to serialize REST request", e)
            return@withContext Result.Error(e, "Serialization failure: ${e.message}")
        }

        val httpRequestBody = jsonBodyString.toRequestBody(jsonMediaType)
        val httpRequest = Request.Builder()
            .url(url)
            .post(httpRequestBody)
            .build()

        AppLogger.i(TAG, "Sending snapshot request to Gemini REST: $modelIdentifier")

        try {
            okHttpClient.newCall(httpRequest).execute().use { response ->
                val latencyMs = System.currentTimeMillis() - startTime
                val responseBodyString = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    AppLogger.e(TAG, "Gemini REST API error HTTP ${response.code}: $responseBodyString")
                    return@withContext try {
                        val parsedErrorResponse = json.decodeFromString<GeminiRestResponse>(responseBodyString)
                        val errorMsg = parsedErrorResponse.error?.message
                            ?: "HTTP ${response.code}: ${response.message}"
                        Result.Error(IOException("Gemini API Error ($errorMsg)"), errorMsg)
                    } catch (e: Exception) {
                        Result.Error(
                            IOException("HTTP ${response.code} error: $responseBodyString"),
                            "Server returned HTTP ${response.code}"
                        )
                    }
                }

                val parsedResponse = json.decodeFromString<GeminiRestResponse>(responseBodyString)
                val snapshotResponse = parsedResponse.toSnapshotResponse(latencyMs = latencyMs)
                AppLogger.i(TAG, "Snapshot analysis completed in ${latencyMs}ms")
                Result.Success(snapshotResponse)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Network failure calling Gemini REST API", e)
            Result.Error(e, "Network error: ${e.message}")
        }
    }

    /**
     * Overload accepting Base64 JPEG string and prompt directly.
     */
    suspend fun analyzeSnapshot(
        apiKey: String,
        model: String,
        jpegBase64: String,
        prompt: String = "Describe what you see in detail."
    ): Result<String> = withContext(dispatchersProvider.io) {
        val imageBytes = try {
            java.util.Base64.getDecoder().decode(jpegBase64)
        } catch (e: Exception) {
            return@withContext Result.Error(e, "Invalid base64 JPEG payload")
        }

        val request = SnapshotRequest(imageBytes = imageBytes, prompt = prompt)
        when (val result = analyzeSnapshot(apiKey, model, request)) {
            is Result.Success -> Result.Success(result.data.text)
            is Result.Error -> Result.Error(result.exception, result.message)
            is Result.Loading -> Result.Loading
        }
    }

    companion object {
        private const val TAG = "GeminiRestClient"
    }
}
