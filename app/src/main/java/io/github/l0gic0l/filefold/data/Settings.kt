package io.github.l0gic0l.filefold.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.l0gic0l.filefold.core.AutoSortConfig
import io.github.l0gic0l.filefold.core.AutoSortMode
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.core.GroupRule
import io.github.l0gic0l.filefold.core.SortGroup
import io.github.l0gic0l.filefold.core.SortOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

/** Where the app's colours come from. */
enum class ColorSource(val label: String) { WALLPAPER("Wallpaper"), CUSTOM("Custom colour") }

/** How a custom colour is spread into a full Material scheme (the same styles Android offers for wallpapers). */
enum class PaletteStyle(val label: String) {
    TONAL_SPOT("Tonal"),
    VIBRANT("Vibrant"),
    EXPRESSIVE("Expressive"),
    FIDELITY("Faithful"),
    NEUTRAL("Neutral"),
    MONOCHROME("Monochrome"),
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val source: ColorSource = ColorSource.WALLPAPER,
    /** ARGB seed for [ColorSource.CUSTOM], and the fallback on devices without wallpaper colours. */
    val seed: Int = DEFAULT_SEED,
    val style: PaletteStyle = PaletteStyle.TONAL_SPOT,
    /** True black backgrounds in the dark theme, kind to OLED screens. */
    val pureBlack: Boolean = false,
) {
    companion object {
        const val DEFAULT_SEED: Int = 0xFF3A5BA9.toInt()
    }
}

data class IconColorSettings(
    /** Nudge every file colour toward the theme's, so they sit together (Material colour harmonisation). */
    val harmonize: Boolean = true,
    /** Colours chosen by the user, as ARGB, replacing the defaults for those kinds. */
    val custom: Map<FileKind, Int> = emptyMap(),
)

data class AppSettings(
    val sort: SortOptions = SortOptions(),
    val showHidden: Boolean = false,
    val gridView: Boolean = false,
    val editorWordWrap: Boolean = true,
    val editorLineNumbers: Boolean = true,
    val editorFontSize: Float = 14f,
    val theme: ThemeSettings = ThemeSettings(),
    val iconColors: IconColorSettings = IconColorSettings(),
    val autoSort: AutoSortConfig = AutoSortConfig(),
)

/** Open tabs as saved strings (see Location.encode) and which one was active. */
data class SavedTabs(val tabs: List<String>, val active: Int)

private val Context.dataStore by preferencesDataStore(name = "settings")

/** User preferences, persisted with DataStore so they survive restarts. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val sortBy = stringPreferencesKey("sort_by")
        val sortAscending = booleanPreferencesKey("sort_ascending")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val showHidden = booleanPreferencesKey("show_hidden")
        val gridView = booleanPreferencesKey("grid_view")
        val wordWrap = booleanPreferencesKey("editor_word_wrap")
        val lineNumbers = booleanPreferencesKey("editor_line_numbers")
        val fontSize = floatPreferencesKey("editor_font_size")
        val tabs = stringPreferencesKey("tabs")
        val activeTab = intPreferencesKey("active_tab")

        val themeMode = stringPreferencesKey("theme_mode")
        val colorSource = stringPreferencesKey("color_source")
        val seed = intPreferencesKey("seed_color")
        val paletteStyle = stringPreferencesKey("palette_style")
        val pureBlack = booleanPreferencesKey("pure_black")

        val iconHarmonize = booleanPreferencesKey("icon_harmonize")
        fun iconColor(kind: FileKind) = intPreferencesKey("icon_color_${kind.name.lowercase()}")

        val sortMode = stringPreferencesKey("autosort_mode")
        val sortHidden = booleanPreferencesKey("autosort_hidden")
        val sortMoveFolders = booleanPreferencesKey("autosort_move_folders")
        val sortFoldersTarget = stringPreferencesKey("autosort_folders_target")
        fun groupEnabled(group: SortGroup) = booleanPreferencesKey("autosort_${group.name.lowercase()}_enabled")
        fun groupFolder(group: SortGroup) = stringPreferencesKey("autosort_${group.name.lowercase()}_folder")
    }

    private inline fun <reified T : Enum<T>> Preferences.enum(key: Preferences.Key<String>, default: T): T =
        this[key]?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val d = AppSettings()
        AppSettings(
            sort = SortOptions(
                by = prefs.enum(Keys.sortBy, d.sort.by),
                ascending = prefs[Keys.sortAscending] ?: d.sort.ascending,
                foldersFirst = prefs[Keys.foldersFirst] ?: d.sort.foldersFirst,
            ),
            showHidden = prefs[Keys.showHidden] ?: d.showHidden,
            gridView = prefs[Keys.gridView] ?: d.gridView,
            editorWordWrap = prefs[Keys.wordWrap] ?: d.editorWordWrap,
            editorLineNumbers = prefs[Keys.lineNumbers] ?: d.editorLineNumbers,
            editorFontSize = prefs[Keys.fontSize] ?: d.editorFontSize,
            theme = ThemeSettings(
                mode = prefs.enum(Keys.themeMode, d.theme.mode),
                source = prefs.enum(Keys.colorSource, d.theme.source),
                seed = prefs[Keys.seed] ?: d.theme.seed,
                style = prefs.enum(Keys.paletteStyle, d.theme.style),
                pureBlack = prefs[Keys.pureBlack] ?: d.theme.pureBlack,
            ),
            iconColors = IconColorSettings(
                harmonize = prefs[Keys.iconHarmonize] ?: d.iconColors.harmonize,
                custom = FileKind.entries.mapNotNull { kind -> prefs[Keys.iconColor(kind)]?.let { kind to it } }.toMap(),
            ),
            autoSort = AutoSortConfig(
                mode = prefs.enum(Keys.sortMode, d.autoSort.mode),
                groups = SortGroup.entries.associateWith { group ->
                    GroupRule(
                        enabled = prefs[Keys.groupEnabled(group)] ?: true,
                        folder = prefs[Keys.groupFolder(group)] ?: group.defaultFolder,
                    )
                },
                moveFolders = prefs[Keys.sortMoveFolders] ?: d.autoSort.moveFolders,
                foldersTarget = prefs[Keys.sortFoldersTarget] ?: d.autoSort.foldersTarget,
                includeHidden = prefs[Keys.sortHidden] ?: d.autoSort.includeHidden,
            ),
        )
    }

    /** Kept apart from [settings] so that saving tabs on every navigation does not re-emit settings. */
    val savedTabs: Flow<SavedTabs> = context.dataStore.data.map { prefs ->
        SavedTabs(prefs[Keys.tabs]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty(), prefs[Keys.activeTab] ?: 0)
    }

    suspend fun setTabs(tabs: List<String>, active: Int) = edit {
        it[Keys.tabs] = tabs.joinToString("\n")
        it[Keys.activeTab] = active
    }

    suspend fun setSort(sort: SortOptions) = edit {
        it[Keys.sortBy] = sort.by.name
        it[Keys.sortAscending] = sort.ascending
        it[Keys.foldersFirst] = sort.foldersFirst
    }

    suspend fun setShowHidden(value: Boolean) = edit { it[Keys.showHidden] = value }
    suspend fun setGridView(value: Boolean) = edit { it[Keys.gridView] = value }
    suspend fun setEditorWordWrap(value: Boolean) = edit { it[Keys.wordWrap] = value }
    suspend fun setEditorLineNumbers(value: Boolean) = edit { it[Keys.lineNumbers] = value }
    suspend fun setEditorFontSize(value: Float) = edit { it[Keys.fontSize] = value.coerceIn(8f, 32f) }

    suspend fun setTheme(theme: ThemeSettings) = edit {
        it[Keys.themeMode] = theme.mode.name
        it[Keys.colorSource] = theme.source.name
        it[Keys.seed] = theme.seed
        it[Keys.paletteStyle] = theme.style.name
        it[Keys.pureBlack] = theme.pureBlack
    }

    suspend fun setIconHarmonize(value: Boolean) = edit { it[Keys.iconHarmonize] = value }

    /** Sets a file kind's colour, or goes back to the default with null. */
    suspend fun setIconColor(kind: FileKind, argb: Int?) = edit {
        if (argb == null) it.remove(Keys.iconColor(kind)) else it[Keys.iconColor(kind)] = argb
    }

    suspend fun resetIconColors() = edit { prefs -> FileKind.entries.forEach { prefs.remove(Keys.iconColor(it)) } }

    suspend fun setAutoSort(config: AutoSortConfig) = edit {
        it[Keys.sortMode] = config.mode.name
        it[Keys.sortHidden] = config.includeHidden
        it[Keys.sortMoveFolders] = config.moveFolders
        it[Keys.sortFoldersTarget] = config.foldersTarget
        SortGroup.entries.forEach { group ->
            val rule = config.rule(group)
            it[Keys.groupEnabled(group)] = rule.enabled
            it[Keys.groupFolder(group)] = rule.folder
        }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}

/** The saved settings, or null for the moment before they have been read. */
@Composable
fun rememberAppSettings(): AppSettings? {
    val context = LocalContext.current.applicationContext
    val settings = remember { SettingsRepository(context).settings }
    return settings.collectAsStateWithLifecycle<AppSettings?>(initialValue = null).value
}
