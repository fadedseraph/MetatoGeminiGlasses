package com.metatogemini.glasses.media.camera

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.media.ExifInterface
import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.media.util.ImageUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Collections

/**
 * Data model representing a synced photo captured by smart glasses.
 */
data class GlassesPhoto(
    val id: Long,
    val uri: Uri,
    val jpegBytes: ByteArray,
    val fileName: String,
    val timestampMs: Long,
    val source: String,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GlassesPhoto
        return id == other.id && timestampMs == other.timestampMs
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Interface contract for detecting and syncing newly captured photos from Ray-Ban Meta glasses
 * via Android MediaStore ContentObserver.
 */
interface GlassesPhotoSyncManager {
    val photoEvents: SharedFlow<GlassesPhoto>
    val isMonitoring: StateFlow<Boolean>

    /** Starts monitoring MediaStore for photos imported from Meta smart glasses. */
    fun startMonitoring()

    /** Stops monitoring MediaStore. */
    fun stopMonitoring()

    /** Manually checks for the latest photo matching Meta glasses criteria within the given age limit. */
    suspend fun checkForNewPhotos(maxAgeSeconds: Long = 30): GlassesPhoto?
}

/**
 * Production implementation of [GlassesPhotoSyncManager] watching [MediaStore.Images.Media.EXTERNAL_CONTENT_URI].
 */
class GlassesPhotoSyncManagerImpl(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GlassesPhotoSyncManager {

    companion object {
        private const val TAG = "GlassesPhotoSyncManager"
        private const val MAX_PROCESSED_CACHE_SIZE = 100
        private val KNOWN_META_FOLDERS = listOf(
            "meta view",
            "ray-ban",
            "ray-ban meta",
            "ray-ban stories",
            "meta"
        )
    }

    private val _photoEvents = MutableSharedFlow<GlassesPhoto>(extraBufferCapacity = 10)
    override val photoEvents: SharedFlow<GlassesPhoto> = _photoEvents.asSharedFlow()

    private val _isMonitoring = MutableStateFlow(false)
    override val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val processedPhotoIds = Collections.synchronizedSet(LinkedHashSet<Long>())
    private var monitoringStartTimeMs: Long = 0L

    private var contentObserver: ContentObserver? = null

    override fun startMonitoring() {
        if (_isMonitoring.value) return
        _isMonitoring.value = true
        monitoringStartTimeMs = System.currentTimeMillis()

        // Populate initial cache with existing recent photo IDs so we only react to brand-new captures
        scope.launch(ioDispatcher) {
            populateInitialCache()
        }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                AppLogger.d(TAG, "MediaStore onChange detected, uri=$uri")
                scope.launch(ioDispatcher) {
                    checkForNewPhotos(maxAgeSeconds = 45)
                }
            }
        }

        try {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            contentObserver = observer
            AppLogger.i(TAG, "Registered MediaStore ContentObserver for Meta Glasses photos")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register MediaStore ContentObserver", e)
        }
    }

    override fun stopMonitoring() {
        if (!_isMonitoring.value) return
        _isMonitoring.value = false
        contentObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error unregistering ContentObserver", e)
            }
        }
        contentObserver = null
        AppLogger.i(TAG, "Stopped monitoring Meta Glasses photos")
    }

    override suspend fun checkForNewPhotos(maxAgeSeconds: Long): GlassesPhoto? = withContext(ioDispatcher) {
        val cutoffTimestampSec = (System.currentTimeMillis() - (maxAgeSeconds * 1000L)) / 1000L

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.RELATIVE_PATH
            } else {
                MediaStore.Images.Media.DATA
            },
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(cutoffTimestampSec.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val bucketColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    if (processedPhotoIds.contains(id)) {
                        continue
                    }

                    val name = cursor.getString(nameColumn) ?: "photo_$id.jpg"
                    val dateAddedSec = cursor.getLong(dateAddedColumn)
                    val bucketName = if (bucketColumn != -1) cursor.getString(bucketColumn) ?: "" else ""
                    val path = if (pathColumn != -1) cursor.getString(pathColumn) ?: "" else ""

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val isMetaPhoto = isMetaGlassesPhoto(bucketName, path, contentUri)
                    if (isMetaPhoto) {
                        markAsProcessed(id)
                        val glassesPhoto = loadAndProcessPhoto(contentUri, id, name, dateAddedSec * 1000L, bucketName.ifBlank { "Meta View" })
                        if (glassesPhoto != null) {
                            AppLogger.i(TAG, "New Meta Glasses photo detected & processed: ${glassesPhoto.fileName} (${glassesPhoto.jpegBytes.size} bytes)")
                            _photoEvents.emit(glassesPhoto)
                            return@withContext glassesPhoto
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error querying MediaStore for smart glasses photos", e)
        }

        null
    }

    private fun isMetaGlassesPhoto(bucketName: String, path: String, uri: Uri): Boolean {
        val lowerBucket = bucketName.lowercase()
        val lowerPath = path.lowercase()

        // 1. Path / Album check
        val isMatchingFolder = KNOWN_META_FOLDERS.any { folder ->
            lowerBucket.contains(folder) || lowerPath.contains(folder)
        }
        if (isMatchingFolder) return true

        // 2. EXIF metadata inspection fallback
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                checkExifForMeta(inputStream)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    internal fun checkExifForMeta(inputStream: InputStream): Boolean {
        return try {
            val exif = ExifInterface(inputStream)
            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.lowercase().orEmpty()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.lowercase().orEmpty()
            make.contains("meta") || make.contains("luxottica") ||
                model.contains("ray-ban") || model.contains("stories")
        } catch (e: Exception) {
            false
        }
    }

    private fun loadAndProcessPhoto(
        uri: Uri,
        id: Long,
        fileName: String,
        timestampMs: Long,
        source: String
    ): GlassesPhoto? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return null

                // Downscale if unusually large to ensure smooth Gemini ingestion
                val processedBitmap = ImageUtils.rotateAndDownscaleBitmap(
                    bitmap = originalBitmap,
                    rotationDegrees = 0,
                    maxDimension = 1536
                )
                val jpegBytes = ImageUtils.bitmapToJpegBytes(processedBitmap, quality = 85)

                GlassesPhoto(
                    id = id,
                    uri = uri,
                    jpegBytes = jpegBytes,
                    fileName = fileName,
                    timestampMs = timestampMs,
                    source = source,
                    width = processedBitmap.width,
                    height = processedBitmap.height
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load and decode photo uri: $uri", e)
            null
        }
    }

    private fun populateInitialCache() {
        try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                var count = 0
                while (cursor.moveToNext() && count < MAX_PROCESSED_CACHE_SIZE) {
                    val id = cursor.getLong(idColumn)
                    processedPhotoIds.add(id)
                    count++
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error populating initial photo cache", e)
        }
    }

    private fun markAsProcessed(id: Long) {
        if (processedPhotoIds.size >= MAX_PROCESSED_CACHE_SIZE * 2) {
            processedPhotoIds.clear()
        }
        processedPhotoIds.add(id)
    }
}
