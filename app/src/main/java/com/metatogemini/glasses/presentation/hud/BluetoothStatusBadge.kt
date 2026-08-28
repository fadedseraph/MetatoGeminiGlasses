package com.metatogemini.glasses.presentation.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metatogemini.glasses.domain.model.AudioRoute
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioState
import com.metatogemini.glasses.presentation.theme.AlertRed
import com.metatogemini.glasses.presentation.theme.CyberCyan
import com.metatogemini.glasses.presentation.theme.GlassBorder
import com.metatogemini.glasses.presentation.theme.GlassSurface
import com.metatogemini.glasses.presentation.theme.StatusConnectedColor
import com.metatogemini.glasses.presentation.theme.StatusConnectingColor
import com.metatogemini.glasses.presentation.theme.StatusDisconnectedColor
import com.metatogemini.glasses.presentation.theme.TextPrimary
import com.metatogemini.glasses.presentation.theme.TextSecondary

/**
 * Smart Glasses / Bluetooth audio route connection badge pill.
 */
@Composable
fun BluetoothStatusBadge(
    audioRoute: AudioRoute,
    bluetoothState: BluetoothAudioState,
    onReconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (statusColor, statusText) = when (bluetoothState) {
        is BluetoothAudioState.Connected -> StatusConnectedColor to (bluetoothState.deviceName.ifBlank { "Smart Glasses" })
        is BluetoothAudioState.Connecting -> StatusConnectingColor to "Connecting BT..."
        is BluetoothAudioState.Disconnected -> StatusDisconnectedColor to "Speaker (No Glasses)"
        is BluetoothAudioState.Error -> AlertRed to "BT Error"
    }

    val icon: ImageVector = when (audioRoute) {
        is AudioRoute.SmartGlasses -> Icons.Outlined.Visibility
        is AudioRoute.BluetoothHeadset -> Icons.Default.Headset
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .clickable { onReconnectClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Icon(
                imageVector = icon,
                contentDescription = "Audio Route",
                tint = if (bluetoothState is BluetoothAudioState.Connected) CyberCyan else TextSecondary,
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = statusText,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}
