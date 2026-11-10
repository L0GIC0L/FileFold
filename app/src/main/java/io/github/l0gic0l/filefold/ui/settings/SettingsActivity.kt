package io.github.l0gic0l.filefold.ui.settings

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.filefold.BuildConfig
import io.github.l0gic0l.filefold.core.AutoSortMode
import io.github.l0gic0l.filefold.core.AutoSorter
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.core.SortGroup
import io.github.l0gic0l.filefold.data.AppSettings
import io.github.l0gic0l.filefold.data.ColorSource
import io.github.l0gic0l.filefold.data.PaletteStyle
import io.github.l0gic0l.filefold.data.SettingsRepository
import io.github.l0gic0l.filefold.data.ThemeMode
import io.github.l0gic0l.filefold.data.rememberAppSettings
import io.github.l0gic0l.filefold.ui.components.KindBadge
import io.github.l0gic0l.filefold.ui.theme.FileColors
import io.github.l0gic0l.filefold.ui.theme.FileFoldTheme
import io.github.l0gic0l.filefold.ui.theme.LocalFileColors
import io.github.l0gic0l.filefold.ui.theme.schemeFromSeed
import kotlinx.coroutines.launch

/** Settings. A separate screen, so leaving it gets Android's own predictive back animation. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings = rememberAppSettings()
            val repo = remember { SettingsRepository(applicationContext) }
            if (settings != null) {
                FileFoldTheme(settings.theme, settings.iconColors) {
                    SettingsScreen(settings, repo, onBack = ::finish)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(settings: AppSettings, repo: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun save(block: suspend SettingsRepository.() -> Unit) {
        scope.launch { repo.block() }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var editingKind by remember { mutableStateOf<FileKind?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---------------------------------------------------------- appearance
            item { Section("Appearance") }
            item {
                Setting("Theme") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = settings.theme.mode == mode,
                                onClick = { save { setTheme(settings.theme.copy(mode = mode)) } },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                label = { Text(mode.label, maxLines = 1) },
                            )
                        }
                    }
                }
            }
            item {
                val wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                Setting("Colours", if (wallpaperAvailable) null else "Wallpaper colours need Android 12 or newer") {
                    ColorSource.entries.forEach { source ->
                        RadioRow(
                            label = if (source == ColorSource.WALLPAPER) "From your wallpaper (Material You)" else "Pick a colour",
                            selected = settings.theme.source == source,
                            enabled = source != ColorSource.WALLPAPER || wallpaperAvailable,
                        ) { save { setTheme(settings.theme.copy(source = source)) } }
                    }
                }
            }
            if (settings.theme.source == ColorSource.CUSTOM || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                item {
                    Setting("Colour") {
                        SwatchGrid(selected = settings.theme.seed) { save { setTheme(settings.theme.copy(seed = it)) } }
                    }
                }
                item {
                    Setting("Style") {
                        StylePicker(settings) { style -> save { setTheme(settings.theme.copy(style = style)) } }
                    }
                }
            }
            item {
                SwitchRow("Pure black", "Black backgrounds in the dark theme, easy on OLED screens", settings.theme.pureBlack) {
                    save { setTheme(settings.theme.copy(pureBlack = it)) }
                }
            }

            // ---------------------------------------------------------- file colours
            item { Section("File icons") }
            item {
                SwitchRow(
                    "Blend with theme",
                    "Tint every file colour toward your theme colour, Material You style",
                    settings.iconColors.harmonize,
                ) { save { setIconHarmonize(it) } }
            }
            item {
                Setting("Colours by type", "Tap a type to choose its colour") {
                    KindGrid(settings) { editingKind = it }
                    if (settings.iconColors.custom.isNotEmpty()) {
                        TextButton(onClick = { save { resetIconColors() } }) { Text("Reset all to defaults") }
                    }
                }
            }

            // ---------------------------------------------------------- browsing
            item { Section("Browsing") }
            item {
                SwitchRow("Show hidden files", "Files and folders whose names start with a dot", settings.showHidden) {
                    save { setShowHidden(it) }
                }
            }
            item {
                SwitchRow("Folders first", "List folders above files", settings.sort.foldersFirst) {
                    save { setSort(settings.sort.copy(foldersFirst = it)) }
                }
            }
            item { SwitchRow("Grid view", "Show files as tiles instead of a list", settings.gridView) { save { setGridView(it) } } }

            // ---------------------------------------------------------- editor
            item { Section("Text editor") }
            item { SwitchRow("Word wrap", "Wrap long lines to fit the screen", settings.editorWordWrap) { save { setEditorWordWrap(it) } } }
            item { SwitchRow("Line numbers", null, settings.editorLineNumbers) { save { setEditorLineNumbers(it) } } }
            item {
                Setting("Text size", "${settings.editorFontSize.toInt()} sp") {
                    Slider(
                        value = settings.editorFontSize,
                        onValueChange = { save { setEditorFontSize(it) } },
                        valueRange = 10f..24f,
                        steps = 13,
                    )
                }
            }

            // ---------------------------------------------------------- auto-sort
            item { Section("Auto-sort") }
            item { AutoSortSettings(settings) { config -> save { setAutoSort(config) } } }

            // ---------------------------------------------------------- about
            item { Section("About") }
            item {
                ListItem(
                    headlineContent = { Text("FileFold ${BuildConfig.VERSION_NAME}") },
                    supportingContent = { Text("A Material You file explorer for foldables") },
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
        }
    }

    editingKind?.let { kind ->
        ColorPickerDialog(
            title = kind.label,
            current = settings.iconColors.custom[kind] ?: FileColors.defaultSeed(kind),
            onDismiss = { editingKind = null },
            onReset = { save { setIconColor(kind, null) }; editingKind = null },
            onPick = { save { setIconColor(kind, it) }; editingKind = null },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

/** A titled setting with any control under it. */
@Composable
private fun Setting(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.widthIn(max = 720.dp).clickable(role = Role.Switch) { onChange(!checked) },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.size(12.dp))
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

/** Round colour swatches; the chosen one is ringed and ticked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchGrid(selected: Int?, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FileColors.swatches.forEach { argb ->
            val isSelected = argb == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .padding(4.dp)
                    .background(Color(argb), CircleShape)
                    .clickable(role = Role.RadioButton) { onPick(argb) }
                    .semantics { contentDescription = "Colour ${Integer.toHexString(argb).drop(2).uppercase()}" },
            ) {
                if (isSelected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The palette styles, each shown with its own preview of primary, secondary and tertiary. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StylePicker(settings: AppSettings, onPick: (PaletteStyle) -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaletteStyle.entries.forEach { style ->
            val preview = remember(settings.theme.seed, style, dark) { schemeFromSeed(settings.theme.seed, dark, style) }
            FilterChip(
                selected = settings.theme.style == style,
                onClick = { onPick(style) },
                label = { Text(style.label) },
                leadingIcon = {
                    Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                        listOf(preview.primary, preview.secondary, preview.tertiary).forEach {
                            Box(Modifier.size(14.dp).background(it, CircleShape))
                        }
                    }
                },
            )
        }
    }
}

private fun Color.luminanceIsDark(): Boolean = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5

/** Every kind of file with its tile, so the colours can be judged together. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindGrid(settings: AppSettings, onEdit: (FileKind) -> Unit) {
    LocalFileColors.current // read so the grid redraws as colours change
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FileKind.entries.forEach { kind ->
            Surface(
                onClick = { onEdit(kind) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.widthIn(min = 150.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    KindBadge(kind, size = 36.dp)
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text(kind.label, style = MaterialTheme.typography.labelLarge)
                        if (kind in settings.iconColors.custom) {
                            Text("Custom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorPickerDialog(title: String, current: Int?, onDismiss: () -> Unit, onReset: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { SwatchGrid(selected = current, onPick = onPick) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onReset) { Text("Default") } },
    )
}

/** Mode, what to sort and where to, and what to do with folders. */
@Composable
private fun AutoSortSettings(settings: AppSettings, onChange: (io.github.l0gic0l.filefold.core.AutoSortConfig) -> Unit) {
    val config = settings.autoSort
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Sort by", style = MaterialTheme.typography.bodyLarge)
        AutoSortMode.entries.forEach { mode ->
            RadioRow("${mode.label}: ${mode.description}", config.mode == mode) { onChange(config.copy(mode = mode)) }
        }
        Spacer(Modifier.height(12.dp))
        Text("Folders by type", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Untick a type to leave those files where they are. A name can be a path, like Sorted/Images, " +
                "and two types can share a folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SortGroup.entries.forEach { group ->
            val rule = config.rule(group)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Checkbox(checked = rule.enabled, onCheckedChange = {
                    onChange(config.copy(groups = config.groups + (group to rule.copy(enabled = it))))
                })
                FolderNameField(
                    value = rule.folder,
                    label = group.kinds.joinToString(", ") { it.label },
                    enabled = rule.enabled,
                    modifier = Modifier.weight(1f),
                ) { onChange(config.copy(groups = config.groups + (group to rule.copy(folder = it)))) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(config.copy(moveFolders = !config.moveFolders)) },
        ) {
            Column(Modifier.weight(1f)) {
                Text("Move folders too", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Tidy sub-folders into one folder. The folders files are sorted into are never moved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = config.moveFolders, onCheckedChange = null)
        }
        FolderNameField(
            value = config.foldersTarget,
            label = "Folder for folders",
            enabled = config.moveFolders,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { onChange(config.copy(foldersTarget = it)) }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(config.copy(includeHidden = !config.includeHidden)) },
        ) {
            Text("Include hidden files", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = config.includeHidden, onCheckedChange = null)
        }
    }
}

/**
 * A folder-name field that flags names a phone cannot store. It keeps its own text while you
 * type and saves each change, so the saved value catching up never moves the cursor.
 */
@Composable
private fun FolderNameField(value: String, label: String, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    val invalid = enabled && AutoSorter.cleanPath(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        enabled = enabled,
        isError = invalid,
        supportingText = if (invalid) { { Text("Not a valid folder name; the default is used") } } else null,
        modifier = modifier,
    )
}
