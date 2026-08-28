package com.metatogemini.glasses.domain

import app.cash.turbine.test
import com.metatogemini.glasses.domain.model.AudioRoute
import com.metatogemini.glasses.domain.usecase.AudioRoutingUseCase
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioManager
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioRoutingUseCaseTest {

    private val bluetoothAudioManager: BluetoothAudioManager = mockk(relaxed = true)
    private val bluetoothStateFlow = MutableStateFlow<BluetoothAudioState>(BluetoothAudioState.Disconnected)
    private lateinit var useCase: AudioRoutingUseCase

    @Before
    fun setup() {
        every { bluetoothAudioManager.bluetoothState } returns bluetoothStateFlow
        useCase = AudioRoutingUseCase(bluetoothAudioManager = bluetoothAudioManager)
    }

    @Test
    fun `startMonitoring calls bluetoothAudioManager startMonitoring`() {
        useCase.startMonitoring()
        verify(exactly = 1) { bluetoothAudioManager.startMonitoring() }
    }

    @Test
    fun `stopMonitoring calls bluetoothAudioManager stopMonitoring`() {
        useCase.stopMonitoring()
        verify(exactly = 1) { bluetoothAudioManager.stopMonitoring() }
    }

    @Test
    fun `reconnect calls bluetoothAudioManager reconnectAudioRoute`() {
        useCase.reconnect()
        verify(exactly = 1) { bluetoothAudioManager.reconnectAudioRoute() }
    }

    @Test
    fun `audioRoute flow maps connected smart glasses correctly`() = runTest {
        useCase.audioRoute.test {
            assertEquals(AudioRoute.Speaker, awaitItem())

            bluetoothStateFlow.value = BluetoothAudioState.Connected(
                deviceName = "Ray-Ban Meta Smart Glasses",
                deviceType = "BLE Audio (Glasses)",
                isScoActive = false,
                isBleAudio = true
            )
            val glassesRoute = awaitItem()
            assertTrue(glassesRoute is AudioRoute.SmartGlasses)
            assertEquals("Ray-Ban Meta Smart Glasses", (glassesRoute as AudioRoute.SmartGlasses).deviceName)

            bluetoothStateFlow.value = BluetoothAudioState.Connected(
                deviceName = "Sony WH-1000XM4",
                deviceType = "Bluetooth A2DP",
                isScoActive = false,
                isBleAudio = false
            )
            val headsetRoute = awaitItem()
            assertTrue(headsetRoute is AudioRoute.BluetoothHeadset)
            assertEquals("Sony WH-1000XM4", (headsetRoute as AudioRoute.BluetoothHeadset).deviceName)

            bluetoothStateFlow.value = BluetoothAudioState.Connecting
            assertEquals(AudioRoute.Unknown, awaitItem())

            bluetoothStateFlow.value = BluetoothAudioState.Disconnected
            assertEquals(AudioRoute.Speaker, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
