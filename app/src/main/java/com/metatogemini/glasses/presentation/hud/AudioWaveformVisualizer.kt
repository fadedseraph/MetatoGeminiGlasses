package com.metatogemini.glasses.presentation.hud

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.metatogemini.glasses.media.audio.AudioRmsState
import com.metatogemini.glasses.presentation.theme.AlertRed
import com.metatogemini.glasses.presentation.theme.CyberCyan
import com.metatogemini.glasses.presentation.theme.CyberCyanDark
import com.metatogemini.glasses.presentation.theme.GeminiBlue
import com.metatogemini.glasses.presentation.theme.GeminiPurple
import com.metatogemini.glasses.presentation.theme.GlassBorder
import com.metatogemini.glasses.presentation.theme.GlassSurface
import com.metatogemini.glasses.presentation.theme.TextSubtle
import com.metatogemini.glasses.presentation.theme.WarningAmber
import kotlin.math.PI
import kotlin.math.sin

/**
 * Animated real-time audio waveform visualizer rendered via Compose [Canvas].
 * Smoothly interpolates RMS amplitudes for both microphone capture and Gemini assistant speech.
 */
@Composable
fun AudioWaveformVisualizer(
    micRms: AudioRmsState,
    speakerRms: AudioRmsState,
    isAssistantSpeaking: Boolean,
    isMicMuted: Boolean,
    isMockMode: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24
) {
    val activeNormalized = if (isAssistantSpeaking) {
        speakerRms.normalizedRms.coerceIn(0.05f, 1.0f)
    } else if (isMicMuted) {
        0.0f
    } else {
        micRms.normalizedRms.coerceIn(0.02f, 1.0f)
    }

    val animatedAmplitude by animateFloatAsState(
        targetValue = activeNormalized,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 400f),
        label = "waveform_amplitude"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "waveform_phase")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase_oscillation"
    )

    val primaryBarColor = when {
        isMicMuted && !isAssistantSpeaking -> AlertRed.copy(alpha = 0.5f)
        isAssistantSpeaking -> GeminiBlue
        isMockMode -> WarningAmber
        else -> CyberCyan
    }

    val secondaryBarColor = when {
        isMicMuted && !isAssistantSpeaking -> AlertRed.copy(alpha = 0.2f)
        isAssistantSpeaking -> GeminiPurple
        isMockMode -> WarningAmber.copy(alpha = 0.6f)
        else -> CyberCyanDark
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val totalSpacing = canvasWidth / (barCount * 1.5f)
            val barWidth = totalSpacing.coerceAtLeast(3f)
            val step = canvasWidth / barCount

            val centerY = canvasHeight / 2f
            val maxBarHeight = canvasHeight * 0.9f
            val minBarHeight = 4f

            for (i in 0 until barCount) {
                val normalizedIndex = i.toFloat() / barCount.toFloat()
                // Center-weighted Gaussian bell curve envelope
                val bellEnvelope = sin(normalizedIndex * PI).toFloat().coerceIn(0.2f, 1.0f)

                // Sine oscillation modulation
                val waveMod = sin(normalizedIndex * 4 * PI + phase).toFloat() * 0.35f
                val dynamicScale = (animatedAmplitude * bellEnvelope + waveMod * animatedAmplitude).coerceIn(0.0f, 1.0f)

                val barHeight = if (isMicMuted && !isAssistantSpeaking) {
                    minBarHeight
                } else {
                    (minBarHeight + (maxBarHeight - minBarHeight) * dynamicScale).coerceAtLeast(minBarHeight)
                }

                val startX = i * step + (step - barWidth) / 2f
                val startY = centerY - barHeight / 2f

                val barBrush = Brush.verticalGradient(
                    colors = listOf(primaryBarColor, secondaryBarColor),
                    startY = startY,
                    endY = startY + barHeight
                )

                drawRoundRect(
                    brush = barBrush,
                    topLeft = Offset(startX, startY),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}
