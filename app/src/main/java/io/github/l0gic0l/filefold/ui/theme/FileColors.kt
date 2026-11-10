package io.github.l0gic0l.filefold.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.blend.Blend
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.data.IconColorSettings

/** Tile and icon colour for one kind of file. */
@Immutable
data class KindColors(val container: Color, val content: Color)

/**
 * Colours for every kind of file. Each kind has its own hue around the colour
 * wheel; the tile and icon are tones of that hue picked the way Material picks
 * container colours, so they read well in light and dark themes alike.
 */
@Immutable
class FileColors(private val colors: Map<FileKind, KindColors>) {

    operator fun get(kind: FileKind): KindColors = colors.getValue(kind)

    companion object {
        /** Default colour of each kind, as a hue (HCT degrees) and chroma; null means use the theme's own colours. */
        private val defaultHues: Map<FileKind, Pair<Double, Double>?> = mapOf(
            FileKind.FOLDER to null,
            FileKind.IMAGE to (300.0 to 52.0),
            FileKind.DESIGN to (335.0 to 56.0),
            FileKind.VIDEO to (45.0 to 58.0),
            FileKind.AUDIO to (70.0 to 56.0),
            FileKind.PDF to (20.0 to 62.0),
            FileKind.DOCUMENT to (260.0 to 52.0),
            FileKind.SPREADSHEET to (150.0 to 48.0),
            FileKind.PRESENTATION to (85.0 to 54.0),
            FileKind.EBOOK to (60.0 to 26.0),
            FileKind.TEXT to null,
            FileKind.CODE to (195.0 to 44.0),
            FileKind.WEB to (180.0 to 44.0),
            FileKind.DATA to (275.0 to 44.0),
            FileKind.SUBTITLE to (115.0 to 46.0),
            FileKind.ARCHIVE to (100.0 to 56.0),
            FileKind.DISK_IMAGE to (225.0 to 16.0),
            FileKind.APK to (135.0 to 52.0),
            FileKind.EXECUTABLE to (10.0 to 30.0),
            FileKind.FONT to (315.0 to 40.0),
            FileKind.DATABASE to (210.0 to 26.0),
            FileKind.CONTACT to (165.0 to 40.0),
            FileKind.CALENDAR to (0.0 to 50.0),
            FileKind.MODEL_3D to (285.0 to 46.0),
            FileKind.TORRENT to (125.0 to 40.0),
            FileKind.KEY to (240.0 to 40.0),
            FileKind.OTHER to null,
        )

        /** The default colour of [kind] as ARGB, or null when it follows the theme. */
        fun defaultSeed(kind: FileKind): Int? =
            defaultHues[kind]?.let { (hue, chroma) -> Hct.from(hue, chroma, 55.0).toInt() }

        /** Colours on offer in the picker: a spread of hues, then two neutrals. */
        val swatches: List<Int> = (0 until 18).map { Hct.from(it * 20.0, 60.0, 55.0).toInt() } +
            listOf(Hct.from(250.0, 8.0, 55.0).toInt(), Hct.from(60.0, 12.0, 45.0).toInt())

        fun build(scheme: ColorScheme, settings: IconColorSettings, dark: Boolean): FileColors {
            val primary = scheme.primary.toArgb()
            return FileColors(
                FileKind.entries.associateWith { kind ->
                    val seed = settings.custom[kind] ?: defaultSeed(kind)
                    when {
                        seed != null -> {
                            // Harmonising turns each hue slightly toward the theme's, so the set looks like it belongs.
                            val hue = if (settings.harmonize) Blend.harmonize(seed, primary) else seed
                            val palette = TonalPalette.fromInt(hue)
                            if (dark) KindColors(Color(palette.tone(30)), Color(palette.tone(88)))
                            else KindColors(Color(palette.tone(90)), Color(palette.tone(35)))
                        }
                        kind == FileKind.FOLDER -> KindColors(scheme.primaryContainer, scheme.onPrimaryContainer)
                        else -> KindColors(scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
                    }
                },
            )
        }
    }
}

val LocalFileColors = staticCompositionLocalOf { FileColors.build(lightColorScheme(), IconColorSettings(), dark = false) }
