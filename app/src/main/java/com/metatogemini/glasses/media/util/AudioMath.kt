package com.metatogemini.glasses.media.util

import com.metatogemini.glasses.media.audio.AudioRmsState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio math utility for 16-bit linear PCM audio computations.
 * Provides high-efficiency Root Mean Square (RMS), dBFS, and synthetic tone generation.
 */
object AudioMath {

    const val MAX_16BIT_AMPLITUDE = 32767.0f
    const val MIN_DBFS = -100.0f
    const val DBFS_DYNAMIC_RANGE = 60.0f // Scale from -60 dBFS to 0 dBFS for normalized UI meters

    /**
     * Computes [AudioRmsState] from a 16-bit Little-Endian PCM byte array.
     */
    fun calculateRms(pcmBytes: ByteArray): AudioRmsState {
        if (pcmBytes.isEmpty()) return AudioRmsState()
        val shortCount = pcmBytes.size / 2
        if (shortCount == 0) return AudioRmsState()

        var sumSquares = 0.0
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until shortCount) {
            val sample = shortBuffer.get(i).toDouble()
            sumSquares += sample * sample
        }

        return computeRmsFromMeanSquare(sumSquares / shortCount)
    }

    /**
     * Computes [AudioRmsState] from a 16-bit PCM Short array.
     */
    fun calculateRms(pcmShorts: ShortArray): AudioRmsState {
        if (pcmShorts.isEmpty()) return AudioRmsState()

        var sumSquares = 0.0
        for (sample in pcmShorts) {
            val s = sample.toDouble()
            sumSquares += s * s
        }

        return computeRmsFromMeanSquare(sumSquares / pcmShorts.size)
    }

    private fun computeRmsFromMeanSquare(meanSquare: Double): AudioRmsState {
        val rawRms = sqrt(meanSquare).toFloat()
        val normalizedRms = (rawRms / MAX_16BIT_AMPLITUDE).coerceIn(0.0f, 1.0f)

        val safeRatio = max((rawRms / MAX_16BIT_AMPLITUDE).toDouble(), 1e-5)
        val db = (20.0 * log10(safeRatio)).toFloat().coerceIn(MIN_DBFS, 0.0f)
        val normalizedDb = ((db + DBFS_DYNAMIC_RANGE) / DBFS_DYNAMIC_RANGE).coerceIn(0.0f, 1.0f)

        return AudioRmsState(
            rms = rawRms,
            normalizedRms = normalizedRms,
            db = db,
            normalizedDb = normalizedDb
        )
    }

    /**
     * Converts a Short array to Little-Endian 16-bit PCM ByteArray.
     */
    fun shortArrayToByteArrayLe(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
        return bytes
    }

    /**
     * Converts a Little-Endian 16-bit PCM ByteArray to Short array.
     */
    fun byteArrayToShortArrayLe(bytes: ByteArray): ShortArray {
        val count = bytes.size / 2
        val shorts = ShortArray(count)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    /**
     * Synthesizes a 16-bit Little-Endian Mono PCM sine wave.
     * Useful for hardware testing and audio unit verification.
     *
     * @param frequencyHz Tone frequency in Hz (e.g. 440.0 for A4)
     * @param sampleRateHz Sample rate in Hz (e.g. 16000 or 24000)
     * @param durationMs Duration of generated audio in milliseconds
     * @param amplitude Peak amplitude scaling factor [0.0 .. 1.0]
     */
    fun generateSineWavePcm(
        frequencyHz: Double,
        sampleRateHz: Int,
        durationMs: Int,
        amplitude: Double = 1.0
    ): ByteArray {
        val totalSamples = (sampleRateHz * durationMs / 1000.0).toInt()
        val shorts = ShortArray(totalSamples)
        val clampedAmp = amplitude.coerceIn(0.0, 1.0) * MAX_16BIT_AMPLITUDE

        for (i in 0 until totalSamples) {
            val angle = 2.0 * PI * frequencyHz * i / sampleRateHz
            shorts[i] = (sin(angle) * clampedAmp).toInt().toShort()
        }

        return shortArrayToByteArrayLe(shorts)
    }
}
