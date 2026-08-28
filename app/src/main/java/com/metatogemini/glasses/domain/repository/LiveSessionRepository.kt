package com.metatogemini.glasses.domain.repository

import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LiveSessionRepository {
    val connectionState: StateFlow<ConnectionState>
    val incomingMessages: Flow<GeminiMessage>

    suspend fun startSession(config: SessionConfig)
    suspend fun sendAudioChunk(pcmData: ByteArray, sampleRate: Int = 16000)
    suspend fun sendVideoFrame(jpegData: ByteArray)
    suspend fun sendTextMessage(text: String)
    suspend fun stopSession()
}
