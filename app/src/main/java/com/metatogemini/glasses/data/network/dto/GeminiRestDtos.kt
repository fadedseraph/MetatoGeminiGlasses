package com.metatogemini.glasses.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==========================================
// Gemini Multimodal REST API Request DTOs
// ==========================================

@Serializable
data class GeminiRestRequest(
    @SerialName("contents")
    val contents: List<GeminiRestContent>,
    @SerialName("systemInstruction")
    val systemInstruction: GeminiRestContent? = null,
    @SerialName("generationConfig")
    val generationConfig: GeminiRestGenerationConfig? = null,
    @SerialName("safetySettings")
    val safetySettings: List<GeminiRestSafetySetting>? = null
)

@Serializable
data class GeminiRestContent(
    @SerialName("role")
    val role: String = "user",
    @SerialName("parts")
    val parts: List<GeminiRestPart>
)

@Serializable
data class GeminiRestPart(
    @SerialName("text")
    val text: String? = null,
    @SerialName("inlineData")
    val inlineData: GeminiRestInlineData? = null
)

@Serializable
data class GeminiRestInlineData(
    @SerialName("mimeType")
    val mimeType: String = "image/jpeg",
    @SerialName("data")
    val data: String // Base64 JPEG
)

@Serializable
data class GeminiRestGenerationConfig(
    @SerialName("temperature")
    val temperature: Float? = 0.4f,
    @SerialName("topK")
    val topK: Int? = 32,
    @SerialName("topP")
    val topP: Float? = 0.95f,
    @SerialName("maxOutputTokens")
    val maxOutputTokens: Int? = 1024,
    @SerialName("stopSequences")
    val stopSequences: List<String>? = null
)

@Serializable
data class GeminiRestSafetySetting(
    @SerialName("category")
    val category: String,
    @SerialName("threshold")
    val threshold: String
)

// ==========================================
// Gemini Multimodal REST API Response DTOs
// ==========================================

@Serializable
data class GeminiRestResponse(
    @SerialName("candidates")
    val candidates: List<GeminiRestCandidate>? = null,
    @SerialName("usageMetadata")
    val usageMetadata: GeminiRestUsageMetadata? = null,
    @SerialName("promptFeedback")
    val promptFeedback: GeminiRestPromptFeedback? = null,
    @SerialName("error")
    val error: GeminiRestError? = null
)

@Serializable
data class GeminiRestCandidate(
    @SerialName("content")
    val content: GeminiRestContent? = null,
    @SerialName("finishReason")
    val finishReason: String? = null,
    @SerialName("index")
    val index: Int? = 0
)

@Serializable
data class GeminiRestUsageMetadata(
    @SerialName("promptTokenCount")
    val promptTokenCount: Int = 0,
    @SerialName("candidatesTokenCount")
    val candidatesTokenCount: Int = 0,
    @SerialName("totalTokenCount")
    val totalTokenCount: Int = 0
)

@Serializable
data class GeminiRestPromptFeedback(
    @SerialName("blockReason")
    val blockReason: String? = null
)

@Serializable
data class GeminiRestError(
    @SerialName("code")
    val code: Int? = null,
    @SerialName("message")
    val message: String? = null,
    @SerialName("status")
    val status: String? = null
)
