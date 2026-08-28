package com.metatogemini.glasses.presentation.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Brand & Accent Colors
val CyberCyan = Color(0xFF00E5FF)
val CyberCyanDark = Color(0xFF00B0FF)
val GeminiBlue = Color(0xFF8AB4F8)
val GeminiPurple = Color(0xFF7C4DFF)
val NeonGreen = Color(0xFF00E676)
val WarningAmber = Color(0xFFFFD600)
val AlertRed = Color(0xFFFF5252)

// HUD Glassmorphism & Background Colors
val BackgroundDark = Color(0xFF0A0E17)
val SurfaceDark = Color(0xFF121824)
val SurfaceVariantDark = Color(0xFF1A2234)

val GlassBackground = Color(0xCC0A0E17)    // 80% opacity dark backdrop
val GlassSurface = Color(0xB3121824)       // 70% opacity card surface
val GlassBorder = Color(0x33FFFFFF)        // 20% opacity border
val GlassBorderFocused = Color(0x8000E5FF) // 50% opacity cyan border

// Status Colors
val StatusConnectedColor = Color(0xFF00E676)
val StatusConnectingColor = Color(0xFFFFD600)
val StatusDisconnectedColor = Color(0xFF9E9E9E)
val StatusErrorColor = Color(0xFFFF5252)
val StatusMockColor = Color(0xFFFF9100)

// Text Colors
val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)
val TextSubtle = Color(0xFF64748B)

// Material 3 Dark Color Scheme
val DarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF9CF0FF),
    secondary = GeminiBlue,
    onSecondary = Color(0xFF00325B),
    secondaryContainer = Color(0xFF004881),
    onSecondaryContainer = Color(0xFFD2E4FF),
    tertiary = GeminiPurple,
    onTertiary = Color(0xFF381E72),
    tertiaryContainer = Color(0xFF4F378B),
    onTertiaryContainer = Color(0xFFEADDFF),
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    error = AlertRed,
    onError = Color(0xFF690005)
)
