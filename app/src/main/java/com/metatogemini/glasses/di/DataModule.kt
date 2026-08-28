package com.metatogemini.glasses.di

import com.metatogemini.glasses.data.local.DataStoreManager
import com.metatogemini.glasses.data.repository.LiveSessionRepositoryImpl
import com.metatogemini.glasses.data.repository.SettingsRepositoryImpl
import com.metatogemini.glasses.data.repository.SnapshotRepositoryImpl
import com.metatogemini.glasses.domain.repository.LiveSessionRepository
import com.metatogemini.glasses.domain.repository.SettingsRepository
import com.metatogemini.glasses.domain.repository.SnapshotRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single { DataStoreManager(androidContext()) }
    single<SettingsRepository> { SettingsRepositoryImpl(dataStoreManager = get()) }
    single<SnapshotRepository> { SnapshotRepositoryImpl(restClient = get(), dispatchersProvider = get()) }
    single<LiveSessionRepository> { LiveSessionRepositoryImpl(liveWebSocket = get(), dispatchersProvider = get()) }
}
