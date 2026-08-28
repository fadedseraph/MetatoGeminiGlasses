package com.metatogemini.glasses.domain.model

sealed interface AudioRoute {
    val displayName: String

    data object Speaker : AudioRoute {
        override val displayName: String = "Built-in Speaker"
    }

    data object Earpiece : AudioRoute {
        override val displayName: String = "Earpiece"
    }

    data class BluetoothHeadset(
        val deviceName: String = "Bluetooth Headset"
    ) : AudioRoute {
        override val displayName: String = deviceName
    }

    data class SmartGlasses(
        val deviceName: String = "Ray-Ban Meta Glasses"
    ) : AudioRoute {
        override val displayName: String = deviceName
    }

    data object Unknown : AudioRoute {
        override val displayName: String = "Unknown Audio Route"
    }
}
