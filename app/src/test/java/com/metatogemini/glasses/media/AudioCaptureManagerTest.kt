package com.metatogemini.glasses.media

import android.media.AudioRecord
import com.metatogemini.glasses.media.audio.AudioCaptureManagerImpl
import com.metatogemini.glasses.media.audio.AudioRmsState
import com.metatogemini.glasses.media.audio.CaptureState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioCaptureManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        // Prepare test environment
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initial capture state is IDLE and rms is default`() {
        val manager = AudioCaptureManagerImpl(ioDispatcher = testDispatcher)
        assertEquals(CaptureState.IDLE, manager.captureState.value)
        assertEquals(0f, manager.rmsState.value.rms, 0.001f)
        assertEquals(0f, manager.rmsState.value.normalizedRms, 0.001f)
        assertEquals(-100f, manager.rmsState.value.db, 0.001f)
    }

    @Test
    fun `pauseCapture and resumeCapture toggle state correctly when recording`() {
        val manager = AudioCaptureManagerImpl(ioDispatcher = testDispatcher)

        // Force internal state to RECORDING via reflection for unit test state machine
        val stateField = AudioCaptureManagerImpl::class.java.getDeclaredField("_captureState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<CaptureState>
        stateFlow.value = CaptureState.RECORDING

        manager.pauseCapture()
        assertEquals(CaptureState.PAUSED, manager.captureState.value)

        manager.resumeCapture()
        assertEquals(CaptureState.RECORDING, manager.captureState.value)
    }

    @Test
    fun `pauseCapture does nothing if not in RECORDING state`() {
        val manager = AudioCaptureManagerImpl(ioDispatcher = testDispatcher)
        assertEquals(CaptureState.IDLE, manager.captureState.value)

        manager.pauseCapture()
        assertEquals(CaptureState.IDLE, manager.captureState.value)
    }

    @Test
    fun `stopCapture resets state to IDLE`() {
        val manager = AudioCaptureManagerImpl(ioDispatcher = testDispatcher)

        val stateField = AudioCaptureManagerImpl::class.java.getDeclaredField("_captureState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<CaptureState>
        stateFlow.value = CaptureState.RECORDING

        manager.stopCapture()
        assertEquals(CaptureState.IDLE, manager.captureState.value)
    }

    @Test
    fun `audio capture flow initialization error sets state to ERROR when AudioRecord fails`() = runTest(testDispatcher) {
        // Without hardware AudioRecord mock returning initialized state, constructor throws or fails
        val manager = AudioCaptureManagerImpl(ioDispatcher = testDispatcher)

        val chunks = mutableListOf<ByteArray>()
        val job = launch {
            manager.startCapture(512).collect { chunks.add(it) }
        }

        testScheduler.advanceUntilIdle()
        // In JVM test environment without mock, AudioRecord fails to initialize -> CaptureState.ERROR
        assertEquals(CaptureState.ERROR, manager.captureState.value)
        assertTrue(chunks.isEmpty())
        job.cancel()
    }
}
