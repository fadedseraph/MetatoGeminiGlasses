package com.metatogemini.glasses.core.common

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Base64 encoding compatible with JVM unit tests and Android runtime.
 */
fun ByteArray.toBase64(): String {
    return Base64.getEncoder().encodeToString(this)
}

/**
 * Base64 decoding compatible with JVM unit tests and Android runtime.
 */
fun String.decodeBase64(): ByteArray {
    return Base64.getDecoder().decode(this)
}

/**
 * Converts 16-bit PCM Byte array to Short array (Little-Endian).
 */
fun ByteArray.toShortArrayLe(): ShortArray {
    val shortCount = this.size / 2
    val shortArray = ShortArray(shortCount)
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray)
    return shortArray
}

/**
 * Converts Short array to 16-bit PCM Byte array (Little-Endian).
 */
fun ShortArray.toByteArrayLe(): ByteArray {
    val byteArray = ByteArray(this.size * 2)
    ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(this)
    return byteArray
}

/**
 * Calculates normalized RMS (0.0f .. 1.0f) for 16-bit PCM audio byte buffer.
 */
fun ByteArray.calculatePcm16Rms(): Float {
    if (this.isEmpty()) return 0.0f
    val shorts = this.toShortArrayLe()
    if (shorts.isEmpty()) return 0.0f
    var sumSquares = 0.0
    for (sample in shorts) {
        val normalized = sample.toDouble() / Short.MAX_VALUE
        sumSquares += normalized * normalized
    }
    val meanSquare = sumSquares / shorts.size
    val rms = Math.sqrt(meanSquare).toFloat()
    return rms.coerceIn(0.0f, 1.0f)
}
