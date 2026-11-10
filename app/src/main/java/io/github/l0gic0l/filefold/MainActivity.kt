package io.github.l0gic0l.filefold

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.l0gic0l.filefold.data.rememberAppSettings
import io.github.l0gic0l.filefold.ui.FileFoldApp
import io.github.l0gic0l.filefold.ui.theme.FileFoldTheme
import io.github.l0gic0l.filefold.util.Storage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Nothing is drawn for the instant before settings are read, so the theme never flashes.
            val settings = rememberAppSettings() ?: return@setContent
            FileFoldTheme(settings.theme, settings.iconColors) {
                var granted by remember { mutableStateOf(Storage.hasAllFilesAccess()) }
                // Re-check every time we come back, e.g. from the system settings page.
                LifecycleResumeEffect(Unit) {
                    granted = Storage.hasAllFilesAccess()
                    onPauseOrDispose { }
                }
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (granted) FileFoldApp() else PermissionScreen(onGrant = ::requestAllFilesAccess)
                }
            }
        }
    }

    private fun requestAllFilesAccess() {
        val appPage = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri())
        val generalList = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        // Some devices only offer the general list of apps, and stripped-down builds offer neither.
        for (intent in listOf(appPage, generalList)) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        Toast.makeText(this, "Open Settings › Apps › FileFold and allow \"All files access\"", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("Welcome to FileFold", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                "To browse, edit, compress and extract your files, FileFold needs permission to manage all files " +
                    "on this device. Your files never leave your phone.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onGrant) { Text("Allow access to all files") }
        }
    }
}
