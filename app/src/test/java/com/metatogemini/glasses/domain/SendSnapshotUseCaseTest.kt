package com.metatogemini.glasses.domain

import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import com.metatogemini.glasses.domain.repository.SettingsRepository
import com.metatogemini.glasses.domain.repository.SnapshotRepository
import com.metatogemini.glasses.domain.usecase.SendSnapshotUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SendSnapshotUseCaseTest {

    private val snapshotRepository: SnapshotRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private lateinit var useCase: SendSnapshotUseCase

    private val testConfig = SessionConfig(
        apiKey = "test-api-key",
        model = "gemini-1.5-flash",
        systemInstruction = "Analyze photo"
    )

    @Before
    fun setup() {
        useCase = SendSnapshotUseCase(
            snapshotRepository = snapshotRepository,
            settingsRepository = settingsRepository
        )
        coEvery { settingsRepository.getSessionConfig() } returns testConfig
    }

    @Test
    fun `invoke with request delegates to snapshot repository and returns success`() = runTest {
        val request = SnapshotRequest(
            imageBytes = byteArrayOf(1, 2, 3, 4),
            prompt = "What is this?"
        )
        val expectedResponse = SnapshotResponse(
            text = "This is a coffee cup.",
            latencyMs = 320L,
            finishReason = "STOP"
        )
        coEvery { snapshotRepository.analyzeSnapshot(request, testConfig) } returns Result.Success(expectedResponse)

        val result = useCase(request)

        assertTrue(result is Result.Success)
        assertEquals(expectedResponse, (result as Result.Success).data)
        coVerify(exactly = 1) { snapshotRepository.analyzeSnapshot(request, testConfig) }
    }

    @Test
    fun `invoke with image bytes and prompt creates request and delegates to repository`() = runTest {
        val imageBytes = byteArrayOf(10, 20, 30)
        val prompt = "Inspect sign"
        val expectedResponse = SnapshotResponse(text = "Exit Sign")
        coEvery {
            snapshotRepository.analyzeSnapshot(
                match { it.imageBytes.contentEquals(imageBytes) && it.prompt == prompt },
                testConfig
            )
        } returns Result.Success(expectedResponse)

        val result = useCase(imageBytes, prompt)

        assertTrue(result is Result.Success)
        assertEquals("Exit Sign", (result as Result.Success).data.text)
    }

    @Test
    fun `invoke propagates failure result when repository returns error`() = runTest {
        val request = SnapshotRequest(imageBytes = byteArrayOf(1, 2, 3))
        val exception = RuntimeException("Network timeout")
        coEvery { snapshotRepository.analyzeSnapshot(request, testConfig) } returns Result.Error(exception, "Timeout")

        val result = useCase(request)

        assertTrue(result is Result.Error)
        assertEquals("Timeout", (result as Result.Error).message)
    }
}
