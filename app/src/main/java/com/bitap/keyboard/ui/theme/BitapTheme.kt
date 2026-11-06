package com.bitap.keyboard.ui.theme

import android.inputmethodservice.InputMethodService
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.bitap.keyboard.settings.ThemeFamily
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.glasmioColorScheme
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

// ---------------------------------------------------------------------------
// Palette tokens (docs/04-design-system.md, section 3)
// ---------------------------------------------------------------------------

private val MiuixAccentLight = Color(0xFF3482FF)
private val MiuixAccentDark = Color(0xFF5B9BFF)
private val GlasmioAccentLight = Color(0xFF0A84FF)
private val GlasmioAccentDark = Color(0xFF64D2FF)

/** Miuix "清透" light: warm paper white with a quiet blue accent. */
val MiuixLightPalette = BitapPalette(
    background = Color(0xFFF5F5F7),
    surface = Color(0xFFFFFFFF),
    elevatedSurface = Color(0xFFFFFFFF),
    key = Color(0xFFFFFFFF),
    keyPressed = Color(0xFFE8E8ED),
    functionKey = Color(0xFFE9E9EE),
    accent = MiuixAccentLight,
    onAccent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF111114),
    textSecondary = Color(0xFF6E6E73),
    hairline = Color(0x1A000000),
    danger = Color(0xFFFF3B30),
    glassHighlight = Color.Transparent,
    isGlass = false,
)

/** Miuix "清透" dark: ink black with graphite keycaps. */
val MiuixDarkPalette = BitapPalette(
    background = Color(0xFF111114),
    surface = Color(0xFF1C1C20),
    elevatedSurface = Color(0xFF242429),
    key = Color(0xFF2A2A2E),
    keyPressed = Color(0xFF3A3A3F),
    functionKey = Color(0xFF1F1F23),
    accent = MiuixAccentDark,
    onAccent = Color(0xFF081020),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFA5A5AC),
    hairline = Color(0x24FFFFFF),
    danger = Color(0xFFFF453A),
    glassHighlight = Color.Transparent,
    isGlass = false,
)

/** Glasmio "流光" light: translucent glass over a soft aurora. */
val GlasmioLightPalette = BitapPalette(
    // The aurora is painted by GlasmioBackdropHost behind the page; keep this token
    // transparent so the keyboard surface does not mask the gradient.
    background = Color.Transparent,
    surface = Color.White.copy(alpha = 0.45f),
    elevatedSurface = Color.White.copy(alpha = 0.64f),
    key = Color.White.copy(alpha = 0.38f),
    keyPressed = Color.White.copy(alpha = 0.55f),
    functionKey = Color.White.copy(alpha = 0.22f),
    accent = GlasmioAccentLight,
    onAccent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF0B0B0F),
    textSecondary = Color(0xFF4A4A52),
    hairline = Color(0x1F000000),
    danger = Color(0xFFFF453A),
    glassHighlight = Color.White.copy(alpha = 0.65f),
    isGlass = true,
)

/** Glasmio "流光" dark: deep space with dimmer glass so text stays readable. */
val GlasmioDarkPalette = BitapPalette(
    background = Color.Transparent,
    surface = Color.White.copy(alpha = 0.10f),
    elevatedSurface = Color.White.copy(alpha = 0.14f),
    key = Color.White.copy(alpha = 0.12f),
    keyPressed = Color.White.copy(alpha = 0.20f),
    functionKey = Color.White.copy(alpha = 0.08f),
    accent = GlasmioAccentDark,
    onAccent = Color(0xFF04121A),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFA8A8B2),
    hairline = Color(0x24FFFFFF),
    danger = Color(0xFFFF6961),
    glassHighlight = Color.White.copy(alpha = 0.35f),
    isGlass = true,
)

/**
 * Applies the selected theme family.
 *
 * Miuix: real [MiuixTheme] + [ThemeController] (ColorSchemeMode + explicit dark flag).
 * Glasmio: real [GlasmioTheme] + [GlasmioBackdropHost] wrapped around the page background.
 *
 * Both branches publish the matching [BitapPalette] through [LocalBitapPalette] so the
 * keyboard and the page share exactly the same tokens.
 */
@Composable
fun BitapTheme(
    themeFamily: ThemeFamily,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    when (themeFamily) {
        ThemeFamily.Miuix -> {
            val palette = if (darkTheme) MiuixDarkPalette else MiuixLightPalette
            val controller = remember(darkTheme) {
                ThemeController(
                    colorSchemeMode = ColorSchemeMode.System,
                    keyColor = palette.accent,
                    isDark = darkTheme,
                )
            }
            MiuixTheme(controller = controller) {
                MaterialTheme(colorScheme = materialColorScheme(palette, darkTheme)) {
                    CompositionLocalProvider(LocalBitapPalette provides palette) {
                        content()
                    }
                }
            }
        }

        ThemeFamily.Glasmio -> {
            val palette = if (darkTheme) GlasmioDarkPalette else GlasmioLightPalette
            val context = LocalContext.current
            val isIme = context is InputMethodService
            GlasmioTheme(
                isDark = darkTheme,
                colorScheme = glasmioColorScheme(isDark = darkTheme, accent = palette.accent),
            ) {
                CompositionLocalProvider(LocalBitapPalette provides palette) {
                    if (isIme) {
                        // The IME input view is measured WRAP_CONTENT; a full-screen backdrop
                        // host would stretch the keyboard. Match the keyboard column instead.
                        Box(modifier = Modifier.fillMaxWidth()) {
                            GlasmioAuroraBackdrop(
                                darkTheme = darkTheme,
                                modifier = Modifier.matchParentSize(),
                            )
                            content()
                        }
                    } else {
                        GlasmioBackdropHost(
                            modifier = Modifier.fillMaxSize(),
                            sampledContent = {
                                GlasmioAuroraBackdrop(
                                    darkTheme = darkTheme,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            },
                            overlayContent = { content() },
                        )
                    }
                }
            }
        }
    }
}

/** Material3 bridge so stock Material components inherit Bitap colors. */
private fun materialColorScheme(palette: BitapPalette, darkTheme: Boolean) =
    if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            error = palette.danger,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            error = palette.danger,
            onError = Color.White,
        )
    }

/**
 * Low-saturation aurora background sampled by Glasmio's backdrop host. Purely decorative:
 * accessibility services skip it (no semantics) and it never intercepts touch.
 */
@Composable
private fun GlasmioAuroraBackdrop(
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val baseTop = if (darkTheme) Color(0xFF070A14) else Color(0xFFF6F9FF)
    val baseMiddle = if (darkTheme) Color(0xFF101728) else Color(0xFFEDF2FF)
    val baseBottom = if (darkTheme) Color(0xFF1A1030) else Color(0xFFF9EFFF)

    Box(
        modifier = modifier
            .clearAndSetSemantics { }
            .background(Brush.verticalGradient(listOf(baseTop, baseMiddle, baseBottom))),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val blue = if (darkTheme) Color(0x443B82F6) else Color(0x3D3482FF)
            val violet = if (darkTheme) Color(0x3D8B5CF6) else Color(0x2E8B5CF6)
            val cyan = if (darkTheme) Color(0x3319C8E8) else Color(0x3319C8E8)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(blue, Color.Transparent),
                    center = Offset(w * 0.16f, h * 0.10f),
                    radius = w * 0.78f,
                ),
                radius = w * 0.78f,
                center = Offset(w * 0.16f, h * 0.10f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(violet, Color.Transparent),
                    center = Offset(w * 0.92f, h * 0.28f),
                    radius = w * 0.72f,
                ),
                radius = w * 0.72f,
                center = Offset(w * 0.92f, h * 0.28f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cyan, Color.Transparent),
                    center = Offset(w * 0.42f, h * 0.92f),
                    radius = w * 0.85f,
                ),
                radius = w * 0.85f,
                center = Offset(w * 0.42f, h * 0.92f),
            )
        }
    }
}
