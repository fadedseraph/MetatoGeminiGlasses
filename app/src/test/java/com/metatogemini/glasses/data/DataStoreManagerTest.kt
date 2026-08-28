package com.metatogemini.glasses.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.data.local.DataStoreManager
import com.metatogemini.glasses.domain.model.SessionConfig
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dataStoreScope = CoroutineScope(testDispatcher + Job())

    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var dataStoreManager: DataStoreManager
    private val mockContext = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        testDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("test_preferences_${System.nanoTime()}.preferences_pb") }
        )
        dataStoreManager = DataStoreManager(
            context = mockContext,
            dataStore = testDataStore
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `test default session config when datastore is uninitialized`() = runTest(testDispatcher) {
        val defaultConfig = dataStoreManager.getSessionConfig()

        assertEquals("", defaultConfig.apiKey)
        assertEquals(Constants.DEFAULT_MODEL, defaultConfig.model)
        assertEquals(Constants.DEFAULT_VOICE, defaultConfig.voice)
        assertEquals(Constants.DEFAULT_SYSTEM_INSTRUCTION, defaultConfig.systemInstruction)
        assertFalse(defaultConfig.isMockMode)
        assertFalse(defaultConfig.isLoopbackMode)
        assertEquals(Constants.DEFAULT_CAMERA_FPS, defaultConfig.cameraFps)
        assertEquals(Constants.SAMPLE_RATE_CAPTURE_HZ, defaultConfig.audioSampleRate)
    }

    @Test
    fun `test updating individual preferences updates session config`() = runTest(testDispatcher) {
        dataStoreManager.updateApiKey("test-api-key-12345")
        dataStoreManager.updateModel("gemini-1.5-pro")
        dataStoreManager.updateVoice("Aoede")
        dataStoreManager.updateSystemInstruction("Custom instruction for smart glasses.")
        dataStoreManager.updateMockMode(true)
        dataStoreManager.updateLoopbackMode(true)
        dataStoreManager.updateCameraFps(2)

        val updated = dataStoreManager.getSessionConfig()
        assertEquals("test-api-key-12345", updated.apiKey)
        assertEquals("gemini-1.5-pro", updated.model)
        assertEquals("Aoede", updated.voice)
        assertEquals("Custom instruction for smart glasses.", updated.systemInstruction)
        assertTrue(updated.isMockMode)
        assertTrue(updated.isLoopbackMode)
        assertEquals(2, updated.cameraFps)
    }

    @Test
    fun `test updating entire session config`() = runTest(testDispatcher) {
        val customConfig = SessionConfig(
            apiKey = "ai-key-999",
            model = "gemini-1.5-flash",
            voice = "Fenrir",
            systemInstruction = "Concise voice assistant",
            isMockMode = false,
            isLoopbackMode = false,
            cameraFps = 1,
            audioSampleRate = 16000
        )

        dataStoreManager.updateSessionConfig(customConfig)

        val retrieved = dataStoreManager.getSessionConfig()
        assertEquals(customConfig, retrieved)
    }

    @Test
    fun `test clear resets preferences to defaults`() = runTest(testDispatcher) {
        dataStoreManager.updateApiKey("some-key")
        dataStoreManager.updateModel("gemini-1.5-pro")

        dataStoreManager.clear()

        val afterClear = dataStoreManager.getSessionConfig()
        assertEquals("", afterClear.apiKey)
        assertEquals(Constants.DEFAULT_MODEL, afterClear.model)
    }

    @Test
    fun `test reactive sessionConfigFlow emits updates`() = runTest(testDispatcher) {
        dataStoreManager.sessionConfigFlow.test {
            val initial = awaitItem()
            assertEquals("", initial.apiKey)

            dataStoreManager.updateApiKey("reactive-api-key")
            val updated = awaitItem()
            assertEquals("reactive-api-key", updated.apiKey)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
