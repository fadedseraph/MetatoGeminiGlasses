package com.metatogemini.glasses.domain

import com.metatogemini.glasses.domain.usecase.ProcessInterruptionUseCase
import com.metatogemini.glasses.media.audio.AudioPlaybackManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class ProcessInterruptionUseCaseTest {

    private val audioPlaybackManager: AudioPlaybackManager = mockk(relaxed = true)
    private lateinit var useCase: ProcessInterruptionUseCase

    @Before
    fun setup() {
        useCase = ProcessInterruptionUseCase(audioPlaybackManager = audioPlaybackManager)
    }

    @Test
    fun `invoke calls stopAndClear on audio playback manager for barge-in`() {
        useCase()

        verify(exactly = 1) { audioPlaybackManager.stopAndClear() }
    }

    @Test
    fun `multiple interruption invocations trigger stopAndClear each time`() {
        useCase()
        useCase()
        useCase()

        verify(exactly = 3) { audioPlaybackManager.stopAndClear() }
    }
}
