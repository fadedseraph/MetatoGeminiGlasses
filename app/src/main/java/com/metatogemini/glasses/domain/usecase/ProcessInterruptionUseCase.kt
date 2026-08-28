package com.metatogemini.glasses.domain.usecase

import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.media.audio.AudioPlaybackManager

/**
 * UseCase to handle low-latency barge-in / interruption events.
 * Immediately invokes [AudioPlaybackManager.stopAndClear] to flush the hardware buffer (<10ms).
 */
class ProcessInterruptionUseCase(
    private val audioPlaybackManager: AudioPlaybackManager
) {

    operator fun invoke() {
        AppLogger.i(TAG, "Processing barge-in interruption: clearing audio playback buffer")
        audioPlaybackManager.stopAndClear()
    }

    companion object {
        private const val TAG = "ProcessInterruptionUC"
    }
}
