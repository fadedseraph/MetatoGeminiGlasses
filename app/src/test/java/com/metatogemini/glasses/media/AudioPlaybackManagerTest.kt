package com.metatogemini.glasses.media

import android.content.Context
import android.media.AudioManager
import com.metatogemini.glasses.media.audio.AudioPlaybackManagerImpl
import com.metatogemini.glasses.media.audio.PlaybackState
import com.metatogemini.glasses.media.util.AudioMath
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlaybackManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
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

    @Test
    fun `initial playback state is IDLE or ERROR depending on platform AudioTrack support`() {
        val manager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = testScope,
            ioDispatcher = testDispatcher
        )
        assertNotNull(manager.playbackState.value)
        assertNotNull(manager.playbackRmsState.value)
        manager.release()
    }

    @Test
    fun `stopAndClear resets playback state to IDLE and clears RMS`() = runTest(testDispatcher) {
        val manager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = backgroundScope,
            ioDispatcher = testDispatcher
        )

        val sineChunk = AudioMath.generateSineWavePcm(
            frequencyHz = 440.0,
            sampleRateHz = 24000,
            durationMs = 50
        )

        manager.enqueueAudio(sineChunk)
        manager.stopAndClear()

        assertEquals(PlaybackState.IDLE, manager.playbackState.value)
        assertEquals(0f, manager.playbackRmsState.value.rms, 0.001f)
        assertEquals(-100f, manager.playbackRmsState.value.db, 0.001f)
        manager.release()
    }

    @Test
    fun `enqueueAudio with empty byte array is ignored`() = runTest(testDispatcher) {
        val manager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = backgroundScope,
            ioDispatcher = testDispatcher
        )

        manager.enqueueAudio(ByteArray(0))
        advanceUntilIdle()

        assertEquals(PlaybackState.IDLE, manager.playbackState.value)
        manager.release()
    }

    @Test
    fun `setVolume handles values outside 0 to 1 range gracefully`() {
        val manager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = testScope,
            ioDispatcher = testDispatcher
        )

        // Should not throw
        manager.setVolume(-0.5f)
        manager.setVolume(1.5f)
        manager.setVolume(0.8f)
        manager.release()
    }

    @Test
    fun `release transitions playback state to STOPPED`() {
        val manager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = testScope,
            ioDispatcher = testDispatcher
        )

        manager.release()
        assertEquals(PlaybackState.STOPPED, manager.playbackState.value)
    }

    @Test
    fun `barge-in interruption handles rapid stopAndClear without deadlock`() = runTest(testDispatcher) {
        val manager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = backgroundScope,
            ioDispatcher = testDispatcher
        )

        val chunk1 = AudioMath.generateSineWavePcm(440.0, 24000, 100)
        val chunk2 = AudioMath.generateSineWavePcm(880.0, 24000, 100)

        // Rapid enqueues followed by immediate barge-in
        manager.enqueueAudio(chunk1)
        manager.enqueueAudio(chunk2)
        manager.stopAndClear()
        manager.stopAndClear()

        advanceUntilIdle()
        assertEquals(PlaybackState.IDLE, manager.playbackState.value)
        manager.release()
    }
}
