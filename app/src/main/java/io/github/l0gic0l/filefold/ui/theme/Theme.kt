package io.github.l0gic0l.filefold.ui.theme

import android.app.Activity
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import io.github.l0gic0l.filefold.data.ColorSource
import io.github.l0gic0l.filefold.data.IconColorSettings
import io.github.l0gic0l.filefold.data.PaletteStyle
import io.github.l0gic0l.filefold.data.ThemeMode
import io.github.l0gic0l.filefold.data.ThemeSettings

/** Whether the theme settings call for the dark scheme right now. */
@Composable
fun ThemeSettings.isDark(): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Material You: colours come from the wallpaper (Android 12+), or from a colour
 * the user picks, spread into a full scheme with Google's Material colour
 * algorithm in the chosen style. File-type colours ride along in [LocalFileColors].
 */
@Composable
fun FileFoldTheme(
    theme: ThemeSettings = ThemeSettings(),
    iconColors: IconColorSettings = IconColorSettings(),
    content: @Composable () -> Unit,
) {
    val dark = theme.isDark()
    val context = LocalContext.current
    val colors = remember(theme, dark) {
        val base = if (theme.source == ColorSource.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            schemeFromSeed(theme.seed, dark, theme.style)
        }
        if (dark && theme.pureBlack) base.toPureBlack() else base
    }
    val fileColors = remember(colors, iconColors, dark) { FileColors.build(colors, iconColors, dark) }

    // Status and navigation bar icons follow the app's theme, not only the system's.
    val activity = context as? Activity
    DisposableEffect(dark, activity) {
        (activity as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        onDispose { }
    }

    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalFileColors provides fileColors, content = content)
    }
}

// The default translucent scrims Android uses behind three-button navigation (ARGB).
private const val LIGHT_SCRIM = 0xE6FFFFFF.toInt()
private const val DARK_SCRIM = 0x801B1B1B.toInt()

/** A complete Material 3 colour scheme generated from one colour. */
fun schemeFromSeed(seed: Int, dark: Boolean, style: PaletteStyle): ColorScheme {
    val source = Hct.fromInt(seed)
    val scheme: DynamicScheme = when (style) {
        PaletteStyle.TONAL_SPOT -> SchemeTonalSpot(source, dark, 0.0)
        PaletteStyle.VIBRANT -> SchemeVibrant(source, dark, 0.0)
        PaletteStyle.EXPRESSIVE -> SchemeExpressive(source, dark, 0.0)
        PaletteStyle.FIDELITY -> SchemeFidelity(source, dark, 0.0)
        PaletteStyle.NEUTRAL -> SchemeNeutral(source, dark, 0.0)
        PaletteStyle.MONOCHROME -> SchemeMonochrome(source, dark, 0.0)
    }
    val roles = MaterialDynamicColors()
    fun DynamicColor.color() = Color(getArgb(scheme))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = roles.primary().color(),
        onPrimary = roles.onPrimary().color(),
        primaryContainer = roles.primaryContainer().color(),
        onPrimaryContainer = roles.onPrimaryContainer().color(),
        inversePrimary = roles.inversePrimary().color(),
        secondary = roles.secondary().color(),
        onSecondary = roles.onSecondary().color(),
        secondaryContainer = roles.secondaryContainer().color(),
        onSecondaryContainer = roles.onSecondaryContainer().color(),
        tertiary = roles.tertiary().color(),
        onTertiary = roles.onTertiary().color(),
        tertiaryContainer = roles.tertiaryContainer().color(),
        onTertiaryContainer = roles.onTertiaryContainer().color(),
        background = roles.background().color(),
        onBackground = roles.onBackground().color(),
        surface = roles.surface().color(),
        onSurface = roles.onSurface().color(),
        surfaceVariant = roles.surfaceVariant().color(),
        onSurfaceVariant = roles.onSurfaceVariant().color(),
        surfaceTint = roles.surfaceTint().color(),
        inverseSurface = roles.inverseSurface().color(),
        inverseOnSurface = roles.inverseOnSurface().color(),
        error = roles.error().color(),
        onError = roles.onError().color(),
        errorContainer = roles.errorContainer().color(),
        onErrorContainer = roles.onErrorContainer().color(),
        outline = roles.outline().color(),
        outlineVariant = roles.outlineVariant().color(),
        scrim = roles.scrim().color(),
        surfaceBright = roles.surfaceBright().color(),
        surfaceDim = roles.surfaceDim().color(),
        surfaceContainer = roles.surfaceContainer().color(),
        surfaceContainerHigh = roles.surfaceContainerHigh().color(),
        surfaceContainerHighest = roles.surfaceContainerHighest().color(),
        surfaceContainerLow = roles.surfaceContainerLow().color(),
        surfaceContainerLowest = roles.surfaceContainerLowest().color(),
    )
}

/** True black backgrounds, with containers darkened but still tinted so bars and cards stay distinct. */
private fun ColorScheme.toPureBlack(): ColorScheme {
    fun Color.darker() = lerp(this, Color.Black, 0.55f)
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = surfaceContainerLow.darker(),
        surfaceContainer = surfaceContainer.darker(),
        surfaceContainerHigh = surfaceContainerHigh.darker(),
        surfaceContainerHighest = surfaceContainerHighest.darker(),
    )
}
