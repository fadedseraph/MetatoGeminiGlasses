package com.metatogemini.glasses.mock

import com.metatogemini.glasses.core.common.Constants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * High-performance mathematical tone generator for offline audio simulation and testing.
 * Generates 16-bit Mono Little-Endian linear PCM sine tones with raised-cosine
 * (Hann-style) envelope windowing at attack and decay boundaries to prevent acoustic clicks/pops.
 */
class SyntheticToneGenerator {

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ: Int = Constants.SAMPLE_RATE_PLAYBACK_HZ // 24000 Hz
        const val DEFAULT_FREQUENCY_HZ: Double = 440.0 // Standard pitch A4
        const val DEFAULT_AMPLITUDE: Double = 0.5
        const val DEFAULT_ATTACK_MS: Int = 20
        const val DEFAULT_DECAY_MS: Int = 20
        const val BYTES_PER_SAMPLE: Int = Constants.BYTES_PER_SAMPLE_PCM_16BIT // 2 bytes
        const val MAX_16BIT_VALUE: Double = 32767.0
    }

    /**
     * Generates a 24,000 Hz 16-bit Mono Little-Endian PCM sine tone chunk with raised-cosine windowing.
     *
     * @param durationMs Duration of tone in milliseconds.
     * @param frequencyHz Frequency of the sine wave in Hertz (default 440.0 Hz).
     * @param amplitude Normalized peak amplitude scaling factor [0.0 .. 1.0] (default 0.5).
     * @param attackMs Duration of the raised-cosine attack ramp in milliseconds (default 20ms).
     * @param decayMs Duration of the raised-cosine decay ramp in milliseconds (default 20ms).
     * @return ByteArray containing 16-bit Little-Endian PCM data.
     */
    fun generateTone24k(
        durationMs: Int = 200,
        frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
        amplitude: Double = DEFAULT_AMPLITUDE,
        attackMs: Int = DEFAULT_ATTACK_MS,
        decayMs: Int = DEFAULT_DECAY_MS
    ): ByteArray {
        return generateTone(
            sampleRateHz = DEFAULT_SAMPLE_RATE_HZ,
            durationMs = durationMs,
            frequencyHz = frequencyHz,
            amplitude = amplitude,
            attackMs = attackMs,
            decayMs = decayMs
        )
    }

    /**
     * Generates a configurable sample-rate 16-bit Mono Little-Endian PCM sine tone chunk
     * with raised-cosine envelope windowing.
     *
     * @param sampleRateHz Sample rate in Hz (e.g. 24000, 16000).
     * @param durationMs Duration of tone in milliseconds.
     * @param frequencyHz Frequency of the sine wave in Hertz.
     * @param amplitude Normalized peak amplitude scaling factor [0.0 .. 1.0].
     * @param attackMs Duration of the attack ramp in milliseconds.
     * @param decayMs Duration of the decay ramp in milliseconds.
     * @return ByteArray containing 16-bit Little-Endian PCM data.
     */
    fun generateTone(
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        durationMs: Int = 200,
        frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
        amplitude: Double = DEFAULT_AMPLITUDE,
        attackMs: Int = DEFAULT_ATTACK_MS,
        decayMs: Int = DEFAULT_DECAY_MS
    ): ByteArray {
        if (durationMs <= 0 || sampleRateHz <= 0 || frequencyHz <= 0.0) {
            return ByteArray(0)
        }

        val totalSamples = ((sampleRateHz.toLong() * durationMs) / 1000L).toInt()
        if (totalSamples <= 0) return ByteArray(0)

        val clampedAmplitude = amplitude.coerceIn(0.0, 1.0)
        if (clampedAmplitude == 0.0) {
            return ByteArray(totalSamples * BYTES_PER_SAMPLE)
        }

        val rawAttackSamples = ((sampleRateHz.toLong() * attackMs.coerceAtLeast(0)) / 1000L).toInt()
        val rawDecaySamples = ((sampleRateHz.toLong() * decayMs.coerceAtLeast(0)) / 1000L).toInt()

        // Ensure attack and decay do not overlap if duration is short
        val (attackSamples, decaySamples) = if (rawAttackSamples + rawDecaySamples > totalSamples) {
            val half = totalSamples / 2
            Pair(half, totalSamples - half)
        } else {
            Pair(rawAttackSamples, rawDecaySamples)
        }

        val byteBuffer = ByteBuffer.allocate(totalSamples * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        val peakScale = clampedAmplitude * MAX_16BIT_VALUE
        val angularFrequency = 2.0 * PI * frequencyHz / sampleRateHz

        for (i in 0 until totalSamples) {
            val envelope = when {
                attackSamples > 0 && i < attackSamples -> {
                    // Raised-cosine attack: 0.5 * (1 - cos(PI * i / N_attack))
                    0.5 * (1.0 - cos(PI * i.toDouble() / attackSamples.toDouble()))
                }
                decaySamples > 0 && i >= totalSamples - decaySamples -> {
                    // Raised-cosine decay: 0.5 * (1 - cos(PI * (total - 1 - i) / N_decay))
                    val k = (totalSamples - 1 - i).coerceAtLeast(0)
                    0.5 * (1.0 - cos(PI * k.toDouble() / decaySamples.toDouble()))
                }
                else -> 1.0
            }

            val rawSine = sin(angularFrequency * i)
            val sampleValue = (rawSine * peakScale * envelope).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()

            byteBuffer.putShort(sampleValue)
        }

        return byteBuffer.array()
    }

    /**
     * Generates a multi-tone chime sequence (e.g. musical chords or notification arpeggio)
     * by concatenating individual raised-cosine windowed tones with optional silent gaps.
     *
     * @param frequencies List of tone frequencies in Hz.
     * @param noteDurationMs Duration of each individual note in milliseconds.
     * @param gapMs Duration of silence between notes in milliseconds.
     * @param amplitude Amplitude scaling factor [0.0 .. 1.0].
     * @param sampleRateHz Output sample rate in Hz (default: 24000).
     */
    fun generateChime(
        frequencies: List<Double>,
        noteDurationMs: Int = 120,
        gapMs: Int = 10,
        amplitude: Double = DEFAULT_AMPLITUDE,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ
    ): ByteArray {
        if (frequencies.isEmpty()) return ByteArray(0)

        val silenceBytes = if (gapMs > 0) generateSilence(gapMs, sampleRateHz) else ByteArray(0)
        val totalNoteBytes = frequencies.map { freq ->
            generateTone(
                sampleRateHz = sampleRateHz,
                durationMs = noteDurationMs,
                frequencyHz = freq,
                amplitude = amplitude,
                attackMs = (noteDurationMs * 0.15).toInt().coerceAtLeast(5),
                decayMs = (noteDurationMs * 0.25).toInt().coerceAtLeast(5)
            )
        }

        val totalSize = totalNoteBytes.sumOf { it.size } + (frequencies.size - 1).coerceAtLeast(0) * silenceBytes.size
        val resultBuffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        totalNoteBytes.forEachIndexed { index, noteChunk ->
            resultBuffer.put(noteChunk)
            if (index < totalNoteBytes.lastIndex && silenceBytes.isNotEmpty()) {
                resultBuffer.put(silenceBytes)
            }
        }

        return resultBuffer.array()
    }

    /**
     * Generates a multi-tone chime sequence at 24,000 Hz.
     */
    fun generateChime24k(
        frequencies: List<Double>,
        noteDurationMs: Int = 120,
        gapMs: Int = 10,
        amplitude: Double = DEFAULT_AMPLITUDE
    ): ByteArray {
        return generateChime(
            frequencies = frequencies,
            noteDurationMs = noteDurationMs,
            gapMs = gapMs,
            amplitude = amplitude,
            sampleRateHz = DEFAULT_SAMPLE_RATE_HZ
        )
    }

    /**
     * Generates a silent PCM chunk of specified duration.
     */
    fun generateSilence(
        durationMs: Int,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ
    ): ByteArray {
        if (durationMs <= 0 || sampleRateHz <= 0) return ByteArray(0)
        val samples = ((sampleRateHz.toLong() * durationMs) / 1000L).toInt()
        return ByteArray(samples * BYTES_PER_SAMPLE)
    }
}
