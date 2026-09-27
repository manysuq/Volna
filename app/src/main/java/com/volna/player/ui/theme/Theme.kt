@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.volna.player.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = CoralSecondary,
    onSecondary = CoralOnSecondary,
    secondaryContainer = CoralSecondaryContainer,
    onSecondaryContainer = CoralOnSecondaryContainer,
    tertiary = VioletTertiary,
    onTertiary = VioletOnTertiary,
    tertiaryContainer = VioletTertiaryContainer,
    onTertiaryContainer = VioletOnTertiaryContainer,
    error = ErrorRed,
    onError = OnErrorRed,
    errorContainer = ErrorContainerRed,
    onErrorContainer = OnErrorContainerRed,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    surfaceContainerLowest = ContainerLowestLight,
    surfaceContainerLow = ContainerLowLight,
    surfaceContainer = ContainerLight,
    surfaceContainerHigh = ContainerHighLight,
    surfaceContainerHighest = ContainerHighestLight,
)

private val DarkColors = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = TealPrimaryContainerDark,
    onPrimaryContainer = TealPrimaryContainer,
    secondary = CoralSecondaryDark,
    onSecondary = CoralOnSecondaryContainer,
    secondaryContainer = CoralSecondaryContainerDark,
    onSecondaryContainer = CoralSecondaryContainer,
    tertiary = VioletTertiaryDark,
    onTertiary = VioletOnTertiaryContainer,
    tertiaryContainer = VioletTertiaryContainer,
    onTertiaryContainer = VioletTertiaryDark,
    error = Color(0xFFF2B8B5),
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorContainerRed,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    surfaceContainerLowest = ContainerLowestDark,
    surfaceContainerLow = ContainerLowDark,
    surfaceContainer = ContainerDark,
    surfaceContainerHigh = ContainerHighDark,
    surfaceContainerHighest = ContainerHighestDark,
)

/**
 * Формы в духе Material 3 Expressive: крупные, скруглённые,
 * с выделенным радиусом у крупных элементов.
 */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Режим оформления: как выбрана тема. */
enum class ThemeMode { System, Light, Dark;

    companion object {
        private const val PREFS = "ytdl_prefs"
        private const val KEY = "theme_mode"

        /** Читаем сохранённый выбор, по умолчанию — тёмная (комфортнее вечером). */
        fun load(context: android.content.Context): ThemeMode {
            val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return Dark
            return entries.firstOrNull { it.name == raw } ?: Dark
        }

        fun save(context: android.content.Context, mode: ThemeMode) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.name).apply()
        }
    }
}

/**
 * Тема приложения на Material 3 Expressive.
 *
 * По умолчанию тёмная: она спокойнее для глаз и не бликует. Светлая тоже
 * приглушённая — не чисто белая, а холодный серо-бирюзовый.
 */
@Composable
fun VolnaTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        shapes = ExpressiveShapes,
        content = content,
    )
}
