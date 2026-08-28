package com.metatogemini.glasses.media

import android.content.Context
import android.media.AudioManager
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioManagerImpl
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioState
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothAudioManagerTest {

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
    fun `initial bluetooth state is Disconnected`() {
        val manager = BluetoothAudioManagerImpl(
            context = mockContext,
            scope = testScope
        )

        assertEquals(BluetoothAudioState.Disconnected, manager.bluetoothState.value)
    }

    @Test
    fun `startMonitoring registers device callback and broadcast receiver`() = runTest(testDispatcher) {
        val manager = BluetoothAudioManagerImpl(
            context = mockContext,
            scope = this
        )

        manager.startMonitoring()
        advanceUntilIdle()

        verify { mockAudioManager.registerAudioDeviceCallback(any(), any()) }
        verify { mockContext.registerReceiver(any(), any()) }
    }

    @Test
    fun `stopMonitoring unregisters callbacks and resets state to Disconnected`() = runTest(testDispatcher) {
        val manager = BluetoothAudioManagerImpl(
            context = mockContext,
            scope = this
        )

        manager.startMonitoring()
        advanceUntilIdle()

        manager.stopMonitoring()
        advanceUntilIdle()

        verify { mockAudioManager.unregisterAudioDeviceCallback(any()) }
        verify { mockContext.unregisterReceiver(any()) }
        assertEquals(BluetoothAudioState.Disconnected, manager.bluetoothState.value)
    }

    @Test
    fun `multiple startMonitoring calls are idempotent`() = runTest(testDispatcher) {
        val manager = BluetoothAudioManagerImpl(
            context = mockContext,
            scope = this
        )

        manager.startMonitoring()
        manager.startMonitoring()
        advanceUntilIdle()

        // Should only register once
        verify(exactly = 1) { mockAudioManager.registerAudioDeviceCallback(any(), any()) }
    }

    @Test
    fun `reconnectAudioRoute executes evaluation without error`() = runTest(testDispatcher) {
        val manager = BluetoothAudioManagerImpl(
            context = mockContext,
            scope = this
        )

        manager.reconnectAudioRoute()
        advanceUntilIdle()

        // State remains Disconnected if no hardware devices present
        assertEquals(BluetoothAudioState.Disconnected, manager.bluetoothState.value)
    }

    @Test
    fun `BluetoothAudioState sealed class models contain valid properties`() {
        val connected = BluetoothAudioState.Connected(
            deviceName = "Ray-Ban Meta",
            deviceType = "BLE Audio (Glasses)",
            isScoActive = false,
            isBleAudio = true
        )
        assertEquals("Ray-Ban Meta", connected.deviceName)
        assertEquals("BLE Audio (Glasses)", connected.deviceType)
        assertFalse(connected.isScoActive)
        assertTrue(connected.isBleAudio)

        val err = BluetoothAudioState.Error("Device error", RuntimeException("BT fail"))
        assertEquals("Device error", err.message)
        assertEquals("BT fail", err.throwable?.message)
    }
}
