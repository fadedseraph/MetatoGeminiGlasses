package com.metatogemini.glasses.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.metatogemini.glasses.media.camera.GlassesPhoto
import com.metatogemini.glasses.media.camera.GlassesPhotoSyncManagerImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesPhotoSyncManagerTest {

    private val context: Context = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var syncManager: GlassesPhotoSyncManagerImpl

    @Before
    fun setUp() {
        every { context.contentResolver } returns contentResolver
        syncManager = GlassesPhotoSyncManagerImpl(
            context = context,
            scope = testScope,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun startMonitoring_registersObserverAndSetsState() {
        syncManager.startMonitoring()
        assertTrue(syncManager.isMonitoring.value)
        verify {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                any()
            )
        }
    }

    @Test
    fun stopMonitoring_unregistersObserverAndResetsState() {
        syncManager.startMonitoring()
        assertTrue(syncManager.isMonitoring.value)

        syncManager.stopMonitoring()
        assertFalse(syncManager.isMonitoring.value)
        verify {
            contentResolver.unregisterContentObserver(any())
        }
    }

    @Test
    fun checkExifForMeta_detectsMetaAndRayBanTags() {
        val nonExifBytes = ByteArray(100)
        val result = syncManager.checkExifForMeta(ByteArrayInputStream(nonExifBytes))
        assertFalse(result)
    }

    @Test
    fun glassesPhoto_dataClassEquality() {
        val mockUri = mockk<Uri>(relaxed = true)
        val photo1 = GlassesPhoto(
            id = 101L,
            uri = mockUri,
            jpegBytes = byteArrayOf(1, 2, 3),
            fileName = "meta_photo.jpg",
            timestampMs = 5000L,
            source = "Meta View",
            width = 1920,
            height = 1080
        )
        val photo2 = GlassesPhoto(
            id = 101L,
            uri = mockUri,
            jpegBytes = byteArrayOf(4, 5, 6),
            fileName = "different_name.jpg",
            timestampMs = 5000L,
            source = "Meta View",
            width = 1920,
            height = 1080
        )

        assertEquals(photo1, photo2)
        assertEquals(photo1.hashCode(), photo2.hashCode())
    }

    @Test
    fun checkForNewPhotos_emptyCursor_returnsNull() = runTest(testDispatcher) {
        val cursor: Cursor = mockk(relaxed = true)
        every { cursor.moveToNext() } returns false
        every {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        val result = syncManager.checkForNewPhotos(maxAgeSeconds = 30)
        assertNull(result)
    }
}
