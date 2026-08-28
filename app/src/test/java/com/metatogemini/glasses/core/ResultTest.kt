package com.metatogemini.glasses.core

import com.metatogemini.glasses.core.common.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ResultTest {

    @Test
    fun `success result holds data and flags are correct`() {
        val result: Result<String> = Result.Success("Hello Gemini")

        assertTrue(result.isSuccess)
        assertFalse(result.isError)
        assertFalse(result.isLoading)
        assertEquals("Hello Gemini", result.getOrNull())
        assertNull(result.exceptionOrNull())
    }

    @Test
    fun `error result holds exception and flags are correct`() {
        val exception = IOException("Network socket disconnected")
        val result: Result<String> = Result.Error(exception, "Custom error message")

        assertFalse(result.isSuccess)
        assertTrue(result.isError)
        assertFalse(result.isLoading)
        assertNull(result.getOrNull())
        assertEquals(exception, result.exceptionOrNull())
        assertEquals("Custom error message", (result as Result.Error).message)
    }

    @Test
    fun `error result default message uses exception message`() {
        val exception = RuntimeException("Default ex message")
        val result: Result<String> = Result.Error(exception)

        assertTrue(result.isError)
        val error = result as Result.Error
        assertEquals(exception, error.exception)
        assertEquals("Default ex message", error.message)
    }

    @Test
    fun `loading result flags are correct`() {
        val result: Result<String> = Result.Loading

        assertFalse(result.isSuccess)
        assertFalse(result.isError)
        assertTrue(result.isLoading)
        assertNull(result.getOrNull())
        assertNull(result.exceptionOrNull())
    }

    @Test
    fun `map transforms success value correctly`() {
        val result = Result.Success(42).map { it * 2 }
        assertEquals(Result.Success(84), result)
    }

    @Test
    fun `map preserves error without executing transform`() {
        val ex = IllegalStateException("Failed")
        val initial: Result<Int> = Result.Error(ex)
        val result: Result<Int> = initial.map { it * 2 }
        assertTrue(result is Result.Error)
        assertEquals(ex, (result as Result.Error).exception)
    }

    @Test
    fun `map preserves loading without executing transform`() {
        val initial: Result<Int> = Result.Loading
        val result: Result<String> = initial.map { it.toString() }
        assertTrue(result.isLoading)
        assertTrue(result is Result.Loading)
    }

    @Test
    fun `flatMap chains successful results`() {
        val result = Result.Success("24000")
            .flatMap { str -> com.metatogemini.glasses.core.common.Result.runCatching { str.toInt() } }

        assertTrue(result.isSuccess)
        assertEquals(24000, result.getOrNull())
    }

    @Test
    fun `flatMap propagates error from first operation`() {
        val ex = RuntimeException("Initial failure")
        val result: Result<Int> = Result.Error(ex)
            .flatMap { Result.Success(100) }

        assertTrue(result is Result.Error)
        assertEquals(ex, (result as Result.Error).exception)
    }

    @Test
    fun `flatMap preserves loading without executing transform`() {
        val initial: Result<Int> = Result.Loading
        val result: Result<String> = initial.flatMap { Result.Success(it.toString()) }
        assertTrue(result.isLoading)
        assertTrue(result is Result.Loading)
    }

    @Test
    fun `fold executes correct lambda branch`() {
        val successResult = Result.Success("data")
        val foldSuccess = successResult.fold(
            onSuccess = { "Success: $it" },
            onError = { _, msg -> "Error: $msg" },
            onLoading = { "Loading" }
        )
        assertEquals("Success: data", foldSuccess)

        val errorResult = Result.Error(Exception("Boom"), "Boom")
        val foldError = errorResult.fold(
            onSuccess = { "Success: $it" },
            onError = { _, msg -> "Error: $msg" },
            onLoading = { "Loading" }
        )
        assertEquals("Error: Boom", foldError)

        val loadingResult = Result.Loading
        val foldLoading = loadingResult.fold(
            onSuccess = { "Success: $it" },
            onError = { _, msg -> "Error: $msg" },
            onLoading = { "Loading" }
        )
        assertEquals("Loading", foldLoading)
    }

    @Test
    fun `onSuccess callback runs only on success`() {
        var called = false
        Result.Success("test").onSuccess {
            called = true
            assertEquals("test", it)
        }
        assertTrue(called)

        var errorCallbackRan = false
        Result.Error(Exception()).onSuccess {
            errorCallbackRan = true
        }
        assertFalse(errorCallbackRan)
    }

    @Test
    fun `onError callback runs only on error`() {
        var called = false
        val exception = RuntimeException("test error")
        Result.Error(exception, "test error").onError { ex, msg ->
            called = true
            assertEquals(exception, ex)
            assertEquals("test error", msg)
        }
        assertTrue(called)

        var successCallbackRan = false
        Result.Success("test").onError { _, _ ->
            successCallbackRan = true
        }
        assertFalse(successCallbackRan)
    }

    @Test
    fun `onLoading callback runs only on loading`() {
        var called = false
        Result.Loading.onLoading {
            called = true
        }
        assertTrue(called)

        var errorCallbackRan = false
        Result.Error(Exception()).onLoading {
            errorCallbackRan = true
        }
        assertFalse(errorCallbackRan)
    }

    @Test
    fun `runCatching captures thrown exceptions as Result Error`() {
        val result = com.metatogemini.glasses.core.common.Result.runCatching {
            throw IllegalArgumentException("Invalid argument")
        }

        assertTrue(result.isError)
        assertTrue((result as Result.Error).exception is IllegalArgumentException)
        assertEquals("Invalid argument", (result as Result.Error).message)
    }

    @Test
    fun `runCatching returns Result Success when block succeeds`() {
        val result = com.metatogemini.glasses.core.common.Result.runCatching {
            42 * 2
        }

        assertTrue(result.isSuccess)
        assertEquals(84, result.getOrNull())
    }
}
