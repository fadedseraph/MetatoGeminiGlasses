package com.metatogemini.glasses.media

import com.metatogemini.glasses.media.camera.CameraFrameData
import com.metatogemini.glasses.media.camera.FrameProcessorImpl
import com.metatogemini.glasses.media.util.ImageUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameProcessorTest {

    @Test
    fun `shouldProcessFrame throttles correctly at 1 FPS`() {
        val processor = FrameProcessorImpl()

        // First frame at t = 1000ms passes
        assertTrue(processor.shouldProcessFrame(timestampMs = 1000L, targetFps = 1))

        // Subsequent frames before 1000ms elapsed interval are dropped
        assertFalse(processor.shouldProcessFrame(timestampMs = 1100L, targetFps = 1))
        assertFalse(processor.shouldProcessFrame(timestampMs = 1500L, targetFps = 1))
        assertFalse(processor.shouldProcessFrame(timestampMs = 1999L, targetFps = 1))

        // Frame at t = 2000ms (1000ms interval) passes
        assertTrue(processor.shouldProcessFrame(timestampMs = 2000L, targetFps = 1))

        // Frame at t = 2500ms dropped
        assertFalse(processor.shouldProcessFrame(timestampMs = 2500L, targetFps = 1))

        // Frame at t = 3000ms passes
        assertTrue(processor.shouldProcessFrame(timestampMs = 3000L, targetFps = 1))
    }

    @Test
    fun `shouldProcessFrame throttles correctly at 2 FPS`() {
        val processor = FrameProcessorImpl()

        // First frame at t = 1000ms passes
        assertTrue(processor.shouldProcessFrame(timestampMs = 1000L, targetFps = 2))

        // Intermediate frames within 500ms window are dropped
        assertFalse(processor.shouldProcessFrame(timestampMs = 1200L, targetFps = 2))
        assertFalse(processor.shouldProcessFrame(timestampMs = 1499L, targetFps = 2))

        // Frame at t = 1500ms (500ms interval) passes
        assertTrue(processor.shouldProcessFrame(timestampMs = 1500L, targetFps = 2))

        // Frame at t = 2000ms passes
        assertTrue(processor.shouldProcessFrame(timestampMs = 2000L, targetFps = 2))
    }

    @Test
    fun `resetThrottle allows immediate processing of next frame`() {
        val processor = FrameProcessorImpl()

        assertTrue(processor.shouldProcessFrame(timestampMs = 5000L, targetFps = 1))
        assertFalse(processor.shouldProcessFrame(timestampMs = 5100L, targetFps = 1))

        processor.resetThrottle()

        // After reset, immediate next frame passes
        assertTrue(processor.shouldProcessFrame(timestampMs = 5100L, targetFps = 1))
    }

    @Test
    fun `ImageUtils base64 encoding and decoding roundtrip succeeds`() {
        val originalData = byteArrayOf(0, 1, 2, 3, -1, -128, 127, 42, 99, 100)
        val base64Str = ImageUtils.toBase64(originalData)
        val decodedData = ImageUtils.fromBase64(base64Str)

        assertArrayEquals(originalData, decodedData)
    }

    @Test
    fun `CameraFrameData data class equality and hashCode work correctly`() {
        val frame1 = CameraFrameData(
            jpegBytes = byteArrayOf(1, 2, 3),
            base64Jpeg = "AQID",
            width = 640,
            height = 480,
            rotationDegrees = 0,
            timestampMs = 12345L
        )

        val frame2 = CameraFrameData(
            jpegBytes = byteArrayOf(1, 2, 3),
            base64Jpeg = "AQID",
            width = 640,
            height = 480,
            rotationDegrees = 0,
            timestampMs = 12345L
        )

        val frameDifferent = CameraFrameData(
            jpegBytes = byteArrayOf(4, 5, 6),
            base64Jpeg = "BAUG",
            width = 640,
            height = 480,
            rotationDegrees = 0,
            timestampMs = 67890L
        )

        assertEquals(frame1, frame2)
        assertEquals(frame1.hashCode(), frame2.hashCode())
        assertNotEquals(frame1, frameDifferent)
    }
}
