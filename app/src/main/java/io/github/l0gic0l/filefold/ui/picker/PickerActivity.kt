package io.github.l0gic0l.filefold.ui.picker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.l0gic0l.filefold.core.FileItem
import io.github.l0gic0l.filefold.core.FileOps
import io.github.l0gic0l.filefold.data.AppSettings
import io.github.l0gic0l.filefold.data.rememberAppSettings
import io.github.l0gic0l.filefold.ui.browser.Breadcrumbs
import io.github.l0gic0l.filefold.ui.browser.Crumb
import io.github.l0gic0l.filefold.ui.browser.DelayedSpinner
import io.github.l0gic0l.filefold.ui.browser.EmptyState
import io.github.l0gic0l.filefold.ui.browser.FileList
import io.github.l0gic0l.filefold.ui.browser.Location
import io.github.l0gic0l.filefold.ui.theme.FileFoldTheme
import io.github.l0gic0l.filefold.util.IncomingFiles
import io.github.l0gic0l.filefold.util.Launch
import io.github.l0gic0l.filefold.util.Place
import io.github.l0gic0l.filefold.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Lets another app ask for a file ("Attach", "Upload", "Import from files"). The user browses
 * with FileFold and picks one file, or several when the app allows it; the app gets a link it
 * can read, and nothing else.
 */
class PickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val types = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
            ?: listOfNotNull(intent.type).filter { it != "*/*" }
        val multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        setResult(Activity.RESULT_CANCELED)
        setContent {
            val settings = rememberAppSettings() ?: return@setContent
            FileFoldTheme(settings.theme, settings.iconColors) {
                PickerScreen(settings, types, multiple, onPick = ::finishWith, onCancel = ::finish, onGrant = ::requestAllFilesAccess)
            }
        }
    }

    private fun finishWith(files: List<File>) {
        val uris = try {
            files.map { FileProvider.getUriForFile(this, "$packageName.files", it) }
        } catch (_: IllegalArgumentException) {
            return
        }
        if (uris.isEmpty()) return
        val result = Intent().setData(uris.first()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Apps that asked for several files read them from the clip data, which also carries the read grant.
        val clip = ClipData.newUri(contentResolver, "Files", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        result.clipData = clip
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun requestAllFilesAccess() {
        val appPage = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri())
        try {
            startActivity(appPage)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScreen(
    settings: AppSettings,
    types: List<String>,
    multiple: Boolean,
    onPick: (List<File>) -> Unit,
    onCancel: () -> Unit,
    onGrant: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Storage.hasAllFilesAccess()) }
    LifecycleResumeEffect(Unit) {
        granted = Storage.hasAllFilesAccess()
        onPauseOrDispose { }
    }
    val places = remember { Storage.volumes(context) + Storage.shortcuts() }
    val volumes = places.filter { it.isVolume }
    // Starts in Download, where most files that get attached somewhere end up.
    val start = File(Storage.primaryRoot, Environment.DIRECTORY_DOWNLOADS).takeIf { it.isDirectory } ?: Storage.primaryRoot
    var dir by rememberSaveable { mutableStateOf(start.absolutePath) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    val root = Storage.volumeRootOf(volumes, dir)
    BackHandler(enabled = selection.isNotEmpty() || (root != null && dir != root)) {
        if (selection.isNotEmpty()) selection = emptySet() else File(dir).parent?.let { dir = it }
    }

    val listing by produceState<Result<List<FileItem>>?>(null, dir, granted, settings.showHidden, settings.sort) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                FileOps.list(File(dir), settings.showHidden)
                    // Folders to go into, and the files the app can take.
                    .filter { it.isDirectory || IncomingFiles.mimeMatches(Launch.mimeType(it.name), types) }
                    .sortedWith(settings.sort.comparator())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (multiple) "Choose files" else "Choose a file")
                        if (types.isNotEmpty()) {
                            Text(
                                "Showing ${describe(types)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") } },
            )
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text("${selection.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Button(onClick = { onPick(selection.map(::File)) }) { Text("Select") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (!granted) {
                NeedsAccess(onGrant)
                return@Column
            }
            PlaceChips(places, current = root) { dir = it.path }
            Breadcrumbs(crumbs(volumes, dir)) { dir = it.diskPath }
            val state = remember(dir) { androidx.compose.foundation.lazy.LazyListState() }
            when (val result = listing) {
                null -> DelayedSpinner()
                else -> result.fold(
                    onSuccess = { items ->
                        if (items.isEmpty()) {
                            EmptyState(if (types.isEmpty()) "This folder is empty" else "Nothing here the app can use")
                        } else {
                            FileList(
                                items = items,
                                selection = selection,
                                inArchive = false,
                                thumbnails = settings.showThumbnails,
                                state = state,
                                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                                onClick = { item ->
                                    when {
                                        item.isDirectory -> { selection = emptySet(); dir = item.path }
                                        selection.isNotEmpty() -> selection = selection.toggle(item.path)
                                        else -> onPick(listOf(File(item.path)))
                                    }
                                },
                                // Several files only when the app accepts several.
                                onLongClick = { item -> if (multiple && !item.isDirectory) selection = selection.toggle(item.path) },
                            )
                        }
                    },
                    onFailure = { EmptyState((it as? IOException)?.message ?: "This folder cannot be opened") },
                )
            }
        }
    }
}

private fun Set<String>.toggle(path: String) = if (path in this) this - path else this + path

/** "images", "PDF documents" or "the kinds of file the app asked for". */
private fun describe(types: List<String>): String {
    val single = types.singleOrNull()?.lowercase() ?: return "the kinds of file the app asked for"
    return when {
        single.startsWith("image/") -> "images"
        single.startsWith("video/") -> "videos"
        single.startsWith("audio/") -> "audio"
        single.startsWith("text/") -> "text files"
        single == "application/pdf" -> "PDF documents"
        else -> "$single files"
    }
}

private fun crumbs(volumes: List<Place>, dir: String): List<Crumb> {
    val root = Storage.volumeRootOf(volumes, dir) ?: return listOf(Crumb(File(dir).name, Location.Disk(dir)))
    val crumbs = arrayListOf(Crumb(volumes.first { it.path == root }.label, Location.Disk(root)))
    var path = root
    dir.removePrefix(root).split('/').filter { it.isNotEmpty() }.forEach { part ->
        path = "$path/$part"
        crumbs += Crumb(part, Location.Disk(path))
    }
    return crumbs
}

@Composable
private fun PlaceChips(places: List<Place>, current: String?, onClick: (Place) -> Unit) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(places, key = { it.path }) { place ->
            FilterChip(
                selected = place.path == current,
                onClick = { onClick(place) },
                label = { Text(place.label) },
                leadingIcon = { Icon(place.icon, null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun NeedsAccess(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                "To choose files from anywhere on this device, FileFold needs access to all files.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.size(4.dp))
            Button(onClick = onGrant) { Text("Allow access to all files") }
        }
    }
}
