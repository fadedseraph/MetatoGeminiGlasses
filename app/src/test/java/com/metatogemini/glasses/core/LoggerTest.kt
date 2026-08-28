package com.metatogemini.glasses.core

import com.metatogemini.glasses.core.common.AppLogger
import org.junit.Before
import org.junit.Test

class LoggerTest {

    @Before
    fun setup() {
        AppLogger.isJvmTestEnvironment = true
    }

    @Test
    fun `logger methods execute without exceptions in JVM environment`() {
        AppLogger.d("LoggerTest", "Debug message")
        AppLogger.i("LoggerTest", "Info message")
        AppLogger.w("LoggerTest", "Warning message", RuntimeException("Warn exception"))
        AppLogger.e("LoggerTest", "Error message", RuntimeException("Error exception"))
    }
}
