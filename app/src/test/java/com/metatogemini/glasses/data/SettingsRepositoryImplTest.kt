package com.metatogemini.glasses.data

import com.metatogemini.glasses.data.local.DataStoreManager
import com.metatogemini.glasses.data.repository.SettingsRepositoryImpl
import com.metatogemini.glasses.domain.model.SessionConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SettingsRepositoryImplTest {

    private val dataStoreManager: DataStoreManager = mockk(relaxed = true)
    private lateinit var settingsRepository: SettingsRepositoryImpl

    @Before
    fun setup() {
        settingsRepository = SettingsRepositoryImpl(dataStoreManager)
    }

    @Test
    fun `getSessionConfig delegates to DataStoreManager`() = runTest {
        val expected = SessionConfig(apiKey = "key-123", model = "gemini-2.0-flash-exp")
        coEvery { dataStoreManager.getSessionConfig() } returns expected

        val result = settingsRepository.getSessionConfig()
        assertEquals(expected, result)
        coVerify(exactly = 1) { dataStoreManager.getSessionConfig() }
    }

    @Test
    fun `update methods delegate to DataStoreManager`() = runTest {
        settingsRepository.updateApiKey("new-key")
        coVerify { dataStoreManager.updateApiKey("new-key") }

        settingsRepository.updateModel("gemini-1.5-pro")
        coVerify { dataStoreManager.updateModel("gemini-1.5-pro") }

        settingsRepository.updateVoice("Aoede")
        coVerify { dataStoreManager.updateVoice("Aoede") }

        settingsRepository.updateSystemInstruction("Be concise")
        coVerify { dataStoreManager.updateSystemInstruction("Be concise") }

        settingsRepository.updateMockMode(true)
        coVerify { dataStoreManager.updateMockMode(true) }

        settingsRepository.updateLoopbackMode(true)
        coVerify { dataStoreManager.updateLoopbackMode(true) }

        settingsRepository.updateCameraFps(2)
        coVerify { dataStoreManager.updateCameraFps(2) }

        val config = SessionConfig(apiKey = "bulk-key")
        settingsRepository.updateConfig(config)
        coVerify { dataStoreManager.updateSessionConfig(config) }
    }

    @Test
    fun `sessionConfig flow exposes dataStoreManager flow`() = runTest {
        val expected = SessionConfig(apiKey = "flow-key")
        every { dataStoreManager.sessionConfigFlow } returns flowOf(expected)

        settingsRepository.sessionConfig.collect {
            assertEquals("flow-key", it.apiKey)
        }
    }
}
