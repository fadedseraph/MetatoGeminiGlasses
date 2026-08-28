package com.metatogemini.glasses.presentation.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metatogemini.glasses.presentation.model.SubtitleEntry
import com.metatogemini.glasses.presentation.theme.AlertRed
import com.metatogemini.glasses.presentation.theme.CyberCyan
import com.metatogemini.glasses.presentation.theme.GeminiBlue
import com.metatogemini.glasses.presentation.theme.GlassBorder
import com.metatogemini.glasses.presentation.theme.GlassSurface
import com.metatogemini.glasses.presentation.theme.TextPrimary
import com.metatogemini.glasses.presentation.theme.TextSecondary
import com.metatogemini.glasses.presentation.theme.TextSubtle
import com.metatogemini.glasses.presentation.theme.WarningAmber

/**
 * Autoscrolling live streaming subtitle ticker card displaying speech transcription
 * for both wearer prompts and Gemini AI responses.
 */
@Composable
fun SubtitleBar(
    currentTranscript: String,
    subtitleHistory: List<SubtitleEntry>,
    isAssistantSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new words or history items arrive
    LaunchedEffect(currentTranscript, subtitleHistory.size) {
        val totalItems = subtitleHistory.size + if (currentTranscript.isNotEmpty()) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "cursor_blink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_blink_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp, max = 150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        if (subtitleHistory.isEmpty() && currentTranscript.isEmpty() && !isAssistantSpeaking) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(CyberCyan)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Listening for audio / smart glasses speech...",
                    color = TextSubtle,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(subtitleHistory) { entry ->
                    SubtitleHistoryItem(entry = entry)
                }

                if (currentTranscript.isNotEmpty() || isAssistantSpeaking) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "GEMINI",
                                color = GeminiBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentTranscript.ifEmpty { "..." },
                                color = TextPrimary,
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.SansSerif,
                                modifier = Modifier.weight(1f)
                            )
                            if (isAssistantSpeaking) {
                                Text(
                                    text = "▮",
                                    color = GeminiBlue,
                                    fontSize = 14.sp,
                                    modifier = Modifier
                                        .padding(start = 2.dp)
                                        .alpha(cursorAlpha)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleHistoryItem(entry: SubtitleEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        val (tagColor, tagLabel) = if (entry.isFromUser) {
            CyberCyan to "YOU"
        } else {
            GeminiBlue to "GEMINI"
        }

        Text(
            text = tagLabel,
            color = tagColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.text,
            color = if (entry.isFromUser) TextSecondary else TextPrimary,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier.weight(1f)
        )
        if (entry.isInterrupted) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "[INTERRUPTED]",
                color = AlertRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AlertRed.copy(alpha = 0.15f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}
