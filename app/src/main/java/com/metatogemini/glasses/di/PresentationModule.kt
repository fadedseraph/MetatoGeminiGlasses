package com.metatogemini.glasses.di

import com.metatogemini.glasses.presentation.viewmodel.LiveHudViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val presentationModule = module {
    viewModel {
        LiveHudViewModel(
            startLiveSessionUseCase = get(),
            stopLiveSessionUseCase = get(),
            sendAudioChunkUseCase = get(),
            sendVideoFrameUseCase = get(),
            sendSnapshotUseCase = get(),
            processInterruptionUseCase = get(),
            audioRoutingUseCase = get(),
            updateSessionConfigUseCase = get(),
            liveSessionRepository = get(),
            settingsRepository = get(),
            audioCaptureManager = get(),
            audioPlaybackManager = get(),
            cameraManager = get(),
            geminiMockEngine = get(),
            micLoopbackManager = get(),
            glassesPhotoSyncManager = get(),
            dispatchersProvider = get()
        )
    }
}
