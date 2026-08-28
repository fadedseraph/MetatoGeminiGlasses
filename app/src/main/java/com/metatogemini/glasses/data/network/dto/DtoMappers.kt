package com.metatogemini.glasses.data.network.dto

import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import java.util.Base64

/**
 * Utility mappers converting between Network DTOs and Domain Models.
 */
object DtoMappers {

    fun encodeBase64(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decodeBase64(base64String: String): ByteArray {
        return Base64.getDecoder().decode(base64String)
    }

    /**
     * Maps [SnapshotRequest] into [GeminiRestRequest] payload for REST API.
     */
    fun SnapshotRequest.toRestRequest(
        systemInstruction: String? = null,
        generationConfig: GeminiRestGenerationConfig = GeminiRestGenerationConfig(
            temperature = 0.4f,
            maxOutputTokens = 1024
        )
    ): GeminiRestRequest {
        val base64Image = encodeBase64(this.imageBytes)
        val parts = mutableListOf<GeminiRestPart>()

        if (this.prompt.isNotBlank()) {
            parts.add(GeminiRestPart(text = this.prompt))
        }
        parts.add(
            GeminiRestPart(
                inlineData = GeminiRestInlineData(
                    mimeType = this.mimeType,
                    data = base64Image
                )
            )
        )

        val systemInstructionContent = systemInstruction?.takeIf { it.isNotBlank() }?.let {
            GeminiRestContent(
                role = "system",
                parts = listOf(GeminiRestPart(text = it))
            )
        }

        return GeminiRestRequest(
            contents = listOf(
                GeminiRestContent(
                    role = "user",
                    parts = parts
                )
            ),
            systemInstruction = systemInstructionContent,
            generationConfig = generationConfig
        )
    }

    /**
     * Maps [GeminiRestResponse] into domain [SnapshotResponse].
     */
    fun GeminiRestResponse.toSnapshotResponse(latencyMs: Long = 0L): SnapshotResponse {
        val firstCandidate = this.candidates?.firstOrNull()
        val text = firstCandidate?.content?.parts
            ?.mapNotNull { it.text }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
            ?: (this.error?.message ?: "No description generated.")

        val finishReason = firstCandidate?.finishReason ?: if (this.error != null) "ERROR" else "STOP"

        return SnapshotResponse(
            text = text,
            latencyMs = latencyMs,
            finishReason = finishReason
        )
    }

    /**
     * Maps [GeminiLiveServerMessage] into a list of domain [GeminiMessage] events.
     */
    fun GeminiLiveServerMessage.toGeminiMessages(): List<GeminiMessage> {
        val messages = mutableListOf<GeminiMessage>()

        if (this.setupComplete != null) {
            messages.add(GeminiMessage.SetupComplete)
        }

        this.serverContent?.let { content ->
            if (content.interrupted) {
                messages.add(GeminiMessage.Interruption())
            }

            content.modelTurn?.parts?.forEach { part ->
                part.text?.takeIf { it.isNotEmpty() }?.let {
                    messages.add(GeminiMessage.TextData(text = it, isFromUser = false))
                }
                part.inlineData?.takeIf { it.data.isNotEmpty() }?.let { blob ->
                    try {
                        val pcmBytes = decodeBase64(blob.data)
                        messages.add(GeminiMessage.AudioData(pcmBytes = pcmBytes, sampleRate = 24000))
                    } catch (e: Exception) {
                        messages.add(GeminiMessage.Error(e, "Failed to decode inline audio data"))
                    }
                }
            }

            if (content.turnComplete) {
                messages.add(GeminiMessage.TurnComplete(true))
            }
        }

        return messages
    }
}
