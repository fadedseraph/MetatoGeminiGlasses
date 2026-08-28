package com.metatogemini.glasses.media.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.metatogemini.glasses.core.common.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State representing Bluetooth audio connectivity and routing status.
 */
sealed class BluetoothAudioState {
    data object Disconnected : BluetoothAudioState()
    data object Connecting : BluetoothAudioState()
    data class Connected(
        val deviceName: String,
        val deviceType: String,
        val isScoActive: Boolean,
        val isBleAudio: Boolean
    ) : BluetoothAudioState()
    data class Error(val message: String, val throwable: Throwable? = null) : BluetoothAudioState()
}

/**
 * Interface contract for managing Bluetooth Smart Glasses and headset audio routing.
 */
interface BluetoothAudioManager {
    val bluetoothState: StateFlow<BluetoothAudioState>

    /** Starts monitoring Bluetooth devices and auto-routes audio to connected glasses/headsets. */
    fun startMonitoring()

    /** Stops monitoring and resets audio routing to phone defaults. */
    fun stopMonitoring()

    /** Manually triggers audio route evaluation and reconnection. */
    fun reconnectAudioRoute()
}

/**
 * Production implementation of [BluetoothAudioManager] with Android 12+ (API 31+)
 * [AudioManager.setCommunicationDevice] and legacy Bluetooth SCO fallback.
 */
class BluetoothAudioManagerImpl(
    private val context: Context,
    private val scope: CoroutineScope
) : BluetoothAudioManager {

    companion object {
        private const val TAG = "BluetoothAudioManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _bluetoothState = MutableStateFlow<BluetoothAudioState>(BluetoothAudioState.Disconnected)
    override val bluetoothState: StateFlow<BluetoothAudioState> = _bluetoothState.asStateFlow()

    private var isMonitoring = false
    private var isReceiverRegistered = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            AppLogger.d(TAG, "Audio devices added: ${addedDevices?.size ?: 0}")
            evaluateAndRouteAudio()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            AppLogger.d(TAG, "Audio devices removed: ${removedDevices?.size ?: 0}")
            evaluateAndRouteAudio()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR
                    )
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            AppLogger.d(TAG, "SCO Audio State: CONNECTED")
                            evaluateAndRouteAudio(isScoActive = true)
                        }
                        AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                            AppLogger.d(TAG, "SCO Audio State: CONNECTING")
                            _bluetoothState.value = BluetoothAudioState.Connecting
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            AppLogger.d(TAG, "SCO Audio State: DISCONNECTED")
                            evaluateAndRouteAudio(isScoActive = false)
                        }
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    evaluateAndRouteAudio()
                }
            }
        }
    }

    override fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        val manager = audioManager ?: return
        manager.registerAudioDeviceCallback(audioDeviceCallback, null)

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            context.registerReceiver(bluetoothReceiver, filter)
            isReceiverRegistered = true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to register Bluetooth broadcast receiver", e)
        }

        evaluateAndRouteAudio()
    }

    override fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false

        val manager = audioManager
        try {
            manager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            if (isReceiverRegistered) {
                context.unregisterReceiver(bluetoothReceiver)
                isReceiverRegistered = false
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error unregistering audio receiver/callback", e)
        }

        resetAudioRouting()
        _bluetoothState.value = BluetoothAudioState.Disconnected
    }

    override fun reconnectAudioRoute() {
        evaluateAndRouteAudio()
    }

    @SuppressLint("MissingPermission")
    private fun evaluateAndRouteAudio(isScoActive: Boolean = false) {
        val manager = audioManager ?: return
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Modern API 31+ Routing
                    val devices = manager.availableCommunicationDevices
                    val targetDevice = devices.firstOrNull { device ->
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            device.type == AudioDeviceInfo.TYPE_HEARING_AID
                    }

                    if (targetDevice != null) {
                        manager.mode = AudioManager.MODE_IN_COMMUNICATION
                        val success = manager.setCommunicationDevice(targetDevice)
                        if (success) {
                            val isBle = targetDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                            val typeName = when (targetDevice.type) {
                                AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Audio (Glasses)"
                                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (Glasses)"
                                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                                AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
                                else -> "Bluetooth Headset"
                            }
                            _bluetoothState.value = BluetoothAudioState.Connected(
                                deviceName = targetDevice.productName?.toString() ?: "Smart Glasses",
                                deviceType = typeName,
                                isScoActive = !isBle,
                                isBleAudio = isBle
                            )
                        } else {
                            _bluetoothState.value = BluetoothAudioState.Error("Failed to set communication device")
                        }
                    } else {
                        resetAudioRouting()
                        _bluetoothState.value = BluetoothAudioState.Disconnected
                    }
                } else {
                    // Legacy API < 31 Fallback
                    @Suppress("DEPRECATION")
                    val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
                    val btDevice = devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    }

                    if (btDevice != null) {
                        manager.mode = AudioManager.MODE_IN_COMMUNICATION
                        @Suppress("DEPRECATION")
                        manager.startBluetoothSco()
                        @Suppress("DEPRECATION")
                        manager.isBluetoothScoOn = true
                        @Suppress("DEPRECATION")
                        manager.isSpeakerphoneOn = false

                        _bluetoothState.value = BluetoothAudioState.Connected(
                            deviceName = btDevice.productName?.toString() ?: "Bluetooth Glasses",
                            deviceType = "Bluetooth SCO",
                            isScoActive = isScoActive,
                            isBleAudio = false
                        )
                    } else {
                        resetAudioRouting()
                        _bluetoothState.value = BluetoothAudioState.Disconnected
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error evaluating audio routing", e)
                _bluetoothState.value = BluetoothAudioState.Error("Audio routing exception: ${e.message}", e)
            }
        }
    }

    private fun resetAudioRouting() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = false
            }
            manager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error resetting audio routing", e)
        }
    }
}
