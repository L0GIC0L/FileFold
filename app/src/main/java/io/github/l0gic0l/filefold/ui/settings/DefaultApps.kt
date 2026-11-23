package io.github.l0gic0l.filefold.ui.settings

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.l0gic0l.filefold.util.Storage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Which app opens something by default. */
private sealed interface Handler {
    data object FileFold : Handler
    data class Other(val label: String, val packageName: String) : Handler

    /** No default: Android asks each time, and FileFold is one of the choices. */
    data object Asks : Handler
}

/** A kind of thing FileFold can be the default for, with an example Android can be asked to open. */
private data class Kind(val title: String, val icon: ImageVector, val intent: (Context) -> Intent)

private val kinds = listOf(
    Kind("Folders", Icons.Default.Folder) { context ->
        viewIntent(context, File(Storage.primaryRoot, Environment.DIRECTORY_DOWNLOADS), "resource/folder")
    },
    Kind("Downloads", Icons.Default.Download) { Intent(DownloadManager.ACTION_VIEW_DOWNLOADS) },
    Kind("ZIP and other archives", Icons.Default.FolderZip) { context ->
        viewIntent(context, sample(context, "FileFold sample.zip"), "application/zip")
    },
    Kind("Text files", Icons.AutoMirrored.Filled.TextSnippet) { context ->
        viewIntent(context, sample(context, "FileFold sample.txt"), "text/plain")
    },
)

private fun viewIntent(context: Context, file: File, type: String): Intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", file), type)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/** A small file for Android to open when asked which app should open its kind. */
private fun sample(context: Context, name: String): File {
    val file = File(File(context.cacheDir, "defaults").apply { mkdirs() }, name)
    if (!file.exists()) {
        val text = "This file was made by FileFold to set which app opens this kind of file. It can be deleted.\n"
        if (name.endsWith(".zip")) {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("Read me.txt"))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        } else {
            file.writeText(text)
        }
    }
    return file
}

private fun handlerFor(context: Context, intent: Intent): Handler {
    val pm = context.packageManager
    val candidates = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
    // With no default this resolves to Android's own chooser, which is not one of the candidates.
    val best = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return Handler.Asks
    return when {
        best.packageName == context.packageName -> Handler.FileFold
        best.packageName in candidates -> Handler.Other(best.loadLabel(pm).toString(), best.packageName)
        else -> Handler.Asks
    }
}

/**
 * Settings section for making FileFold the default file manager. Android has no single
 * setting for this: each kind of item opens in whichever app the user picked for it. This
 * shows the current choice for each and lets Android ask again.
 */
@Composable
fun DefaultFileManagerSettings() {
    val context = LocalContext.current
    // Checked again on return, since the choice is made in Android's own dialog.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        Text(
            "Android has no single setting for a default file manager: each kind of item opens in the app you chose " +
                "for it. Tap Set, then pick FileFold and tap \"Always\". Apps that ask you to choose a file also list FileFold.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        kinds.forEach { kind ->
            val handler = remember(refresh) { handlerFor(context, kind.intent(context)) }
            ListItem(
                leadingContent = { Icon(kind.icon, null) },
                headlineContent = { Text(kind.title) },
                supportingContent = {
                    Text(
                        when (handler) {
                            Handler.FileFold -> "Opens in FileFold"
                            Handler.Asks -> "Android asks each time"
                            is Handler.Other -> "Opens in ${handler.label}"
                        },
                    )
                },
                trailingContent = {
                    when (handler) {
                        Handler.FileFold -> Icon(Icons.Default.CheckCircle, "FileFold is the default", tint = MaterialTheme.colorScheme.primary)
                        Handler.Asks -> FilledTonalButton(onClick = { launch(context, kind.intent(context)) }) { Text("Set") }
                        // Another app was chosen with "Always"; that choice is cleared on its own info page.
                        is Handler.Other -> OutlinedButton(onClick = { clearDefault(context, handler) }) { Text("Change") }
                    }
                },
            )
        }
    }
}

private fun launch(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show()
    }
}

/** Opens the other app's info page, where "Open by default" › "Clear defaults" undoes its "Always". */
private fun clearDefault(context: Context, handler: Handler.Other) {
    Toast.makeText(context, "Open \"Open by default\" and clear the defaults for ${handler.label}, then come back", Toast.LENGTH_LONG).show()
    launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${handler.packageName}".toUri()))
}
