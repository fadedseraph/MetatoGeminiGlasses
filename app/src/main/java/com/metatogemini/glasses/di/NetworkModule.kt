package com.metatogemini.glasses.di

import com.metatogemini.glasses.data.network.rest.GeminiRestClient
import com.metatogemini.glasses.data.network.websocket.GeminiLiveWebSocket
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val networkModule = module {
    single {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite for WebSocket streaming
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            prettyPrint = false
        }
    }

    single { GeminiLiveWebSocket(okHttpClient = get(), json = get(), dispatchersProvider = get()) }
    single { GeminiRestClient(okHttpClient = get(), json = get(), dispatchersProvider = get()) }
}
