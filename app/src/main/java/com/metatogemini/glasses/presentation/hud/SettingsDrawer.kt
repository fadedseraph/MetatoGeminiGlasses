package com.metatogemini.glasses.presentation.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.presentation.theme.BackgroundDark
import com.metatogemini.glasses.presentation.theme.CyberCyan
import com.metatogemini.glasses.presentation.theme.GeminiBlue
import com.metatogemini.glasses.presentation.theme.GlassBorder
import com.metatogemini.glasses.presentation.theme.GlassSurface
import com.metatogemini.glasses.presentation.theme.SurfaceDark
import com.metatogemini.glasses.presentation.theme.TextPrimary
import com.metatogemini.glasses.presentation.theme.TextSecondary
import com.metatogemini.glasses.presentation.theme.TextSubtle
import com.metatogemini.glasses.presentation.theme.WarningAmber

/**
 * Settings Modal Bottom Sheet for configuring Gemini API credentials, models, voices,
 * developer mock mode, and smart glasses hardware routing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDrawer(
    sessionConfig: SessionConfig,
    onDismiss: () -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
    onUpdateVoice: (String) -> Unit,
    onUpdateSystemInstruction: (String) -> Unit,
    onToggleMockMode: (Boolean) -> Unit,
    onToggleLoopbackMode: (Boolean) -> Unit,
    onToggleAutoAnalyzeGlassesPhotos: (Boolean) -> Unit = {},
    onUpdateCameraFps: (Int) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val focusManager = LocalFocusManager.current
    var tempApiKey by remember(sessionConfig.apiKey) { mutableStateOf(sessionConfig.apiKey) }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var tempInstruction by remember(sessionConfig.systemInstruction) {
        mutableStateOf(sessionConfig.systemInstruction)
    }

    val availableModels: List<Pair<String, String>> = listOf(
        "gemini-2.0-flash-exp" to "Gemini 2.0 Flash Exp (Live Bidi)",
        "gemini-2.0-flash" to "Gemini 2.0 Flash",
        "gemini-2.5-flash" to "Gemini 2.5 Flash",
        "gemini-1.5-flash" to "Gemini 1.5 Flash",
        "gemini-1.5-pro" to "Gemini 1.5 Pro (Vision Deep)"
    )

    val availableVoices: List<String> = Constants.SUPPORTED_VOICES

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HUD & ENGINE SETTINGS",
                    color = CyberCyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Developer Mock Mode Switch
            SettingsSectionHeader(title = "SIMULATION & OFFLINE")
            SettingSwitchRow(
                title = "Developer Mock Mode",
                subtitle = "Simulate WebSocket tokens, tones, and REST vision offline without API key",
                checked = sessionConfig.isMockMode,
                onCheckedChange = { onToggleMockMode(it) },
                activeColor = WarningAmber
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Microphone Loopback Mode Switch
            SettingSwitchRow(
                title = "Mic Loopback Mode",
                subtitle = "Real-time 16kHz capture -> playback loop for audio hardware testing",
                checked = sessionConfig.isLoopbackMode,
                onCheckedChange = { onToggleLoopbackMode(it) },
                activeColor = CyberCyan
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Smart Glasses Photo Auto-Analyze Switch
            SettingSwitchRow(
                title = "Auto-Analyze Glasses Photos",
                subtitle = "Automatically analyze photos taken with Ray-Ban Meta shutter button when synced by Meta View",
                checked = sessionConfig.autoAnalyzeGlassesPhotos,
                onCheckedChange = { onToggleAutoAnalyzeGlassesPhotos(it) },
                activeColor = CyberCyan
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Gemini API Key
            SettingsSectionHeader(title = "GEMINI API CREDENTIALS")
            OutlinedTextField(
                value = tempApiKey,
                onValueChange = { tempApiKey = it },
                label = { Text("Gemini API Key", color = TextSecondary) },
                placeholder = { Text("AIzaSy...", color = TextSubtle) },
                singleLine = true,
                visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        onUpdateApiKey(tempApiKey)
                    }
                ),
                trailingIcon = {
                    IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                        Icon(
                            imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle API Key Visibility",
                            tint = TextSecondary
                        )
                    }
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Key, contentDescription = null, tint = CyberCyan)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = GlassBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = BackgroundDark,
                    unfocusedContainerColor = BackgroundDark
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    onUpdateApiKey(tempApiKey)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = "Save API Key",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Model Selection
            SettingsSectionHeader(title = "GEMINI MODEL")
            availableModels.forEach { pair ->
                val modelId = pair.first
                val label = pair.second
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onUpdateModel(modelId) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = sessionConfig.model == modelId,
                        onClick = { onUpdateModel(modelId) },
                        colors = RadioButtonDefaults.colors(selectedColor = CyberCyan)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = label,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = modelId,
                            color = TextSubtle,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            var tempCustomModel by remember(sessionConfig.model) { mutableStateOf(sessionConfig.model) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = tempCustomModel,
                    onValueChange = { tempCustomModel = it },
                    label = { Text("Custom Model ID", color = TextSecondary) },
                    placeholder = { Text("e.g. gemini-2.0-flash", color = TextSubtle) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberCyan,
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = BackgroundDark,
                        unfocusedContainerColor = BackgroundDark
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (tempCustomModel.isNotBlank()) {
                            onUpdateModel(tempCustomModel.trim())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                    enabled = tempCustomModel.isNotBlank() && tempCustomModel != sessionConfig.model
                ) {
                    Text("Set", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Voice Selection
            SettingsSectionHeader(title = "PREBUILT VOICE")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableVoices.forEach { voice ->
                    val isSelected = sessionConfig.voice.equals(voice, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) GeminiBlue.copy(alpha = 0.25f) else BackgroundDark)
                            .border(
                                1.dp,
                                if (isSelected) GeminiBlue else GlassBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onUpdateVoice(voice) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = voice,
                            color = if (isSelected) GeminiBlue else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 6. Camera Frame Rate
            SettingsSectionHeader(title = "CAMERA ANALYSIS FRAME RATE")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(1, 2).forEach { fps ->
                    val isSelected = sessionConfig.cameraFps == fps
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) CyberCyan.copy(alpha = 0.25f) else BackgroundDark)
                            .border(
                                1.dp,
                                if (isSelected) CyberCyan else GlassBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onUpdateCameraFps(fps) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$fps FPS Throttled",
                            color = if (isSelected) CyberCyan else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 7. System Instruction
            SettingsSectionHeader(title = "SYSTEM INSTRUCTION PROMPT")
            OutlinedTextField(
                value = tempInstruction,
                onValueChange = { tempInstruction = it },
                label = { Text("System Instruction", color = TextSecondary) },
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = GlassBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = BackgroundDark,
                    unfocusedContainerColor = BackgroundDark
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    onUpdateSystemInstruction(tempInstruction)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = "Apply Prompt",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = CyberCyan,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BackgroundDark)
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = TextSubtle,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = activeColor,
                checkedTrackColor = activeColor.copy(alpha = 0.35f)
            )
        )
    }
}
