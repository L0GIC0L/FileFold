package io.github.l0gic0l.filefold

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import io.github.l0gic0l.filefold.data.rememberAppSettings
import io.github.l0gic0l.filefold.ui.FileFoldApp
import io.github.l0gic0l.filefold.ui.browser.BrowserDialog
import io.github.l0gic0l.filefold.ui.browser.BrowserViewModel
import io.github.l0gic0l.filefold.ui.browser.Location
import io.github.l0gic0l.filefold.ui.theme.FileFoldTheme
import io.github.l0gic0l.filefold.util.Storage
import java.io.File

class MainActivity : ComponentActivity() {

    // The same instance the screens use, so keyboard shortcuts act on what is showing.
    private val browser: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Thumbnails for videos as well as pictures.
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()
        }
        // Not when the activity is only being recreated: that link was already opened.
        if (savedInstanceState == null) handleIntent(intent)
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

    /** FileFold is already running and another app asks it to open something. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Folders, downloads and files other apps ask FileFold to show; see the intent filters in the manifest. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            DownloadManager.ACTION_VIEW_DOWNLOADS ->
                browser.openFolderFromOutside(File(Storage.primaryRoot, Environment.DIRECTORY_DOWNLOADS).absolutePath)
            Intent.ACTION_VIEW -> intent.data?.let { browser.openFromOutside(it, intent.type) }
        }
    }

    /** Ctrl shortcuts for a keyboard or a laptop-style foldable. Text fields take their own first. */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (!event.isCtrlPressed || !Storage.hasAllFilesAccess()) return super.onKeyShortcut(keyCode, event)
        val vm = browser
        when (keyCode) {
            KeyEvent.KEYCODE_A -> vm.selectAll()
            KeyEvent.KEYCODE_C -> vm.copySelection(cut = false)
            KeyEvent.KEYCODE_X -> if (vm.location is Location.Disk) vm.copySelection(cut = true)
            KeyEvent.KEYCODE_V -> if (vm.canPaste) vm.paste()
            KeyEvent.KEYCODE_F -> vm.openSearch()
            KeyEvent.KEYCODE_T -> vm.newTab()
            KeyEvent.KEYCODE_W -> vm.closeTab(vm.activeTabId)
            KeyEvent.KEYCODE_N -> if (vm.location is Location.Disk) vm.dialog = BrowserDialog.NewFolder
            KeyEvent.KEYCODE_H -> vm.setShowHidden(!vm.settings.showHidden)
            KeyEvent.KEYCODE_R -> vm.refresh()
            else -> return super.onKeyShortcut(keyCode, event)
        }
        return true
    }

    /** Delete, F2 and F5, as on a desktop. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!Storage.hasAllFilesAccess()) return super.onKeyDown(keyCode, event)
        val vm = browser
        val selected = vm.selectedItems
        when {
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL && selected.isNotEmpty() && vm.location !is Location.InArchive ->
                vm.dialog = BrowserDialog.Delete(selected)
            keyCode == KeyEvent.KEYCODE_F2 && selected.size == 1 && vm.location is Location.Disk ->
                vm.dialog = BrowserDialog.Rename(selected.single())
            keyCode == KeyEvent.KEYCODE_F5 -> vm.refresh()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
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
