package com.metatogemini.glasses.data

import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.data.network.rest.GeminiRestClient
import com.metatogemini.glasses.data.repository.SnapshotRepositoryImpl
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SnapshotRepositoryImplTest {

    private val restClient: GeminiRestClient = mockk(relaxed = true)
    private val dispatchersProvider = DefaultDispatchersProvider()
    private lateinit var repository: SnapshotRepositoryImpl

    @Before
    fun setup() {
        repository = SnapshotRepositoryImpl(
            restClient = restClient,
            dispatchersProvider = dispatchersProvider
        )
    }

    @Test
    fun `analyzeSnapshot with empty API key returns Error result without network call`() = runTest {
        val request = SnapshotRequest(imageBytes = byteArrayOf(1, 2, 3))
        val configWithEmptyKey = SessionConfig(apiKey = "")

        val result = repository.analyzeSnapshot(request, configWithEmptyKey)

        assertTrue(result is Result.Error)
        coVerify(exactly = 0) { restClient.analyzeSnapshot(any<String>(), any<String>(), any<SnapshotRequest>(), any()) }
    }

    @Test
    fun `analyzeSnapshot with valid config delegates to rest client`() = runTest {
        val request = SnapshotRequest(imageBytes = byteArrayOf(1, 2, 3), prompt = "Describe scene")
        val config = SessionConfig(
            apiKey = "valid-api-key",
            model = "gemini-1.5-pro",
            systemInstruction = "System prompt"
        )
        val expected = SnapshotResponse(text = "Office desk", latencyMs = 250L)

        coEvery {
            restClient.analyzeSnapshot("valid-api-key", "gemini-1.5-pro", request, "System prompt")
        } returns Result.Success(expected)

        val result = repository.analyzeSnapshot(request, config)

        assertTrue(result is Result.Success)
        assertEquals(expected, (result as Result.Success).data)
        coVerify(exactly = 1) {
            restClient.analyzeSnapshot("valid-api-key", "gemini-1.5-pro", request, "System prompt")
        }
    }
}
