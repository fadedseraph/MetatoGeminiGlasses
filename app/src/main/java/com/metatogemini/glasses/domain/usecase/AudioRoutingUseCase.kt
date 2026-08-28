package com.metatogemini.glasses.domain.usecase

import com.metatogemini.glasses.domain.model.AudioRoute
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioManager
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * UseCase managing Bluetooth smart glasses and headset audio routing.
 */
class AudioRoutingUseCase(
    private val bluetoothAudioManager: BluetoothAudioManager
) {

    val bluetoothState: StateFlow<BluetoothAudioState>
        get() = bluetoothAudioManager.bluetoothState

    val audioRoute: Flow<AudioRoute> = bluetoothAudioManager.bluetoothState.map { state ->
        when (state) {
            is BluetoothAudioState.Connected -> {
                val isGlasses = state.deviceName.contains("glasses", ignoreCase = true) ||
                    state.deviceName.contains("meta", ignoreCase = true) ||
                    state.deviceName.contains("ray-ban", ignoreCase = true) ||
                    state.isBleAudio

                if (isGlasses) {
                    AudioRoute.SmartGlasses(deviceName = state.deviceName)
                } else {
                    AudioRoute.BluetoothHeadset(deviceName = state.deviceName)
                }
            }
            is BluetoothAudioState.Connecting -> AudioRoute.Unknown
            is BluetoothAudioState.Disconnected -> AudioRoute.Speaker
            is BluetoothAudioState.Error -> AudioRoute.Speaker
        }
    }

    fun startMonitoring() {
        bluetoothAudioManager.startMonitoring()
    }

    fun stopMonitoring() {
        bluetoothAudioManager.stopMonitoring()
    }

    fun reconnect() {
        bluetoothAudioManager.reconnectAudioRoute()
    }
}
