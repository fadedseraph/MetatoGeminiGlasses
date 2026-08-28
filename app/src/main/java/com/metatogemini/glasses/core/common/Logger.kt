package com.metatogemini.glasses.core.common

import android.util.Log

interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

object AppLogger : Logger {
    var isJvmTestEnvironment: Boolean = false

    override fun d(tag: String, message: String) {
        if (isJvmTestEnvironment) {
            println("[DEBUG] [$tag] $message")
        } else {
            try {
                Log.d(tag, message)
            } catch (e: RuntimeException) {
                println("[DEBUG] [$tag] $message")
            }
        }
    }

    override fun i(tag: String, message: String) {
        if (isJvmTestEnvironment) {
            println("[INFO] [$tag] $message")
        } else {
            try {
                Log.i(tag, message)
            } catch (e: RuntimeException) {
                println("[INFO] [$tag] $message")
            }
        }
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (isJvmTestEnvironment) {
            println("[WARN] [$tag] $message ${throwable?.message ?: ""}")
            throwable?.printStackTrace()
        } else {
            try {
                Log.w(tag, message, throwable)
            } catch (e: RuntimeException) {
                println("[WARN] [$tag] $message ${throwable?.message ?: ""}")
            }
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (isJvmTestEnvironment) {
            System.err.println("[ERROR] [$tag] $message ${throwable?.message ?: ""}")
            throwable?.printStackTrace()
        } else {
            try {
                Log.e(tag, message, throwable)
            } catch (e: RuntimeException) {
                System.err.println("[ERROR] [$tag] $message ${throwable?.message ?: ""}")
            }
        }
    }
}
