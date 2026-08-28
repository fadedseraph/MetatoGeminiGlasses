package com.metatogemini.glasses.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==========================================
// Client -> Server Payloads (Upstream)
// ==========================================

@Serializable
data class GeminiLiveClientMessage(
    @SerialName("setup")
    val setup: GeminiLiveSetup? = null,
    @SerialName("realtimeInput")
    val realtimeInput: GeminiRealtimeInput? = null,
    @SerialName("clientContent")
    val clientContent: GeminiClientContent? = null
)

@Serializable
data class GeminiLiveSetup(
    @SerialName("model")
    val model: String,
    @SerialName("generationConfig")
    val generationConfig: GeminiLiveGenerationConfig? = null,
    @SerialName("systemInstruction")
    val systemInstruction: GeminiLiveContent? = null
)

@Serializable
data class GeminiLiveGenerationConfig(
    @SerialName("responseModalities")
    val responseModalities: List<String> = listOf("AUDIO"),
    @SerialName("speechConfig")
    val speechConfig: GeminiLiveSpeechConfig? = null,
    @SerialName("temperature")
    val temperature: Float? = null,
    @SerialName("topP")
    val topP: Float? = null,
    @SerialName("topK")
    val topK: Int? = null,
    @SerialName("maxOutputTokens")
    val maxOutputTokens: Int? = null
)

@Serializable
data class GeminiLiveSpeechConfig(
    @SerialName("voiceConfig")
    val voiceConfig: GeminiLiveVoiceConfig
)

@Serializable
data class GeminiLiveVoiceConfig(
    @SerialName("prebuiltVoiceConfig")
    val prebuiltVoiceConfig: GeminiLivePrebuiltVoiceConfig
)

@Serializable
data class GeminiLivePrebuiltVoiceConfig(
    @SerialName("voiceName")
    val voiceName: String = "Puck"
)

@Serializable
data class GeminiRealtimeInput(
    @SerialName("mediaChunks")
    val mediaChunks: List<GeminiLiveBlob>
)

@Serializable
data class GeminiClientContent(
    @SerialName("turns")
    val turns: List<GeminiLiveContent>,
    @SerialName("turnComplete")
    val turnComplete: Boolean = true
)

@Serializable
data class GeminiLiveContent(
    @SerialName("role")
    val role: String = "user",
    @SerialName("parts")
    val parts: List<GeminiLivePart>
)

@Serializable
data class GeminiLivePart(
    @SerialName("text")
    val text: String? = null,
    @SerialName("inlineData")
    val inlineData: GeminiLiveBlob? = null
)

@Serializable
data class GeminiLiveBlob(
    @SerialName("mimeType")
    val mimeType: String,
    @SerialName("data")
    val data: String // Base64 encoded payload
)

// ==========================================
// Server -> Client Payloads (Downstream)
// ==========================================

@Serializable
data class GeminiLiveServerMessage(
    @SerialName("setupComplete")
    val setupComplete: GeminiLiveSetupComplete? = null,
    @SerialName("serverContent")
    val serverContent: GeminiLiveServerContent? = null,
    @SerialName("toolCall")
    val toolCall: GeminiLiveToolCall? = null,
    @SerialName("toolCallCancellation")
    val toolCallCancellation: GeminiLiveToolCallCancellation? = null,
    @SerialName("error")
    val error: GeminiLiveError? = null
)

@Serializable
data class GeminiLiveError(
    @SerialName("code")
    val code: Int? = null,
    @SerialName("message")
    val message: String? = null,
    @SerialName("status")
    val status: String? = null
)

@Serializable
data class GeminiLiveSetupComplete(
    @SerialName("model")
    val model: String? = null
)

@Serializable
data class GeminiLiveServerContent(
    @SerialName("modelTurn")
    val modelTurn: GeminiLiveModelTurn? = null,
    @SerialName("turnComplete")
    val turnComplete: Boolean = false,
    @SerialName("interrupted")
    val interrupted: Boolean = false
)

@Serializable
data class GeminiLiveModelTurn(
    @SerialName("parts")
    val parts: List<GeminiLiveServerPart> = emptyList()
)

@Serializable
data class GeminiLiveServerPart(
    @SerialName("text")
    val text: String? = null,
    @SerialName("inlineData")
    val inlineData: GeminiLiveBlob? = null
)

@Serializable
data class GeminiLiveToolCall(
    @SerialName("functionCalls")
    val functionCalls: List<GeminiLiveFunctionCall> = emptyList()
)

@Serializable
data class GeminiLiveFunctionCall(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String? = null
)

@Serializable
data class GeminiLiveToolCallCancellation(
    @SerialName("ids")
    val ids: List<String> = emptyList()
)
