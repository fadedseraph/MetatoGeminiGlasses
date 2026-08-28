package com.metatogemini.glasses.di

import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.core.common.DispatchersProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val appModule = module {
    single<DispatchersProvider> { DefaultDispatchersProvider() }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
}
