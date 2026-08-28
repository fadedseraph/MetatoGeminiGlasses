package com.metatogemini.glasses.media.camera

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.metatogemini.glasses.core.common.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.Executors

/**
 * Data model for a processed camera frame ready for Gemini Multimodal streaming.
 */
data class CameraFrameData(
    val jpegBytes: ByteArray,
    val base64Jpeg: String,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CameraFrameData
        return timestampMs == other.timestampMs && base64Jpeg == other.base64Jpeg
    }

    override fun hashCode(): Int = base64Jpeg.hashCode()
}

/**
 * Supported camera lenses.
 */
enum class CameraLens {
    BACK,
    FRONT
}

/**
 * Interface contract for CameraX lifecycle binding, preview rendering, and throttled frame streaming.
 */
interface CameraManager {
    val isStreamingFrames: StateFlow<Boolean>
    val currentLens: StateFlow<CameraLens>
    val isTorchEnabled: StateFlow<Boolean>

    /**
     * Binds CameraX Preview and ImageAnalysis to the given lifecycle owner and surface provider.
     */
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        lens: CameraLens = CameraLens.BACK
    )

    /**
     * Returns a Flow of rate-limited (1-2 FPS) JPEG compressed [CameraFrameData].
     * @param fps Target frames per second (1 or 2)
     * @param maxDimension Max width/height dimension for downscaling (default: 768px)
     * @param jpegQuality Compression quality 0-100 (default: 75)
     */
    fun startFrameStream(
        fps: Int = 1,
        maxDimension: Int = 768,
        jpegQuality: Int = 75
    ): Flow<CameraFrameData>

    /** Pauses frame streaming while keeping camera preview live. */
    fun pauseFrameStream()

    /** Resumes frame streaming. */
    fun resumeFrameStream()

    /** Switches between front and back camera lens. */
    fun switchLens(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider)

    /** Toggles flashlight/torch. */
    fun toggleTorch(enable: Boolean)

    /** Unbinds all CameraX use cases and releases resources. */
    fun unbind()
}

/**
 * Production implementation of [CameraManager] using CameraX.
 */
class CameraManagerImpl(
    private val context: Context,
    private val frameProcessor: FrameProcessor = FrameProcessorImpl(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CameraManager {

    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val _isStreamingFrames = MutableStateFlow(false)
    override val isStreamingFrames: StateFlow<Boolean> = _isStreamingFrames.asStateFlow()

    private val _currentLens = MutableStateFlow(CameraLens.BACK)
    override val currentLens: StateFlow<CameraLens> = _currentLens.asStateFlow()

    private val _isTorchEnabled = MutableStateFlow(false)
    override val isTorchEnabled: StateFlow<Boolean> = _isTorchEnabled.asStateFlow()

    @Volatile
    private var isStreamingActive = false

    override fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        lens: CameraLens
    ) {
        _currentLens.value = lens
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val selector = if (lens == CameraLens.BACK) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(surfaceProvider)
                }

                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun startFrameStream(
        fps: Int,
        maxDimension: Int,
        jpegQuality: Int
    ): Flow<CameraFrameData> = callbackFlow {
        val targetFps = fps.coerceIn(1, 2)
        frameProcessor.resetThrottle()
        isStreamingActive = true
        _isStreamingFrames.value = true

        val analyzer = ImageAnalysis.Analyzer { imageProxy ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (isStreamingActive && frameProcessor.shouldProcessFrame(now, targetFps)) {
                    val frameData = frameProcessor.processFrame(imageProxy, maxDimension, jpegQuality)
                    if (frameData != null) {
                        trySend(frameData)
                    }
                } else {
                    imageProxy.close() // Discard non-sampled frame
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in frame analysis pipeline", e)
                try {
                    imageProxy.close()
                } catch (ce: Exception) {
                    AppLogger.w(TAG, "Error closing imageProxy after error", ce)
                }
            }
        }

        imageAnalysis?.setAnalyzer(cameraExecutor, analyzer)

        awaitClose {
            isStreamingActive = false
            _isStreamingFrames.value = false
            imageAnalysis?.clearAnalyzer()
        }
    }.flowOn(cameraExecutor.asCoroutineDispatcher())

    override fun pauseFrameStream() {
        isStreamingActive = false
        _isStreamingFrames.value = false
    }

    override fun resumeFrameStream() {
        isStreamingActive = true
        _isStreamingFrames.value = true
    }

    override fun switchLens(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        val newLens = if (_currentLens.value == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        bindCamera(lifecycleOwner, surfaceProvider, newLens)
    }

    override fun toggleTorch(enable: Boolean) {
        camera?.cameraControl?.enableTorch(enable)
        _isTorchEnabled.value = enable
    }

    override fun unbind() {
        isStreamingActive = false
        _isStreamingFrames.value = false
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error unbinding camera provider", e)
        }
        camera = null
        imageAnalysis = null
    }
}
