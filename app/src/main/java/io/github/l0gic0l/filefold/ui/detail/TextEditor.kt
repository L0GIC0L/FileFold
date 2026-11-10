package io.github.l0gic0l.filefold.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val save: () -> Unit = { scope.launch { vm.saveEditor(editor) } }

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
        modifier = Modifier.onPreviewKeyEvent { event ->
            // Hardware keyboard shortcuts, handy on an unfolded device with a keyboard attached.
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
            when (event.key) {
                Key.S -> { if (editor.isDirty) save(); true }
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
        IconButton(onClick = { editor.findVisible = !editor.findVisible }) { Icon(Icons.Default.FindReplace, "Find and replace") }
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
 * The text itself, with an optional line-number gutter. Without word wrap the
 * text scrolls sideways and the gutter stays put.
 */
@Composable
private fun EditorBody(editor: EditorSession, wordWrap: Boolean, lineNumbers: Boolean, fontSize: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = remember(fontSize, colors.onSurface) {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.45f).sp, color = colors.onSurface)
    }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    // Read only while drawing the gutter, so a new layout redraws it without recomposing the editor.
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember { mutableStateOf(0) }

    // Bring a find result into view.
    LaunchedEffect(editor.scrollRequest) {
        val offset = editor.scrollRequest
        val result = layout.value
        if (offset < 0 || result == null || offset > result.layoutInput.text.length) return@LaunchedEffect
        val line = result.getLineForOffset(offset)
        val target = (result.getLineTop(line) - viewportHeight / 3f).toInt().coerceAtLeast(0)
        verticalScroll.animateScrollTo(target)
        if (!wordWrap) horizontalScroll.animateScrollTo((result.getHorizontalPosition(offset, true) - 48f).toInt().coerceAtLeast(0))
        editor.scrollRequest = -1
    }

    Row(modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (lineNumbers) LineNumberGutter(editor, layout, verticalScroll, style)
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            viewportHeight = constraints.maxHeight
            val viewportWidth = maxWidth
            Box(if (wordWrap) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(horizontalScroll)) {
                BasicTextField(
                    state = editor.state,
                    readOnly = editor.readOnly,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    scrollState = verticalScroll,
                    onTextLayout = { result -> layout.value = result() },
                    decorator = { inner -> Box(Modifier.padding(start = 8.dp, end = 16.dp)) { inner() } },
                    modifier = if (wordWrap) {
                        Modifier.fillMaxSize()
                    } else {
                        // Unbounded width inside the horizontal scroll means lines never wrap.
                        Modifier.widthIn(min = viewportWidth).fillMaxHeight()
                    },
                )
            }
        }
    }
}

/** Line numbers drawn beside the text, skipping the continuation rows of wrapped lines. */
@Composable
private fun LineNumberGutter(
    editor: EditorSession,
    layout: MutableState<TextLayoutResult?>,
    scroll: ScrollState,
    style: TextStyle,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val digits by remember { derivedStateOf { editor.lineStarts.size.toString().length.coerceAtLeast(2) } }
    val numberStyle = style.copy(color = MaterialTheme.colorScheme.outline)
    val density = LocalDensity.current
    val width = with(density) { measurer.measure("9".repeat(digits), numberStyle).size.width.toDp() } + 20.dp
    Canvas(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        val result = layout.value ?: return@Canvas
        val text = result.layoutInput.text
        if (result.lineCount == 0) return@Canvas
        val top = scroll.value.toFloat()
        val first = result.getLineForVerticalPosition(top)
        val last = result.getLineForVerticalPosition(top + size.height).coerceAtMost(result.lineCount - 1)
        val padEnd = 10.dp.toPx()
        for (line in first..last) {
            val start = result.getLineStart(line)
            if (start > 0 && start <= text.length && text[start - 1] != '\n') continue
            val label = measurer.measure((editor.lineIndexOf(start) + 1).toString(), numberStyle)
            drawText(label, topLeft = Offset(size.width - label.size.width - padEnd, result.getLineTop(line) - top))
        }
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
                    editor.findQuery,
                    "Find",
                    Modifier.weight(1f).focusRequester(focus),
                    imeAction = ImeAction.Search,
                    onAction = { editor.find() },
                )
                Text(
                    if (editor.findQuery.text.isEmpty()) "" else "${editor.matchCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                IconButton(onClick = { editor.find(forward = false) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
                IconButton(onClick = { editor.find(forward = true) }) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
                IconButton(onClick = { editor.findVisible = false }) { Icon(Icons.Default.Close, "Close find") }
            }
            if (!editor.readOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactField(editor.replaceQuery, "Replace with", Modifier.weight(1f), ImeAction.Done) { editor.replaceCurrent() }
                    TextButton(onClick = editor::replaceCurrent) { Text("Replace") }
                    TextButton(onClick = { editor.replaceAll() }) { Text("All") }
                }
            }
            FilterChip(selected = editor.matchCase, onClick = { editor.matchCase = !editor.matchCase }, label = { Text("Match case") })
        }
    }
}

@Composable
private fun CompactField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier,
    imeAction: ImeAction,
    onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        state = state,
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = imeAction),
        onKeyboardAction = { onAction() },
        modifier = modifier,
        decorator = { inner ->
            Box(
                Modifier
                    .background(colors.surfaceContainerHighest, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (state.text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                inner()
            }
        },
    )
}

/** Undo and redo plus where the cursor is and how the file is stored. */
@OptIn(ExperimentalFoundationApi::class)
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
                val undo = editor.state.undoState
                IconButton(onClick = undo::undo, enabled = undo.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = undo::redo, enabled = undo.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            }
            Spacer(Modifier.weight(1f))
            val (line, column) = editor.cursor
            Text(
                "Ln $line, Col $column · ${editor.lineStarts.size} lines · ${editor.encodingLabel} · ${editor.lineEndingLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}
