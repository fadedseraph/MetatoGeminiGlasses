package com.metatogemini.glasses.domain.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Reconnecting(val attempt: Int = 1) : ConnectionState
    data class Failed(val throwable: Throwable? = null, val reason: String? = throwable?.message) : ConnectionState
}
