package com.metatogemini.glasses.di

import com.metatogemini.glasses.mock.GeminiMockEngine
import com.metatogemini.glasses.mock.MicLoopbackManager
import com.metatogemini.glasses.mock.SyntheticToneGenerator
import org.koin.dsl.module

val mockModule = module {
    single { SyntheticToneGenerator() }

    single {
        GeminiMockEngine(
            syntheticToneGenerator = get(),
            dispatchersProvider = get()
        )
    }

    single {
        MicLoopbackManager(
            audioCaptureManager = get(),
            audioPlaybackManager = get(),
            dispatchersProvider = get()
        )
    }
}
