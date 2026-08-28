package com.metatogemini.glasses.media.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.media.util.ImageUtils
import java.io.ByteArrayOutputStream

/**
 * Interface contract for processing, throttling, and compressing camera image frames.
 */
interface FrameProcessor {
    /**
     * Checks whether the current frame at [timestampMs] passes the rate limit for [targetFps] (1-2 FPS).
     */
    fun shouldProcessFrame(timestampMs: Long, targetFps: Int): Boolean

    /**
     * Converts an [ImageProxy] in YUV_420_888 format to downscaled, rotated Base64 JPEG [CameraFrameData].
     * Always ensures [ImageProxy.close] is executed in a finally block.
     */
    fun processFrame(
        imageProxy: ImageProxy,
        maxDimension: Int = 768,
        jpegQuality: Int = 75
    ): CameraFrameData?

    /**
     * Resets rate-limiting throttle state.
     */
    fun resetThrottle()
}

/**
 * Production implementation of [FrameProcessor] for Gemini Live video/frame ingestion.
 */
class FrameProcessorImpl : FrameProcessor {

    companion object {
        private const val TAG = "FrameProcessor"
    }

    @Volatile
    private var lastProcessedTimestampMs = 0L

    @Synchronized
    override fun shouldProcessFrame(timestampMs: Long, targetFps: Int): Boolean {
        val intervalMs = 1000L / targetFps.coerceIn(1, 2)
        if (timestampMs - lastProcessedTimestampMs >= intervalMs) {
            lastProcessedTimestampMs = timestampMs
            return true
        }
        return false
    }

    override fun processFrame(
        imageProxy: ImageProxy,
        maxDimension: Int,
        jpegQuality: Int
    ): CameraFrameData? {
        return try {
            val nv21Bytes = ImageUtils.yuv420ToNv21(imageProxy)
            val yuvImage = YuvImage(
                nv21Bytes,
                ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )

            val jpegOut = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height),
                90,
                jpegOut
            )
            val rawJpeg = jpegOut.toByteArray()

            val originalBitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return null
            val rotation = imageProxy.imageInfo.rotationDegrees

            val processedBitmap = ImageUtils.rotateAndDownscaleBitmap(
                bitmap = originalBitmap,
                rotationDegrees = rotation,
                maxDimension = maxDimension
            )

            val finalJpegStream = ByteArrayOutputStream()
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(1, 100), finalJpegStream)
            val finalJpegBytes = finalJpegStream.toByteArray()
            val base64 = ImageUtils.toBase64(finalJpegBytes)

            CameraFrameData(
                jpegBytes = finalJpegBytes,
                base64Jpeg = base64,
                width = processedBitmap.width,
                height = processedBitmap.height,
                rotationDegrees = rotation,
                timestampMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to process ImageProxy frame", e)
            null
        } finally {
            try {
                imageProxy.close()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error closing ImageProxy", e)
            }
        }
    }

    @Synchronized
    override fun resetThrottle() {
        lastProcessedTimestampMs = 0L
    }
}
