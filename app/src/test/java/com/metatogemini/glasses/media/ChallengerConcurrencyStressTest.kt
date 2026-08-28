package com.metatogemini.glasses.media

import android.content.Context
import android.media.AudioManager
import com.metatogemini.glasses.media.audio.AudioCaptureManagerImpl
import com.metatogemini.glasses.media.audio.AudioPlaybackManagerImpl
import com.metatogemini.glasses.media.audio.CaptureState
import com.metatogemini.glasses.media.audio.PlaybackState
import com.metatogemini.glasses.media.camera.FrameProcessorImpl
import com.metatogemini.glasses.media.util.AudioMath
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger 2 Concurrency Stress Suite.
 * Adversarially tests concurrency boundaries:
 * 1. Rapid stopAndClear() calls during playback & queue purge safety.
 * 2. Audio capture pause/resume state transitions under concurrent racing.
 * 3. Frame processing throttling under burst frame inputs & multi-threaded access.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChallengerConcurrencyStressTest {

    private lateinit var mockContext: Context
    private lateinit var mockAudioManager: AudioManager

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockAudioManager = mockk(relaxed = true)
        every { mockContext.getSystemService(Context.AUDIO_SERVICE) } returns mockAudioManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========================================================================
    // 1. Playback Concurrency & Rapid stopAndClear Stress Tests
    // ========================================================================

    @Test
    fun `playback_stress_rapid_stopAndClear_during_active_audio_stream_does_not_deadlock`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = backgroundScope,
            ioDispatcher = testDispatcher
        )

        val chunk = AudioMath.generateSineWavePcm(440.0, 24000, 20)

        // Interleave 100 rapid enqueues and 50 stopAndClear calls
        for (i in 1..50) {
            manager.enqueueAudio(chunk)
            manager.enqueueAudio(chunk)
            manager.stopAndClear()
        }

        advanceUntilIdle()
        assertEquals(PlaybackState.IDLE, manager.playbackState.value)
        assertEquals(0f, manager.playbackRmsState.value.rms, 0.001f)
        manager.release()
    }

    @Test
    fun `playback_stress_concurrent_multi_threaded_enqueue_and_stopAndClear`() {
        val pool = Executors.newFixedThreadPool(8)
        val dispatcher = pool.asCoroutineDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)

        val manager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = scope,
            ioDispatcher = dispatcher
        )

        val chunk = AudioMath.generateSineWavePcm(440.0, 24000, 10)
        val threadCount = 6
        val operationsPerThread = 100
        val latch = CountDownLatch(threadCount + 2)
        val errorCount = AtomicInteger(0)

        // 6 Producer threads enqueuing audio
        for (t in 0 until threadCount) {
            pool.submit {
                try {
                    for (i in 0 until operationsPerThread) {
                        manager.enqueueAudio(chunk)
                        Thread.sleep(1)
                    }
                } catch (e: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // 2 Interrupter threads rapidly firing stopAndClear
        for (t in 0 until 2) {
            pool.submit {
                try {
                    for (i in 0 until 40) {
                        manager.stopAndClear()
                        Thread.sleep(3)
                    }
                } catch (e: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertTrue("Multi-threaded playback stress must complete within 10s without deadlock", completed)
        assertEquals("No exceptions should occur during concurrent enqueue and stopAndClear", 0, errorCount.get())

        manager.stopAndClear()
        Thread.sleep(50)
        assertEquals(PlaybackState.IDLE, manager.playbackState.value)
        manager.release()
        pool.shutdownNow()
    }

    @Test
    fun `playback_stress_consecutive_stopAndClear_without_enqueue_is_safe`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val manager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = testScope,
            ioDispatcher = testDispatcher
        )

        // 20 immediate consecutive stopAndClear calls
        for (i in 0 until 20) {
            manager.stopAndClear()
        }

        assertEquals(PlaybackState.IDLE, manager.playbackState.value)
        manager.release()
    }

    // ========================================================================
    // 2. Audio Capture State Machine & Concurrency Stress Tests
    // ========================================================================

    @Test
    fun `capture_stress_rapid_pause_and_resume_cycles_maintain_consistent_state`() {
        val manager = AudioCaptureManagerImpl(ioDispatcher = Dispatchers.Unconfined)

        val stateField = AudioCaptureManagerImpl::class.java.getDeclaredField("_captureState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(manager) as MutableStateFlow<CaptureState>
        stateFlow.value = CaptureState.RECORDING

        // Perform 50 rapid pause / resume transitions
        for (i in 1..50) {
            manager.pauseCapture()
            assertEquals(CaptureState.PAUSED, manager.captureState.value)

            manager.resumeCapture()
            assertEquals(CaptureState.RECORDING, manager.captureState.value)
        }

        manager.stopCapture()
        assertEquals(CaptureState.IDLE, manager.captureState.value)
    }

    @Test
    fun `capture_stress_concurrent_pause_resume_stop_calls_across_threads`() {
        val manager = AudioCaptureManagerImpl(ioDispatcher = Dispatchers.Unconfined)

        val stateField = AudioCaptureManagerImpl::class.java.getDeclaredField("_captureState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(manager) as MutableStateFlow<CaptureState>
        stateFlow.value = CaptureState.RECORDING

        val threadCount = 4
        val iterations = 100
        val latch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)

        // Thread 0: pause
        pool.submit {
            try {
                for (i in 0 until iterations) {
                    manager.pauseCapture()
                }
            } finally {
                latch.countDown()
            }
        }

        // Thread 1: resume
        pool.submit {
            try {
                for (i in 0 until iterations) {
                    manager.resumeCapture()
                }
            } finally {
                latch.countDown()
            }
        }

        // Thread 2: redundant pause/resume
        pool.submit {
            try {
                for (i in 0 until iterations) {
                    if (i % 2 == 0) manager.pauseCapture() else manager.resumeCapture()
                }
            } finally {
                latch.countDown()
            }
        }

        // Thread 3: monitor state valid
        pool.submit {
            try {
                for (i in 0 until iterations) {
                    val s = manager.captureState.value
                    assertTrue(s == CaptureState.RECORDING || s == CaptureState.PAUSED || s == CaptureState.IDLE)
                }
            } finally {
                latch.countDown()
            }
        }

        val finished = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Capture pause/resume stress completed in time", finished)

        manager.stopCapture()
        assertEquals(CaptureState.IDLE, manager.captureState.value)
        pool.shutdownNow()
    }

    @Test
    fun `capture_stress_multiple_stopCapture_calls_are_idempotent`() {
        val manager = AudioCaptureManagerImpl(ioDispatcher = Dispatchers.Unconfined)

        val stateField = AudioCaptureManagerImpl::class.java.getDeclaredField("_captureState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(manager) as MutableStateFlow<CaptureState>
        stateFlow.value = CaptureState.RECORDING

        // Call stopCapture 10 times in succession
        for (i in 0 until 10) {
            manager.stopCapture()
            assertEquals(CaptureState.IDLE, manager.captureState.value)
        }
    }

    @Test
    fun `capture_stress_pause_and_resume_when_idle_is_noop`() {
        val manager = AudioCaptureManagerImpl(ioDispatcher = Dispatchers.Unconfined)
        assertEquals(CaptureState.IDLE, manager.captureState.value)

        manager.pauseCapture()
        assertEquals(CaptureState.IDLE, manager.captureState.value)

        manager.resumeCapture()
        assertEquals(CaptureState.IDLE, manager.captureState.value)
    }

    // ========================================================================
    // 3. Frame Processor Burst & Throttling Stress Tests
    // ========================================================================

    @Test
    fun `frame_stress_burst_10000_frames_at_1_fps_permits_exact_cadence`() {
        val processor = FrameProcessorImpl()
        var acceptedFrames = 0
        val totalDurationMs = 10_000L // 10 seconds of simulated stream
        val stepMs = 1L               // 1ms steps (1000 fps burst input)

        // Real-world elapsedRealtime starts at positive timestamp offset
        val startOffset = 100_000L
        for (t in startOffset..(startOffset + totalDurationMs) step stepMs) {
            if (processor.shouldProcessFrame(t, targetFps = 1)) {
                acceptedFrames++
            }
        }

        // At 1 FPS over 10,000ms:
        // t=startOffset (accepted), startOffset+1000, ... startOffset+10000 = exactly 11 frames
        assertEquals(11, acceptedFrames)
    }

    @Test
    fun `frame_stress_burst_10000_frames_at_2_fps_permits_exact_cadence`() {
        val processor = FrameProcessorImpl()
        var acceptedFrames = 0
        val totalDurationMs = 10_000L // 10 seconds
        val stepMs = 1L               // 1ms steps

        val startOffset = 100_000L
        for (t in startOffset..(startOffset + totalDurationMs) step stepMs) {
            if (processor.shouldProcessFrame(t, targetFps = 2)) {
                acceptedFrames++
            }
        }

        // At 2 FPS (500ms interval) over 10,000ms:
        // t=startOffset, startOffset+500, startOffset+1000, ... startOffset+10000 = exactly 21 frames
        assertEquals(21, acceptedFrames)
    }

    @Test
    fun `frame_stress_concurrent_multi_threaded_timestamp_evaluation_permits_single_winner`() {
        val processor = FrameProcessorImpl()
        val threadCount = 16
        val latch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        val passedCount = AtomicInteger(0)
        val timestamp = 5000L

        // All 16 threads try to claim the frame at timestamp 5000L at the same instant
        for (i in 0 until threadCount) {
            pool.submit {
                try {
                    if (processor.shouldProcessFrame(timestamp, targetFps = 1)) {
                        passedCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val finished = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Threads must complete", finished)
        // Exactly ONE thread should win the throttle lock for that timestamp window
        assertEquals(1, passedCount.get())
        pool.shutdownNow()
    }

    @Test
    fun `frame_stress_negative_or_backwards_timestamps_do_not_corrupt_throttle`() {
        val processor = FrameProcessorImpl()

        // First normal frame
        assertTrue(processor.shouldProcessFrame(1000L, 1))

        // Time jumps backward (e.g. clock correction or out-of-order frame)
        assertFalse(processor.shouldProcessFrame(500L, 1))
        assertFalse(processor.shouldProcessFrame(0L, 1))
        assertFalse(processor.shouldProcessFrame(999L, 1))

        // Forward to next valid interval
        assertTrue(processor.shouldProcessFrame(2000L, 1))
    }

    @Test
    fun `frame_stress_resetThrottle_under_heavy_concurrent_traffic`() {
        val processor = FrameProcessorImpl()
        val passed = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(8)

        for (i in 0 until 8) {
            pool.submit {
                try {
                    for (t in 1..50) {
                        processor.resetThrottle()
                        if (processor.shouldProcessFrame(t * 100L, 1)) {
                            passed.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Reset throttle stress completed", completed)
        assertTrue("At least some frames passed due to resetThrottle", passed.get() > 0)
        pool.shutdownNow()
    }
}
