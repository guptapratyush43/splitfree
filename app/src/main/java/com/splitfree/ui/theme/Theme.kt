package com.splitfree.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Status colours that Material's scheme has no slot for. Exposed through a
 * CompositionLocal so screens read them the same way they read the scheme.
 */
data class StatusColors(
    val success: Color,
    val successSurface: Color,
    val warning: Color,
    val danger: Color,
    val owe: Color = OweLight
)

val LocalStatusColors = compositionLocalOf {
    StatusColors(SuccessLight, SuccessSurfaceLight, WarningLight, DangerLight)
}

private val LightScheme = lightColorScheme(
    primary = Clay,
    onPrimary = Color.White,
    primaryContainer = CreamRaised,
    onPrimaryContainer = ClayPressed,
    secondary = InkSecondary,
    onSecondary = Color.White,
    background = CreamBackground,
    onBackground = InkPrimary,
    surface = CreamSurface,
    onSurface = InkPrimary,
    surfaceVariant = CreamRaised,
    onSurfaceVariant = InkSecondary,
    outline = CreamOutline,
    outlineVariant = CreamOutline,
    error = DangerLight,
    onError = Color.White
)

private val DarkScheme = darkColorScheme(
    primary = ClayDark,
    onPrimary = Color(0xFF2B1710),
    primaryContainer = WarmRaised,
    onPrimaryContainer = ClayDark,
    secondary = ParchmentSecondary,
    onSecondary = Color(0xFF23221F),
    background = WarmBackground,
    onBackground = ParchmentPrimary,
    surface = WarmSurface,
    onSurface = ParchmentPrimary,
    surfaceVariant = WarmRaised,
    onSurfaceVariant = ParchmentSecondary,
    outline = WarmOutline,
    outlineVariant = WarmOutline,
    error = DangerDark,
    onError = Color(0xFF2B1512)
)

/**
 * Follows the OS setting only. There is deliberately no override or toggle:
 * [isSystemInDarkTheme] recomposes when the system flips, so the app tracks it
 * live, including a scheduled switch at dusk.
 *
 * Dynamic colour is not used — it would replace the warm palette with whatever
 * the device wallpaper suggests.
 */
@Composable
fun SplitTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) DarkScheme else LightScheme

    val statusColors = if (dark) {
        StatusColors(SuccessDark, SuccessSurfaceDark, WarningDark, DangerDark, OweDark)
    } else {
        StatusColors(SuccessLight, SuccessSurfaceLight, WarningLight, DangerLight)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography) {
            // Text and icons without an explicit colour follow the theme (light in dark mode), never plain black.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides scheme.onBackground,
                content = content
            )
        }
    }
}
