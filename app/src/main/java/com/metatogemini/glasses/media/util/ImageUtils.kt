package com.metatogemini.glasses.media.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.max
import kotlin.math.min

/**
 * Utility functions for camera frame image transformations, YUV420 to NV21/JPEG conversion,
 * matrix rotations, aspect-ratio preserving downscaling, and Base64 encoding.
 */
object ImageUtils {

    /**
     * Converts an [ImageProxy] in YUV_420_888 format into an NV21 byte array.
     */
    fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val planes = imageProxy.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        var pos = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val uvWidth = width / 2
        val uvHeight = height / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vIndex = row * uvRowStride + col * uvPixelStride
                val uIndex = row * uvRowStride + col * uvPixelStride
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }

        return nv21
    }

    /**
     * Compresses a YUV_420_888 [ImageProxy] directly into raw JPEG bytes.
     */
    fun yuvImageProxyToJpeg(imageProxy: ImageProxy, quality: Int = 90): ByteArray {
        val nv21 = yuv420ToNv21(imageProxy)
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        val outStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            quality.coerceIn(1, 100),
            outStream
        )
        return outStream.toByteArray()
    }

    /**
     * Rotates and downscales a [Bitmap] ensuring max dimension does not exceed [maxDimension],
     * preserving original aspect ratio.
     */
    fun rotateAndDownscaleBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        maxDimension: Int = 768
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val scale = min(1.0f, maxDimension.toFloat() / max(width, height).toFloat())
        if (rotationDegrees == 0 && scale >= 1.0f) {
            return bitmap
        }

        val matrix = Matrix().apply {
            if (rotationDegrees != 0) {
                postRotate(rotationDegrees.toFloat())
            }
            if (scale < 1.0f) {
                postScale(scale, scale)
            }
        }

        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    /**
     * Compresses a [Bitmap] to a JPEG ByteArray.
     */
    fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 75): ByteArray {
        val outStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), outStream)
        return outStream.toByteArray()
    }

    /**
     * Converts raw bytes to a standard Base64 string without line breaks.
     */
    fun toBase64(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Decodes a Base64 string to a byte array.
     */
    fun fromBase64(base64Str: String): ByteArray {
        return Base64.getDecoder().decode(base64Str)
    }
}
