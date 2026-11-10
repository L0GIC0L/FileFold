package io.github.l0gic0l.filefold.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.l0gic0l.filefold.archive.ArchiveFormat
import io.github.l0gic0l.filefold.core.AutoSortConfig
import io.github.l0gic0l.filefold.core.AutoSortMode
import io.github.l0gic0l.filefold.core.AutoSorter
import io.github.l0gic0l.filefold.core.FileOps
import io.github.l0gic0l.filefold.util.Format

/** Shows whichever dialog the ViewModel asks for. */
@Composable
fun BrowserDialogs(vm: BrowserViewModel) {
    val close = { vm.dialog = null }
    when (val d = vm.dialog) {
        null -> Unit
        BrowserDialog.NewFolder -> NameDialog("New folder", "", "Create", close) { vm.createFolder(it); close() }
        BrowserDialog.NewFile -> NameDialog("New text file", "notes.txt", "Create", close) { vm.createFile(it); close() }
        is BrowserDialog.Rename -> NameDialog("Rename", d.item.name, "Rename", close, selectStem = !d.item.isDirectory, requireChange = true) {
            vm.rename(d.item, it)
            close()
        }
        is BrowserDialog.Delete -> AlertDialog(
            onDismissRequest = close,
            title = { Text(if (d.items.size == 1) "Delete \"${d.items.single().name}\"?" else "Delete ${d.items.size} items?") },
            text = {
                val folders = d.items.count { it.isDirectory }
                Text(
                    "This cannot be undone." + if (folders > 0) " Folders are deleted with everything inside them." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(d.items); close() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        is BrowserDialog.Compress -> CompressDialog(d, close) { name, format -> vm.compress(d.files, name, format); close() }
        is BrowserDialog.Extract -> ExtractDialog(d, close) { folder -> vm.confirmExtract(d, folder); close() }
        is BrowserDialog.Password -> PasswordDialog(d)
        is BrowserDialog.AutoSort -> AutoSortDialog(vm.settings.autoSort, close) { mode, moveFolders ->
            vm.planAutoSort(mode, moveFolders)
        }
        is BrowserDialog.AutoSortPreview -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Sort ${d.plan.moves.size} ${if (d.plan.moves.size == 1) "item" else "items"}?") },
            text = {
                Column {
                    Text("Items in \"${d.plan.dir.name}\" will be moved into these folders:")
                    Spacer(Modifier.padding(4.dp))
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(d.plan.summary) { row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(row.folder, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(
                                        row.files.takeIf { it > 0 }?.let { "$it ${if (it == 1) "file" else "files"}" },
                                        row.folders.takeIf { it > 0 }?.let { "$it ${if (it == 1) "folder" else "folders"}" },
                                    ).joinToString(", "),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        if (d.plan.moves.any { it.source.isDirectory }) {
                            "The folders it sorts into are never moved, nothing is overwritten, and you can undo afterwards."
                        } else {
                            "Existing folders are not touched, nothing is overwritten, and you can undo afterwards."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.runAutoSort(d.plan); close() }) { Text("Sort") } },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        is BrowserDialog.UnsavedChanges -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Save changes?") },
            text = { Text("\"${d.fileName}\" has unsaved changes.") },
            confirmButton = { TextButton(onClick = d.onSave) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = close) { Text("Cancel") }
                    TextButton(onClick = d.onDiscard) { Text("Discard") }
                }
            },
        )
    }
}

/**
 * Text input dialog for names. With [selectStem] the name is pre-selected up to
 * its extension, so typing replaces "photo" but keeps ".jpg".
 */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    selectStem: Boolean = false,
    requireChange: Boolean = false,
    onConfirm: (String) -> Unit,
) {
    val stemEnd = if (selectStem && initial.lastIndexOf('.') > 0) initial.lastIndexOf('.') else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stemEnd))) }
    val error = FileOps.validateName(value.text.trim())
    val canConfirm = error == null && !(requireChange && value.text.trim() == initial)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Name") },
                isError = error != null && value.text.isNotEmpty(),
                supportingText = { if (error != null && value.text.isNotEmpty()) Text(error) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = canConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompressDialog(dialog: BrowserDialog.Compress, onDismiss: () -> Unit, onConfirm: (String, ArchiveFormat) -> Unit) {
    var name by remember { mutableStateOf(dialog.suggestedName) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    val error = FileOps.validateName(name.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (dialog.files.size == 1) "Compress \"${dialog.files.single().name}\"" else "Compress ${dialog.files.size} items") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Archive name") },
                    suffix = { Text(".${format.extension}") },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Format", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveFormat.creatable.forEach { option ->
                        FilterChip(selected = option == format, onClick = { format = option }, label = { Text(option.label) })
                    }
                }
                Text(
                    when (format) {
                        ArchiveFormat.ZIP -> "Opens everywhere. The safe default."
                        ArchiveFormat.SEVEN_Z -> "Smallest files, slower to create. Needs a 7-Zip compatible app."
                        ArchiveFormat.TAR -> "Bundles files without compressing them."
                        ArchiveFormat.TAR_GZ -> "Common on Linux. Fast, with good compression."
                        ArchiveFormat.TAR_BZ2 -> "Smaller than TAR.GZ, slower."
                        ArchiveFormat.TAR_XZ -> "Very small, slower. Common for Linux downloads."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim(), format) }, enabled = error == null) { Text("Compress") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExtractDialog(dialog: BrowserDialog.Extract, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var intoFolder by remember { mutableStateOf(!dialog.singleRoot) }
    var folder by remember { mutableStateOf(ArchiveFormat.stripExtension(dialog.archive.name)) }
    val error = if (intoFolder) FileOps.validateName(folder.trim()) else null
    val what = dialog.entries?.let { if (it.size == 1) "\"${it.single().substringAfterLast('/')}\"" else "${it.size} items" } ?: "everything"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extract $what") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "From ${dialog.archive.name} into ${dialog.archive.parentFile?.name ?: "/"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().selectable(intoFolder, role = Role.Checkbox) { intoFolder = !intoFolder },
                ) {
                    Checkbox(checked = intoFolder, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Put them in a new folder")
                }
                if (intoFolder) {
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        singleLine = true,
                        label = { Text("Folder name") },
                        isError = error != null,
                        supportingText = { error?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "Existing files are never overwritten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (intoFolder) folder.trim() else null) }, enabled = error == null) { Text("Extract") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(dialog: BrowserDialog.Password) {
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = dialog.onCancel,
        title = { Text("Password required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\"${dialog.archiveName}\" is encrypted.", maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    isError = dialog.wrong,
                    supportingText = { if (dialog.wrong) Text("That password did not work") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) dialog.onSubmit(password) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { dialog.onSubmit(password) }, enabled = password.isNotEmpty()) { Text("Open") } },
        dismissButton = { TextButton(onClick = dialog.onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun AutoSortDialog(config: AutoSortConfig, onDismiss: () -> Unit, onConfirm: (AutoSortMode, Boolean) -> Unit) {
    var mode by remember { mutableStateOf(config.mode) }
    var moveFolders by remember { mutableStateOf(config.moveFolders) }
    val foldersTarget = AutoSorter.cleanPath(config.foldersTarget) ?: "Misc"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-sort into folders") },
        text = {
            Column {
                Text("Tidy this folder by moving its loose files into sub-folders. Folder names and which types to sort are in Settings.")
                Spacer(Modifier.padding(4.dp))
                AutoSortMode.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(option == mode, role = Role.RadioButton) { mode = option }
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(selected = option == mode, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().selectable(moveFolders, role = Role.Checkbox) { moveFolders = !moveFolders },
                ) {
                    Checkbox(checked = moveFolders, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Also move folders into \"$foldersTarget\"")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode, moveFolders) }) { Text("Preview") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Progress for copy, move, delete, compress, extract and sort, with a cancel button. */
@Composable
fun OperationDialog(state: OperationState, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(state.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(state.current.ifEmpty { "Preparing…" }, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (state.total > 0) {
                    val percent = (state.done * 100 / state.total).coerceIn(0, 100)
                    val detail = if (state.title == "Sorting") "${state.done} of ${state.total}" else "${Format.size(state.done)} of ${Format.size(state.total)}"
                    Text("$percent% · $detail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
