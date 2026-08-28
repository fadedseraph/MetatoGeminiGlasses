package com.metatogemini.glasses

import android.app.Application
import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.di.appModule
import com.metatogemini.glasses.di.dataModule
import com.metatogemini.glasses.di.domainModule
import com.metatogemini.glasses.di.mediaModule
import com.metatogemini.glasses.di.mockModule
import com.metatogemini.glasses.di.networkModule
import com.metatogemini.glasses.di.presentationModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        initKoin()
        AppLogger.i(TAG, "MetatoGeminiGlasses Application initialized successfully")
    }

    private fun initKoin() {
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@App)
            modules(
                listOf(
                    appModule,
                    networkModule,
                    mediaModule,
                    dataModule,
                    domainModule,
                    presentationModule,
                    mockModule
                )
            )
        }
    }

    companion object {
        private const val TAG = "App"
    }
}
