package com.metatogemini.glasses.core

import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.core.common.TestDispatchersProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchersProviderTest {

    @Test
    fun `default dispatchers provider returns valid standard dispatchers`() {
        val provider = DefaultDispatchersProvider()
        assertNotNull(provider.io)
        assertNotNull(provider.default)
        assertNotNull(provider.unconfined)
    }

    @Test
    fun `test dispatchers provider delegates all dispatchers to test dispatcher`() {
        val testDispatcher = UnconfinedTestDispatcher()
        val provider = TestDispatchersProvider(testDispatcher)

        assertEquals(testDispatcher, provider.main)
        assertEquals(testDispatcher, provider.io)
        assertEquals(testDispatcher, provider.default)
        assertEquals(testDispatcher, provider.unconfined)
    }
}
