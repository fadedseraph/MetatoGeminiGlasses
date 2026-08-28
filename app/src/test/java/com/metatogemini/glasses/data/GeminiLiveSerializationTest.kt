package com.metatogemini.glasses.data

import com.metatogemini.glasses.data.network.dto.DtoMappers
import com.metatogemini.glasses.data.network.dto.DtoMappers.toGeminiMessages
import com.metatogemini.glasses.data.network.dto.GeminiClientContent
import com.metatogemini.glasses.data.network.dto.GeminiLiveBlob
import com.metatogemini.glasses.data.network.dto.GeminiLiveClientMessage
import com.metatogemini.glasses.data.network.dto.GeminiLiveContent
import com.metatogemini.glasses.data.network.dto.GeminiLiveGenerationConfig
import com.metatogemini.glasses.data.network.dto.GeminiLivePart
import com.metatogemini.glasses.data.network.dto.GeminiLivePrebuiltVoiceConfig
import com.metatogemini.glasses.data.network.dto.GeminiLiveServerMessage
import com.metatogemini.glasses.data.network.dto.GeminiLiveSetup
import com.metatogemini.glasses.data.network.dto.GeminiLiveSpeechConfig
import com.metatogemini.glasses.data.network.dto.GeminiLiveVoiceConfig
import com.metatogemini.glasses.data.network.dto.GeminiRealtimeInput
import com.metatogemini.glasses.domain.model.GeminiMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun `test setup message serialization matches Gemini Live API spec`() {
        val setupMsg = GeminiLiveClientMessage(
            setup = GeminiLiveSetup(
                model = "models/gemini-2.0-flash-exp",
                generationConfig = GeminiLiveGenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = GeminiLiveSpeechConfig(
                        voiceConfig = GeminiLiveVoiceConfig(
                            prebuiltVoiceConfig = GeminiLivePrebuiltVoiceConfig(voiceName = "Puck")
                        )
                    ),
                    temperature = 0.7f
                ),
                systemInstruction = GeminiLiveContent(
                    role = "system",
                    parts = listOf(GeminiLivePart(text = "You are an AI assistant."))
                )
            )
        )

        val jsonString = json.encodeToString(setupMsg)
        assertTrue(jsonString.contains("models/gemini-2.0-flash-exp"))
        assertTrue(jsonString.contains("AUDIO"))
        assertTrue(jsonString.contains("Puck"))
        assertTrue(jsonString.contains("You are an AI assistant."))

        val deserialized = json.decodeFromString<GeminiLiveClientMessage>(jsonString)
        assertEquals("models/gemini-2.0-flash-exp", deserialized.setup?.model)
        assertEquals("Puck", deserialized.setup?.generationConfig?.speechConfig?.voiceConfig?.prebuiltVoiceConfig?.voiceName)
    }

    @Test
    fun `test realtimeInput audio chunk serialization`() {
        val rawPcm = byteArrayOf(0, 1, 2, 3, 4, 5)
        val base64Data = DtoMappers.encodeBase64(rawPcm)

        val audioMsg = GeminiLiveClientMessage(
            realtimeInput = GeminiRealtimeInput(
                mediaChunks = listOf(
                    GeminiLiveBlob(
                        mimeType = "audio/pcm;rate=16000",
                        data = base64Data
                    )
                )
            )
        )

        val jsonString = json.encodeToString(audioMsg)
        assertTrue(jsonString.contains("audio/pcm;rate=16000"))
        assertTrue(jsonString.contains(base64Data))

        val decoded = json.decodeFromString<GeminiLiveClientMessage>(jsonString)
        assertEquals("audio/pcm;rate=16000", decoded.realtimeInput?.mediaChunks?.first()?.mimeType)
        assertArrayEquals(rawPcm, DtoMappers.decodeBase64(decoded.realtimeInput!!.mediaChunks.first().data))
    }

    @Test
    fun `test realtimeInput video frame chunk serialization`() {
        val rawJpeg = byteArrayOf(-1, -40, -1, -32, 0, 16) // JPEG magic header 0xFF 0xD8 0xFF 0xE0
        val base64Data = DtoMappers.encodeBase64(rawJpeg)

        val videoMsg = GeminiLiveClientMessage(
            realtimeInput = GeminiRealtimeInput(
                mediaChunks = listOf(
                    GeminiLiveBlob(
                        mimeType = "image/jpeg",
                        data = base64Data
                    )
                )
            )
        )

        val jsonString = json.encodeToString(videoMsg)
        assertTrue(jsonString.contains("image/jpeg"))
        assertTrue(jsonString.contains(base64Data))
    }

    @Test
    fun `test clientContent text injection serialization`() {
        val clientContent = GeminiLiveClientMessage(
            clientContent = GeminiClientContent(
                turns = listOf(
                    GeminiLiveContent(
                        role = "user",
                        parts = listOf(GeminiLivePart(text = "Hello Gemini"))
                    )
                ),
                turnComplete = true
            )
        )

        val jsonString = json.encodeToString(clientContent)
        assertTrue(jsonString.contains("Hello Gemini"))
        assertTrue(jsonString.contains("turnComplete"))
    }

    @Test
    fun `test server setup complete deserialization`() {
        val jsonString = """
            {
                "setupComplete": {
                    "model": "models/gemini-2.0-flash-exp"
                }
            }
        """.trimIndent()

        val serverMsg = json.decodeFromString<GeminiLiveServerMessage>(jsonString)
        assertNotNull(serverMsg.setupComplete)
        assertEquals("models/gemini-2.0-flash-exp", serverMsg.setupComplete?.model)

        val domainMessages = serverMsg.toGeminiMessages()
        assertEquals(1, domainMessages.size)
        assertTrue(domainMessages.first() is GeminiMessage.SetupComplete)
    }

    @Test
    fun `test server turn response with text and inline audio deserialization`() {
        val rawAudio = byteArrayOf(10, 20, 30, 40)
        val base64Audio = DtoMappers.encodeBase64(rawAudio)

        val jsonString = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            {
                                "text": "I see a laptop on the desk."
                            },
                            {
                                "inlineData": {
                                    "mimeType": "audio/pcm;rate=24000",
                                    "data": "$base64Audio"
                                }
                            }
                        ]
                    },
                    "turnComplete": true,
                    "interrupted": false
                }
            }
        """.trimIndent()

        val serverMsg = json.decodeFromString<GeminiLiveServerMessage>(jsonString)
        assertNotNull(serverMsg.serverContent)
        assertEquals(2, serverMsg.serverContent?.modelTurn?.parts?.size)

        val domainMessages = serverMsg.toGeminiMessages()
        assertEquals(3, domainMessages.size) // Text, Audio, TurnComplete

        val textMsg = domainMessages[0] as GeminiMessage.TextData
        assertEquals("I see a laptop on the desk.", textMsg.text)

        val audioMsg = domainMessages[1] as GeminiMessage.AudioData
        assertArrayEquals(rawAudio, audioMsg.pcmBytes)
        assertEquals(24000, audioMsg.sampleRate)

        val turnMsg = domainMessages[2] as GeminiMessage.TurnComplete
        assertTrue(turnMsg.isComplete)
    }

    @Test
    fun `test server interruption signal deserialization`() {
        val jsonString = """
            {
                "serverContent": {
                    "interrupted": true,
                    "turnComplete": false
                }
            }
        """.trimIndent()

        val serverMsg = json.decodeFromString<GeminiLiveServerMessage>(jsonString)
        assertTrue(serverMsg.serverContent?.interrupted == true)

        val domainMessages = serverMsg.toGeminiMessages()
        assertEquals(1, domainMessages.size)
        assertTrue(domainMessages.first() is GeminiMessage.Interruption)
    }

    @Test
    fun `test unknown fields in server payload do not fail deserialization`() {
        val jsonString = """
            {
                "futureField": 12345,
                "extraMetadata": { "key": "value" },
                "serverContent": {
                    "turnComplete": true,
                    "unexpectedBool": true
                }
            }
        """.trimIndent()

        val serverMsg = json.decodeFromString<GeminiLiveServerMessage>(jsonString)
        assertNotNull(serverMsg.serverContent)
        assertTrue(serverMsg.serverContent?.turnComplete == true)
    }
}
