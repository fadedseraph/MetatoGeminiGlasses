package com.metatogemini.glasses.presentation.hud

import android.graphics.BitmapFactory
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.media.camera.CameraManager
import com.metatogemini.glasses.presentation.model.HudUiState
import com.metatogemini.glasses.presentation.theme.AlertRed
import com.metatogemini.glasses.presentation.theme.BackgroundDark
import com.metatogemini.glasses.presentation.theme.CyberCyan
import com.metatogemini.glasses.presentation.theme.GeminiBlue
import com.metatogemini.glasses.presentation.theme.GlassBorder
import com.metatogemini.glasses.presentation.theme.GlassSurface
import com.metatogemini.glasses.presentation.theme.StatusConnectedColor
import com.metatogemini.glasses.presentation.theme.StatusConnectingColor
import com.metatogemini.glasses.presentation.theme.StatusDisconnectedColor
import com.metatogemini.glasses.presentation.theme.StatusErrorColor
import com.metatogemini.glasses.presentation.theme.StatusMockColor
import com.metatogemini.glasses.presentation.theme.SurfaceDark
import com.metatogemini.glasses.presentation.theme.TextPrimary
import com.metatogemini.glasses.presentation.theme.TextSecondary
import com.metatogemini.glasses.presentation.theme.TextSubtle
import com.metatogemini.glasses.presentation.theme.WarningAmber
import com.metatogemini.glasses.presentation.viewmodel.LiveHudViewModel

/**
 * Main Smart Glasses Heads-Up Display (HUD) overlay screen.
 * Layers CameraX viewfinder preview with Material 3 glassmorphic HUD controls,
 * animated RMS visualizer, streaming subtitles, Bluetooth status, and quick actions.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun LiveHudScreen(
    viewModel: LiveHudViewModel,
    cameraManager: CameraManager,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // Layer 0: CameraX Viewfinder Surface
        if (uiState.isCameraEnabled) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        previewViewRef = this
                        cameraManager.bindCamera(
                            lifecycleOwner = lifecycleOwner,
                            surfaceProvider = this.surfaceProvider,
                            lens = uiState.currentLens
                        )
                    }
                },
                update = { view ->
                    previewViewRef = view
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BackgroundDark),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Camera Preview Disabled",
                    color = TextSubtle,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
        }

        // Semi-transparent gradient overlay for HUD contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.75f)
                        )
                    )
                )
        )

        // Layer 1: HUD Controls Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // --- TOP STATUS BAR ---
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Bluetooth Smart Glasses Badge
                    BluetoothStatusBadge(
                        audioRoute = uiState.audioRoute,
                        bluetoothState = uiState.bluetoothState,
                        onReconnectClick = { viewModel.reconnectBluetooth() }
                    )

                    // Connection Status & Model Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ConnectionStatusPill(
                            connectionState = uiState.connectionState,
                            isMockMode = uiState.isMockMode
                        )

                        // Camera toggle icon
                        IconButton(
                            onClick = { viewModel.toggleCamera() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(GlassSurface)
                                .border(1.dp, GlassBorder, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (uiState.isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = "Toggle Camera",
                                tint = if (uiState.isCameraEnabled) CyberCyan else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Torch toggle icon
                        IconButton(
                            onClick = { viewModel.toggleTorch(!uiState.isTorchEnabled) },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(GlassSurface)
                                .border(1.dp, GlassBorder, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (uiState.isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Toggle Torch",
                                tint = if (uiState.isTorchEnabled) WarningAmber else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Error Notice Banner
                AnimatedVisibility(
                    visible = uiState.errorMessage != null,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    uiState.errorMessage?.let { errorMsg ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AlertRed.copy(alpha = 0.85f))
                                .border(1.dp, AlertRed, RoundedCornerShape(12.dp))
                                .clickable { viewModel.clearError() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = errorMsg,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // User Notice Banner
                AnimatedVisibility(
                    visible = uiState.userNotice != null,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    uiState.userNotice?.let { notice ->
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyberCyan.copy(alpha = 0.2f))
                                .border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = notice,
                                color = CyberCyan,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // --- CENTER RETICLE & WAVEFORM ---
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Futuristic HUD Target Reticle
                HudReticle(modifier = Modifier.size(80.dp))

                Spacer(modifier = Modifier.height(16.dp))

                // Real-time animated RMS Waveform Visualizer
                AudioWaveformVisualizer(
                    micRms = uiState.micRms,
                    speakerRms = uiState.speakerRms,
                    isAssistantSpeaking = uiState.isAssistantSpeaking,
                    isMicMuted = uiState.isMicMuted,
                    isMockMode = uiState.isMockMode,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            // --- LOWER SUBTITLE & QUICK ACTION DOCK ---
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Live Autoscrolling Subtitle Bar
                SubtitleBar(
                    currentTranscript = uiState.currentTranscript,
                    subtitleHistory = uiState.subtitleHistory,
                    isAssistantSpeaking = uiState.isAssistantSpeaking
                )

                // Bottom Dock Quick Action Bar
                QuickActionBar(
                    isConnected = uiState.isConnected,
                    isConnecting = uiState.isConnecting,
                    isMicMuted = uiState.isMicMuted,
                    isAssistantSpeaking = uiState.isAssistantSpeaking,
                    isMockMode = uiState.isMockMode,
                    isSnapshotAnalyzing = uiState.isSnapshotAnalyzing,
                    onStartSessionClick = { viewModel.startSession() },
                    onStopSessionClick = { viewModel.stopSession() },
                    onToggleMicClick = { viewModel.toggleMicMute() },
                    onSnapshotClick = { viewModel.captureSnapshot() },
                    onBargeInClick = { viewModel.triggerBargeIn() },
                    onSwitchLensClick = {
                        previewViewRef?.let { pv ->
                            cameraManager.switchLens(lifecycleOwner, pv.surfaceProvider)
                        }
                    },
                    onSettingsClick = { viewModel.openSettings(true) }
                )
            }
        }

        // --- SETTINGS DRAWER SHEET ---
        if (uiState.isSettingsOpen) {
            SettingsDrawer(
                sessionConfig = uiState.sessionConfig,
                onDismiss = { viewModel.openSettings(false) },
                onUpdateApiKey = { viewModel.updateApiKey(it) },
                onUpdateModel = { viewModel.updateModel(it) },
                onUpdateVoice = { viewModel.updateVoice(it) },
                onUpdateSystemInstruction = { viewModel.updateSystemInstruction(it) },
                onToggleMockMode = { viewModel.toggleMockMode(it) },
                onToggleLoopbackMode = { viewModel.toggleLoopbackMode(it) },
                onToggleAutoAnalyzeGlassesPhotos = { viewModel.toggleAutoAnalyzeGlassesPhotos(it) },
                onUpdateCameraFps = { viewModel.updateCameraFps(it) }
            )
        }

        // --- SNAPSHOT INSPECTOR SHEET / DIALOG ---
        if (uiState.isSnapshotInspectorOpen && uiState.latestSnapshotResult != null) {
            SnapshotInspectorDialog(
                resultText = uiState.latestSnapshotResult?.text.orEmpty(),
                latencyMs = uiState.latestSnapshotResult?.latencyMs ?: 0L,
                imageBytes = uiState.latestSnapshotBytes,
                onDismiss = { viewModel.openSnapshotInspector(false) }
            )
        }
    }
}

@Composable
private fun ConnectionStatusPill(
    connectionState: ConnectionState,
    isMockMode: Boolean
) {
    val (dotColor, label) = when {
        isMockMode && connectionState is ConnectionState.Connected -> StatusMockColor to "MOCK LIVE"
        connectionState is ConnectionState.Connected -> StatusConnectedColor to "LIVE"
        connectionState is ConnectionState.Connecting -> StatusConnectingColor to "CONNECTING"
        connectionState is ConnectionState.Reconnecting -> StatusConnectingColor to "RETRYING"
        connectionState is ConnectionState.Failed -> StatusErrorColor to "ERROR"
        else -> StatusDisconnectedColor to "STANDBY"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun HudReticle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .border(1.dp, CyberCyan.copy(alpha = 0.3f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(CyberCyan.copy(alpha = 0.6f))
        )
    }
}

@Composable
private fun SnapshotInspectorDialog(
    resultText: String,
    latencyMs: Long,
    imageBytes: ByteArray?,
    onDismiss: () -> Unit
) {
    val bitmap = remember(imageBytes) {
        imageBytes?.takeIf { it.isNotEmpty() }?.let {
            try {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            } catch (e: Exception) {
                null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SNAPSHOT ANALYSIS",
                    color = CyberCyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${latencyMs}ms",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Captured Snapshot",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark)
                        .padding(12.dp)
                ) {
                    Text(
                        text = resultText,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
            ) {
                Text(
                    text = "Close",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    )
}
