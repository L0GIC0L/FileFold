package io.github.l0gic0l.filefold.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.data.IconColorSettings
import io.github.l0gic0l.filefold.data.PaletteStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class FileColorsTest {

    private val seed = 0xFF3A5BA9.toInt()
    private val light = schemeFromSeed(seed, dark = false, PaletteStyle.TONAL_SPOT)
    private val dark = schemeFromSeed(seed, dark = true, PaletteStyle.TONAL_SPOT)

    private fun hueDistance(a: Double, b: Double) = min(abs(a - b), 360 - abs(a - b))

    @Test
    fun `every coloured kind gets its own tile colour`() {
        val colors = FileColors.build(light, IconColorSettings(harmonize = false), dark = false)
        val themed = setOf(FileKind.FOLDER, FileKind.TEXT, FileKind.OTHER)
        val tiles = FileKind.entries.filter { it !in themed }.map { colors[it].container }
        assertEquals(tiles.size, tiles.toSet().size)
        assertEquals(light.primaryContainer, colors[FileKind.FOLDER].container)
    }

    @Test
    fun `tiles are light in the light theme, dark in the dark theme, and icons contrast with them`() {
        val lightColors = FileColors.build(light, IconColorSettings(), dark = false)
        val darkColors = FileColors.build(dark, IconColorSettings(), dark = true)
        for (kind in FileKind.entries) {
            val l = lightColors[kind]
            val d = darkColors[kind]
            assertTrue("$kind light tile", l.container.luminance() > l.content.luminance())
            assertTrue("$kind dark tile", d.container.luminance() < d.content.luminance())
        }
    }

    @Test
    fun `a custom colour replaces the default`() {
        val red = Hct.from(25.0, 80.0, 50.0).toInt()
        val colors = FileColors.build(light, IconColorSettings(harmonize = false, custom = mapOf(FileKind.CODE to red)), dark = false)
        val hue = Hct.fromInt(colors[FileKind.CODE].container.toArgb()).hue
        assertTrue("hue $hue should be near 25", hueDistance(hue, 25.0) < 10)
    }

    @Test
    fun `harmonising moves colours toward the theme colour`() {
        val primaryHue = Hct.fromInt(light.primary.toArgb()).hue
        val plain = FileColors.build(light, IconColorSettings(harmonize = false), dark = false)
        val blended = FileColors.build(light, IconColorSettings(harmonize = true), dark = false)
        val kind = FileKind.AUDIO
        val before = hueDistance(Hct.fromInt(plain[kind].container.toArgb()).hue, primaryHue)
        val after = hueDistance(Hct.fromInt(blended[kind].container.toArgb()).hue, primaryHue)
        assertTrue("harmonised $after should be closer than $before", after < before)
    }

    @Test
    fun `seed schemes differ by style and keep text readable`() {
        val vibrant = schemeFromSeed(seed, dark = false, PaletteStyle.VIBRANT)
        assertNotEquals(light.primary, vibrant.primary)
        for (scheme in listOf(light, dark)) {
            val contrast = (maxOf(scheme.onSurface.luminance(), scheme.surface.luminance()) + 0.05) /
                (minOf(scheme.onSurface.luminance(), scheme.surface.luminance()) + 0.05)
            assertTrue("text contrast $contrast", contrast > 7)
        }
        assertEquals(Color.Black.copy(alpha = 1f).alpha, light.primary.alpha)
    }
}
