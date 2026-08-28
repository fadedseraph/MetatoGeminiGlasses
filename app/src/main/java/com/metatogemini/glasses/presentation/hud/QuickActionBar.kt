package com.metatogemini.glasses.presentation.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metatogemini.glasses.presentation.theme.AlertRed
import com.metatogemini.glasses.presentation.theme.CyberCyan
import com.metatogemini.glasses.presentation.theme.GeminiBlue
import com.metatogemini.glasses.presentation.theme.GlassBorder
import com.metatogemini.glasses.presentation.theme.GlassSurface
import com.metatogemini.glasses.presentation.theme.TextPrimary
import com.metatogemini.glasses.presentation.theme.TextSecondary
import com.metatogemini.glasses.presentation.theme.WarningAmber

/**
 * Floating Quick Action Bar docked at the bottom of the HUD overlay.
 */
@Composable
fun QuickActionBar(
    isConnected: Boolean,
    isConnecting: Boolean,
    isMicMuted: Boolean,
    isAssistantSpeaking: Boolean,
    isMockMode: Boolean,
    isSnapshotAnalyzing: Boolean,
    onStartSessionClick: () -> Unit,
    onStopSessionClick: () -> Unit,
    onToggleMicClick: () -> Unit,
    onSnapshotClick: () -> Unit,
    onBargeInClick: () -> Unit,
    onSwitchLensClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(32.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Mic Mute / Unmute Button
            HudIconButton(
                icon = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = if (isMicMuted) "Unmute Mic" else "Mute Mic",
                tint = if (isMicMuted) AlertRed else CyberCyan,
                backgroundColor = if (isMicMuted) AlertRed.copy(alpha = 0.2f) else Color.Transparent,
                onClick = onToggleMicClick
            )

            // 2. Snapshot Trigger Button
            if (isSnapshotAnalyzing) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = CyberCyan,
                        strokeWidth = 2.5.dp
                    )
                }
            } else {
                HudIconButton(
                    icon = Icons.Default.CameraAlt,
                    contentDescription = "Capture Snapshot",
                    tint = CyberCyan,
                    onClick = onSnapshotClick
                )
            }

            // 3. Primary Center Session Button (Start / Stop)
            val sessionButtonColor by animateColorAsState(
                targetValue = if (isConnected) AlertRed else CyberCyan,
                animationSpec = tween(300),
                label = "session_button_color"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(sessionButtonColor.copy(alpha = 0.25f))
                    .border(1.5.dp, sessionButtonColor, RoundedCornerShape(24.dp))
                    .clickable {
                        if (isConnected) onStopSessionClick() else onStartSessionClick()
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = WarningAmber,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CONNECTING",
                            color = WarningAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isConnected) "Stop Session" else "Start Session",
                            tint = sessionButtonColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConnected) "END LIVE" else "START LIVE",
                            color = sessionButtonColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 4. Low-latency Barge-In / Interruption Button (when assistant is speaking)
            if (isAssistantSpeaking) {
                HudIconButton(
                    icon = Icons.Default.PanTool,
                    contentDescription = "Interrupt Speech",
                    tint = AlertRed,
                    backgroundColor = AlertRed.copy(alpha = 0.25f),
                    onClick = onBargeInClick
                )
            } else {
                // Camera Lens switch button
                HudIconButton(
                    icon = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Camera Lens",
                    tint = TextSecondary,
                    onClick = onSwitchLensClick
                )
            }

            // 5. Settings Drawer Button
            HudIconButton(
                icon = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = if (isMockMode) WarningAmber else TextSecondary,
                onClick = onSettingsClick
            )
        }
    }
}

@Composable
private fun HudIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}
