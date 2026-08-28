package com.metatogemini.glasses.domain

import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.repository.LiveSessionRepository
import com.metatogemini.glasses.domain.repository.SettingsRepository
import com.metatogemini.glasses.domain.usecase.StartLiveSessionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class StartLiveSessionUseCaseTest {

    private val liveSessionRepository: LiveSessionRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private lateinit var useCase: StartLiveSessionUseCase

    @Before
    fun setup() {
        useCase = StartLiveSessionUseCase(
            liveSessionRepository = liveSessionRepository,
            settingsRepository = settingsRepository
        )
    }

    @Test
    fun `invoke with default config reads from settings repository and starts session`() = runTest {
        val storedConfig = SessionConfig(
            apiKey = "test-key-stored",
            model = "gemini-2.0-flash-exp",
            voice = "Puck"
        )
        coEvery { settingsRepository.getSessionConfig() } returns storedConfig

        useCase()

        coVerify(exactly = 1) { settingsRepository.getSessionConfig() }
        coVerify(exactly = 1) { liveSessionRepository.startSession(storedConfig) }
    }

    @Test
    fun `invoke with custom config starts session with provided config without reading settings`() = runTest {
        val customConfig = SessionConfig(
            apiKey = "custom-key",
            model = "gemini-1.5-pro",
            voice = "Charon"
        )

        useCase(customConfig = customConfig)

        coVerify(exactly = 0) { settingsRepository.getSessionConfig() }
        coVerify(exactly = 1) { liveSessionRepository.startSession(customConfig) }
    }
}
