package io.github.l0gic0l.filefold.ui.detail

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.l0gic0l.filefold.R
import io.github.l0gic0l.filefold.ui.browser.BrowserViewModel
import io.github.l0gic0l.filefold.ui.components.TabletopLayout
import io.github.l0gic0l.filefold.util.Launch
import kotlinx.coroutines.launch

/**
 * The built-in text editor. In tabletop posture the text sits on the upright
 * half and every control moves to the half lying flat, like a small laptop.
 */
@Composable
fun TextEditorScreen(vm: BrowserViewModel, editor: EditorSession, showBack: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmLeave by remember { mutableStateOf(false) }
    val save: () -> Unit = { if (editor.isDirty) scope.launch { vm.saveEditor(editor) } }
    DisposableEffect(editor) {
        editor.onSaveShortcut = save
        onDispose { editor.onSaveShortcut = null }
    }

    // On a folded phone, back would close the editor: offer to save first.
    BackHandler(enabled = showBack && editor.isDirty) { confirmLeave = true }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Save changes?") },
            text = { Text("\"${editor.file.name}\" has unsaved changes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch { if (vm.saveEditor(editor)) onBack() }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
                    TextButton(onClick = { confirmLeave = false; editor.revert(); onBack() }) { Text("Discard") }
                }
            },
        )
    }

    val settings = vm.settings
    Surface(
        color = MaterialTheme.colorScheme.surface,
        // Shortcuts while the find box has focus; the editor view handles them itself otherwise.
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
            when (event.key) {
                Key.S -> { save(); true }
                Key.F -> { editor.findVisible = true; true }
                Key.G -> { editor.find(forward = !event.isShiftPressed); true }
                else -> false
            }
        },
    ) {
        TabletopLayout(
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            top = { tabletop ->
                Column(Modifier.fillMaxSize()) {
                    if (!tabletop) {
                        EditorTopBar(vm, editor, showBack, onBack, save, inTabletopControls = false)
                        if (editor.findVisible) FindBar(editor)
                    }
                    EditorBody(
                        editor = editor,
                        wordWrap = settings.editorWordWrap,
                        lineNumbers = settings.editorLineNumbers,
                        fontSize = settings.editorFontSize,
                        modifier = Modifier.weight(1f),
                    )
                }
            },
            bottom = { tabletop ->
                Column {
                    if (tabletop) {
                        EditorTopBar(vm, editor, showBack, onBack, save, inTabletopControls = true)
                        if (editor.findVisible) FindBar(editor)
                    }
                    StatusBar(editor)
                }
            },
        )
    }
}

@Composable
private fun EditorTopBar(
    vm: BrowserViewModel,
    editor: EditorSession,
    showBack: Boolean,
    onBack: () -> Unit,
    save: () -> Unit,
    inTabletopControls: Boolean,
) {
    val context = LocalContext.current
    val settings = vm.settings
    DetailTopBar(
        title = (if (editor.isDirty) "• " else "") + editor.file.name,
        subtitle = if (editor.readOnly) "Read only" else null,
        showBack = showBack,
        onBack = onBack,
        insideTabletopControls = inTabletopControls,
    ) {
        IconButton(onClick = { if (editor.findVisible) editor.closeFind() else editor.findVisible = true }) {
            Icon(Icons.Default.FindReplace, "Find and replace")
        }
        if (!editor.readOnly) {
            IconButton(onClick = save, enabled = editor.isDirty && !editor.saving) {
                if (editor.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Save, "Save")
            }
        }
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Editor options") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Word wrap") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.WrapText, null) },
                    trailingIcon = { Checkbox(settings.editorWordWrap, onCheckedChange = null) },
                    onClick = { vm.setEditorWordWrap(!settings.editorWordWrap) },
                )
                DropdownMenuItem(
                    text = { Text("Line numbers") },
                    leadingIcon = { Icon(Icons.Default.FormatListNumbered, null) },
                    trailingIcon = { Checkbox(settings.editorLineNumbers, onCheckedChange = null) },
                    onClick = { vm.setEditorLineNumbers(!settings.editorLineNumbers) },
                )
                DropdownMenuItem(
                    text = { Text("Larger text") },
                    leadingIcon = { Icon(Icons.Default.TextIncrease, null) },
                    onClick = { vm.setEditorFontSize(settings.editorFontSize + 1) },
                )
                DropdownMenuItem(
                    text = { Text("Smaller text") },
                    leadingIcon = { Icon(Icons.Default.TextDecrease, null) },
                    onClick = { vm.setEditorFontSize(settings.editorFontSize - 1) },
                )
                HorizontalDivider()
                if (editor.isDirty) {
                    DropdownMenuItem(
                        text = { Text("Discard changes") },
                        leadingIcon = { Icon(Icons.Default.Restore, null) },
                        onClick = { open = false; editor.revert() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Open with…") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                    onClick = { open = false; Launch.openWith(context, editor.file) },
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = { open = false; Launch.share(context, listOf(editor.file)) },
                )
            }
        }
    }
}

/**
 * The text itself: Android's native editor, which stays fast with large files
 * because it re-lays out only the paragraph being edited.
 */
@Composable
private fun EditorBody(editor: EditorSession, wordWrap: Boolean, lineNumbers: Boolean, fontSize: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    editor.highlightColor = colors.tertiaryContainer.toArgb()
    // A fresh view per file, so each session attaches to its own.
    key(editor) {
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.code_editor, null) as CodeEditText).also(editor::attach)
            },
            update = { view ->
                view.setColors(
                    text = colors.onSurface.toArgb(),
                    gutter = colors.surfaceContainerLow.toArgb(),
                    numbers = colors.outline.toArgb(),
                    highlight = colors.primary.copy(alpha = 0.3f).toArgb(),
                    cursor = colors.primary.toArgb(),
                )
                view.setEditorTextSize(fontSize)
                view.lineNumbers = lineNumbers
                view.setHorizontallyScrolling(!wordWrap)
            },
            onRelease = { view -> editor.detach(view) },
            modifier = modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FindBar(editor: EditorSession) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactField(
                    value = editor.findQuery,
                    onValueChange = editor::updateFindQuery,
                    placeholder = "Find",
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    imeAction = ImeAction.Search,
                    onAction = { editor.find() },
                )
                Text(
                    if (editor.findQuery.isEmpty()) "" else "${editor.matchCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                IconButton(onClick = { editor.find(forward = false) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
                IconButton(onClick = { editor.find(forward = true) }) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
                IconButton(onClick = editor::closeFind) { Icon(Icons.Default.Close, "Close find") }
            }
            if (!editor.readOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactField(
                        value = editor.replaceQuery,
                        onValueChange = { editor.replaceQuery = it },
                        placeholder = "Replace with",
                        modifier = Modifier.weight(1f),
                        imeAction = ImeAction.Done,
                        onAction = editor::replaceCurrent,
                    )
                    TextButton(onClick = editor::replaceCurrent) { Text("Replace") }
                    TextButton(onClick = { editor.replaceAll() }) { Text("All") }
                }
            }
            FilterChip(selected = editor.matchCase, onClick = editor::toggleMatchCase, label = { Text("Match case") })
        }
    }
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    imeAction: ImeAction,
    onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onAction() }),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(colors.surfaceContainerHighest, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                inner()
            }
        },
    )
}

/** Undo and redo plus where the cursor is and how the file is stored. */
@Composable
private fun StatusBar(editor: EditorSession) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            if (editor.readOnly) {
                Icon(Icons.Default.EditOff, null, Modifier.padding(12.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                IconButton(onClick = editor::undo, enabled = editor.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = editor::redo, enabled = editor.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            }
            Spacer(Modifier.weight(1f))
            val position = if (editor.loadProgress < 1f) {
                "Loading ${(editor.loadProgress * 100).toInt()}%"
            } else {
                "Ln ${editor.cursorLine}, Col ${editor.cursorColumn}"
            }
            Text(
                "$position · ${editor.lineCount} lines · ${editor.encodingLabel} · ${editor.lineEndingLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}
