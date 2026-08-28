package com.metatogemini.glasses.mock

import com.metatogemini.glasses.core.common.DispatchersProvider
import com.metatogemini.glasses.core.common.TestDispatchersProvider
import com.metatogemini.glasses.di.mockModule
import com.metatogemini.glasses.media.audio.AudioCaptureManager
import com.metatogemini.glasses.media.audio.AudioPlaybackManager
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class MockModuleTest : KoinTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testDispatchersProvider = TestDispatchersProvider(testDispatcher)
    private val audioCaptureManager: AudioCaptureManager = mockk(relaxed = true)
    private val audioPlaybackManager: AudioPlaybackManager = mockk(relaxed = true)

    private val supportModule = module {
        single<DispatchersProvider> { testDispatchersProvider }
        single<AudioCaptureManager> { audioCaptureManager }
        single<AudioPlaybackManager> { audioPlaybackManager }
    }

    @Before
    fun setup() {
        startKoin {
            modules(listOf(supportModule, mockModule))
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `mockModule provides SyntheticToneGenerator, GeminiMockEngine, and MicLoopbackManager`() {
        val toneGenerator: SyntheticToneGenerator = get()
        val mockEngine: GeminiMockEngine = get()
        val micLoopbackManager: MicLoopbackManager = get()

        assertNotNull(toneGenerator)
        assertNotNull(mockEngine)
        assertNotNull(micLoopbackManager)
    }
}
