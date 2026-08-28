package com.metatogemini.glasses.core

import com.metatogemini.glasses.core.common.calculatePcm16Rms
import com.metatogemini.glasses.core.common.decodeBase64
import com.metatogemini.glasses.core.common.toBase64
import com.metatogemini.glasses.core.common.toByteArrayLe
import com.metatogemini.glasses.core.common.toShortArrayLe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class PCMConverterTest {

    @Test
    fun `short array to byte array Little Endian converts correctly`() {
        val originalShorts = shortArrayOf(0, 1, -1, 32767, -32768)
        val bytes = originalShorts.toByteArrayLe()
        val convertedShorts = bytes.toShortArrayLe()

        assertArrayEquals(originalShorts, convertedShorts)
    }

    @Test
    fun `base64 encode and decode roundtrip matches original bytes`() {
        val sampleData = "Smart Glasses Multimodal AI Audio Stream".toByteArray(Charsets.UTF_8)
        val base64String = sampleData.toBase64()
        val decoded = base64String.decodeBase64()

        assertArrayEquals(sampleData, decoded)
    }

    @Test
    fun `calculatePcm16Rms on empty array returns zero`() {
        val emptyBytes = ByteArray(0)
        val rms = emptyBytes.calculatePcm16Rms()
        assertEquals(0.0f, rms, 0.0001f)
    }

    @Test
    fun `calculatePcm16Rms on silence returns zero`() {
        val silenceBytes = ByteArray(1024) { 0 }
        val rms = silenceBytes.calculatePcm16Rms()
        assertEquals(0.0f, rms, 0.0001f)
    }

    @Test
    fun `calculatePcm16Rms on maximum amplitude square wave returns near one`() {
        val count = 512
        val shorts = ShortArray(count) { i ->
            if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
        }
        val bytes = shorts.toByteArrayLe()
        val rms = bytes.calculatePcm16Rms()

        assertTrue("Expected RMS > 0.99 for full scale wave, got $rms", rms > 0.99f)
    }

    @Test
    fun `calculatePcm16Rms on full scale sine wave returns approximately 0_707`() {
        val count = 1000
        val shorts = ShortArray(count) { i ->
            val angle = 2.0 * Math.PI * i / 100.0
            (sin(angle) * Short.MAX_VALUE).toInt().toShort()
        }
        val bytes = shorts.toByteArrayLe()
        val rms = bytes.calculatePcm16Rms()

        assertEquals(0.707f, rms, 0.05f)
    }
}
