package com.metatogemini.glasses.di

import com.metatogemini.glasses.domain.usecase.AudioRoutingUseCase
import com.metatogemini.glasses.domain.usecase.ProcessInterruptionUseCase
import com.metatogemini.glasses.domain.usecase.SendAudioChunkUseCase
import com.metatogemini.glasses.domain.usecase.SendSnapshotUseCase
import com.metatogemini.glasses.domain.usecase.SendVideoFrameUseCase
import com.metatogemini.glasses.domain.usecase.StartLiveSessionUseCase
import com.metatogemini.glasses.domain.usecase.StopLiveSessionUseCase
import com.metatogemini.glasses.domain.usecase.UpdateSessionConfigUseCase
import org.koin.dsl.module

val domainModule = module {
    factory { StartLiveSessionUseCase(liveSessionRepository = get(), settingsRepository = get()) }
    factory { StopLiveSessionUseCase(liveSessionRepository = get(), audioPlaybackManager = get()) }
    factory { SendAudioChunkUseCase(liveSessionRepository = get()) }
    factory { SendVideoFrameUseCase(liveSessionRepository = get()) }
    factory { SendSnapshotUseCase(snapshotRepository = get(), settingsRepository = get()) }
    factory { ProcessInterruptionUseCase(audioPlaybackManager = get()) }
    factory { AudioRoutingUseCase(bluetoothAudioManager = get()) }
    factory { UpdateSessionConfigUseCase(settingsRepository = get()) }
}
