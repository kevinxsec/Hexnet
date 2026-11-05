package net.hexnet.app.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Pure AMOLED Black Canvas (#000000)
val HexBackgroundDark = Color(0xFF000000)
val HexSurfaceDark = Color(0xFF09090B)           // Obsidian Tier 1
val HexSurfaceSubtleDark = Color(0xFF101014)     // Obsidian Tier 2
val HexSurfaceElevatedDark = Color(0xFF17171D)   // Obsidian Tier 3

// Hairline & Subtle Borders
val HexBorderDark = Color(0x1FFFFFFF)           // 12% white hairline
val HexBorderSubtleDark = Color(0x0FFFFFFF)     // 6% white divider

// Tactical & Expressive Accents (Approved Palette)
val HexPeriwinkle = Color(0xFF8795FF)           // Electric Periwinkle (Primary)
val HexViolet = Color(0xFF997BFA)               // Violet (E2EE / Noise)
val HexLavender = Color(0xFFB9A7FF)             // Soft Lavender (Pill highlights)
val HexIceBlue = Color(0xFF9ADCF5)              // Ice Blue (Telemetry / Nearby)
val HexMint = Color(0xFF91D9BD)                 // Mint (Delivered / Connected)
val HexCoral = Color(0xFFF2A69D)                // Coral (Alert / Error)

// Backward Compatibility Aliases
val HexAccentPrimary = HexPeriwinkle
val HexAccentHover = Color(0xFFA1ACFF)
val HexAccentSubtle = Color(0x338795FF)
val HexCyanPrimary = HexPeriwinkle
val HexCyanContainer = Color(0x268795FF)
val HexSuccessDirect = HexMint
val HexWarningRelay = HexLavender
val HexErrorAlert = HexCoral

// Liquid Glass Translucent Surfaces
val HexGlassFill = Color(0x0EFFFFFF)            // 5.5% translucent fill
val HexGlassBorder = Color(0x1FFFFFFF)          // 12% border
val HexGlassHighlight = Color(0x38FFFFFF)       // 22% specular rim

// Text Hierarchy — Pure Dark Mode Only
val HexTextPrimaryDark = Color(0xFFF5F5F7)
val HexTextSecondaryDark = Color(0xFFA1A1AA)
val HexTextMutedDark = Color(0xFF6B7280)

val HexnetDarkColorScheme = darkColorScheme(
    primary = HexPeriwinkle,
    onPrimary = Color(0xFF09090B),
    primaryContainer = HexAccentSubtle,
    onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = HexViolet,
    onSecondary = Color(0xFF09090B),
    secondaryContainer = Color(0x33997BFA),
    onSecondaryContainer = HexLavender,
    tertiary = HexIceBlue,
    background = HexBackgroundDark,
    onBackground = HexTextPrimaryDark,
    surface = HexBackgroundDark,
    onSurface = HexTextPrimaryDark,
    surfaceVariant = HexSurfaceSubtleDark,
    onSurfaceVariant = HexTextSecondaryDark,
    outline = HexBorderDark
)

val HexnetLightColorScheme = HexnetDarkColorScheme // Strict AMOLED Dark Mode only
