package io.github.l0gic0l.filefold.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.l0gic0l.filefold.core.SortBy
import io.github.l0gic0l.filefold.core.SortOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val sort: SortOptions = SortOptions(),
    val showHidden: Boolean = false,
    val gridView: Boolean = false,
    val editorWordWrap: Boolean = true,
    val editorLineNumbers: Boolean = true,
    val editorFontSize: Float = 14f,
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
    }

    /** Kept apart from [settings] so that saving tabs on every navigation does not re-emit settings. */
    val savedTabs: Flow<SavedTabs> = context.dataStore.data.map { prefs ->
        SavedTabs(prefs[Keys.tabs]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty(), prefs[Keys.activeTab] ?: 0)
    }

    suspend fun setTabs(tabs: List<String>, active: Int) = edit {
        it[Keys.tabs] = tabs.joinToString("\n")
        it[Keys.activeTab] = active
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            sort = SortOptions(
                by = prefs[Keys.sortBy]?.let { name -> SortBy.entries.firstOrNull { it.name == name } } ?: defaults.sort.by,
                ascending = prefs[Keys.sortAscending] ?: defaults.sort.ascending,
                foldersFirst = prefs[Keys.foldersFirst] ?: defaults.sort.foldersFirst,
            ),
            showHidden = prefs[Keys.showHidden] ?: defaults.showHidden,
            gridView = prefs[Keys.gridView] ?: defaults.gridView,
            editorWordWrap = prefs[Keys.wordWrap] ?: defaults.editorWordWrap,
            editorLineNumbers = prefs[Keys.lineNumbers] ?: defaults.editorLineNumbers,
            editorFontSize = prefs[Keys.fontSize] ?: defaults.editorFontSize,
        )
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
    suspend fun setEditorFontSize(value: Float) = edit { it[Keys.fontSize] = value }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
