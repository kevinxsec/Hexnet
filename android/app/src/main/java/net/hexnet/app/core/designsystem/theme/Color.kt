package net.hexnet.app.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Monochromatic Foundation - Dark Mode (Graphite / Carbon)
val HexBackgroundDark = Color(0xFF0B0C10)
val HexSurfaceDark = Color(0xFF13141C)
val HexSurfaceSubtleDark = Color(0xFF1A1C26)
val HexBorderDark = Color(0xFF262838)
val HexBorderSubtleDark = Color(0xFF1C1E2A)

// Restrained Cobalt Accent (Signal/Linear-inspired)
val HexAccentPrimary = Color(0xFF3B82F6)
val HexAccentHover = Color(0xFF2563EB)
val HexAccentSubtle = Color(0xFF1E3A8A)

// Status & Semantic Colors (Quiet & Precise)
val HexSuccessDirect = Color(0xFF10B981)
val HexWarningRelay = Color(0xFFF59E0B)
val HexErrorAlert = Color(0xFFEF4444)

// Neutral Text Hierarchy - Dark Mode
val HexTextPrimaryDark = Color(0xFFF4F4F5)
val HexTextSecondaryDark = Color(0xFF9CA3AF)
val HexTextMutedDark = Color(0xFF6B7280)

// Monochromatic Foundation - Light Mode
val HexBackgroundLight = Color(0xFFF8F9FA)
val HexSurfaceLight = Color(0xFFFFFFFF)
val HexSurfaceSubtleLight = Color(0xFFF1F3F5)
val HexBorderLight = Color(0xFFE5E7EB)
val HexTextPrimaryLight = Color(0xFF111827)
val HexTextSecondaryLight = Color(0xFF4B5563)
val HexTextMutedLight = Color(0xFF9CA3AF)

val HexnetDarkColorScheme = darkColorScheme(
    primary = HexAccentPrimary,
    onPrimary = Color.White,
    primaryContainer = HexAccentSubtle,
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = HexWarningRelay,
    onSecondary = Color.Black,
    tertiary = HexSuccessDirect,
    background = HexBackgroundDark,
    onBackground = HexTextPrimaryDark,
    surface = HexSurfaceDark,
    onSurface = HexTextPrimaryDark,
    surfaceVariant = HexSurfaceSubtleDark,
    onSurfaceVariant = HexTextSecondaryDark,
    outline = HexBorderDark
)

val HexnetLightColorScheme = lightColorScheme(
    primary = HexAccentHover,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFFD97706),
    onSecondary = Color.White,
    tertiary = Color(0xFF059669),
    background = HexBackgroundLight,
    onBackground = HexTextPrimaryLight,
    surface = HexSurfaceLight,
    onSurface = HexTextPrimaryLight,
    surfaceVariant = HexSurfaceSubtleLight,
    onSurfaceVariant = HexTextSecondaryLight,
    outline = HexBorderLight
)

