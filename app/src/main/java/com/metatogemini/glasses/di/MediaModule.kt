package com.metatogemini.glasses.di

import com.metatogemini.glasses.media.audio.AudioCaptureManager
import com.metatogemini.glasses.media.audio.AudioCaptureManagerImpl
import com.metatogemini.glasses.media.audio.AudioPlaybackManager
import com.metatogemini.glasses.media.audio.AudioPlaybackManagerImpl
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioManager
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioManagerImpl
import com.metatogemini.glasses.media.camera.CameraManager
import com.metatogemini.glasses.media.camera.CameraManagerImpl
import com.metatogemini.glasses.media.camera.FrameProcessor
import com.metatogemini.glasses.media.camera.FrameProcessorImpl
import com.metatogemini.glasses.media.camera.GlassesPhotoSyncManager
import com.metatogemini.glasses.media.camera.GlassesPhotoSyncManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val mediaModule = module {
    single<FrameProcessor> {
        FrameProcessorImpl()
    }

    single<AudioCaptureManager> {
        AudioCaptureManagerImpl(ioDispatcher = Dispatchers.IO)
    }

    single<AudioPlaybackManager> {
        AudioPlaybackManagerImpl(
            context = androidContext(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ioDispatcher = Dispatchers.IO
        )
    }

    single<BluetoothAudioManager> {
        BluetoothAudioManagerImpl(
            context = androidContext(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        )
    }

    single<CameraManager> {
        CameraManagerImpl(
            context = androidContext(),
            frameProcessor = get(),
            ioDispatcher = Dispatchers.IO
        )
    }

    single<GlassesPhotoSyncManager> {
        GlassesPhotoSyncManagerImpl(
            context = androidContext(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ioDispatcher = Dispatchers.IO
        )
    }
}
