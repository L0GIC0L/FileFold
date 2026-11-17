package io.github.l0gic0l.filefold.ui.detail

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.ui.browser.BrowserDialog
import io.github.l0gic0l.filefold.ui.browser.BrowserViewModel
import io.github.l0gic0l.filefold.ui.components.KindBadge
import io.github.l0gic0l.filefold.ui.components.TabletopLayout
import io.github.l0gic0l.filefold.util.Launch
import io.github.l0gic0l.filefold.util.MediaInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Top bar buttons shared by the viewers: details, and a menu with open with, share, checksums
 * and the byte view. Kept to two buttons so the file name still fits on a folded phone.
 */
@Composable
fun ViewerActions(vm: BrowserViewModel, info: FileInfo, file: File, onToggleInfo: () -> Unit) {
    val context = LocalContext.current
    IconButton(onClick = onToggleInfo) { Icon(Icons.Default.Info, "Details") }
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(text: String, icon: ImageVector, action: () -> Unit) =
                DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = { open = false; action() })

            item("Open with", Icons.AutoMirrored.Filled.OpenInNew) { Launch.openWith(context, file) }
            item("Share", Icons.Default.Share) { Launch.share(context, listOf(file)) }
            item("Checksums…", Icons.Default.Fingerprint) { vm.dialog = BrowserDialog.Checksums(file) }
            item("View bytes", Icons.Default.Memory) { vm.openAsHex(info) }
        }
    }
}

/** The details that slide in under a viewer, and always show on the lower half in tabletop posture. */
@Composable
private fun ViewerInfo(vm: BrowserViewModel, target: DetailTarget, info: FileInfo) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
            InfoRows(info.rows)
            EntryActions(vm, target)
        }
    }
}

@Composable
private fun ViewerProblem(message: String, file: File) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = { Launch.openWith(context, file) }) { Text("Open with another app") }
    }
}

// ---------------------------------------------------------------------- PDF

/** An open PDF. Pages are drawn one at a time on a single background thread, as Android's renderer requires. */
private class PdfDocument(file: File) {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Throwable) {
        descriptor.close()
        throw e
    }
    private val thread = Dispatchers.IO.limitedParallelism(1)
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Width divided by height of the first page; a good guess for the rest until each is drawn. */
    val pageRatio: Float = if (pageCount > 0) renderer.openPage(0).use { it.width.toFloat() / it.height } else 0.707f

    // Rendered pages by "page@width". Sized in bytes so a few zoomed pages cannot exhaust memory.
    private val cache = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun render(index: Int, width: Int): Bitmap? {
        cache["$index@$width"]?.let { return it }
        return withContext(thread) {
            if (closed) return@withContext null
            renderer.openPage(index).use { page ->
                val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put("$index@$width", bitmap)
                bitmap
            }
        }
    }

    /** Queued behind any page still being drawn. */
    fun close() {
        CoroutineScope(thread).launch {
            closed = true
            renderer.close()
            descriptor.close()
        }
    }
}

/** PDF pages in a scrolling column, with zoom buttons. */
@Composable
fun PdfScreen(vm: BrowserViewModel, target: DetailTarget, content: DetailContent.Pdf, showBack: Boolean, onBack: () -> Unit) {
    val file = content.file
    // Opened off the main thread: a large PDF takes a moment to read. Null until that is done.
    val opened by produceState<Result<PdfDocument>?>(null, file, file.lastModified()) {
        val result = withContext(Dispatchers.IO) { runCatching { PdfDocument(file) } }
        value = result
        awaitDispose { result.getOrNull()?.close() }
    }
    val document = opened?.getOrNull()
    val listState = rememberLazyListState()
    val page by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    var zoom by remember(file) { mutableFloatStateOf(1f) }
    var showInfo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            DetailTopBar(
                content.info.name, showBack, onBack,
                subtitle = if (document != null) "Page $page of ${document.pageCount}" else content.info.typeLabel,
            ) {
                ViewerActions(vm, content.info, file) { showInfo = !showInfo }
            }
        },
    ) { padding ->
        TabletopLayout(
            modifier = Modifier.padding(padding),
            top = {
                if (opened == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (document == null) {
                    val locked = opened?.exceptionOrNull() is SecurityException
                    ViewerProblem(if (locked) "This PDF is password protected." else "This PDF could not be opened here.", file)
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        val viewport = maxWidth
                        // Drawn at the size shown, so text stays sharp when zoomed, up to a sensible limit.
                        val renderWidth = with(LocalDensity.current) { (viewport * zoom).roundToPx() }.coerceIn(1, 2400)
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).width(viewport * zoom),
                        ) {
                            items(document.pageCount, key = { it }) { index ->
                                val bitmap by produceState<Bitmap?>(null, document, index, renderWidth) {
                                    value = runCatching { document.render(index, renderWidth) }.getOrNull()
                                }
                                val shown = bitmap
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(shown?.let { it.width.toFloat() / it.height } ?: document.pageRatio)
                                        .background(Color.White),
                                ) {
                                    if (shown != null) Image(shown.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize())
                                }
                            }
                        }
                        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalIconButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(3f) }, enabled = zoom < 3f) {
                                Icon(Icons.Default.ZoomIn, "Zoom in")
                            }
                            FilledTonalIconButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }, enabled = zoom > 1f) {
                                Icon(Icons.Default.ZoomOut, "Zoom out")
                            }
                        }
                    }
                }
            },
            bottom = { tabletop -> if (tabletop || showInfo) ViewerInfo(vm, target, content.info) },
        )
    }
}

// ---------------------------------------------------------------------- audio and video

/** Wraps Android's MediaPlayer and exposes what the controls need as observable state. */
private class PlayerState(file: File) {
    var prepared by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var position by mutableIntStateOf(0)
        private set
    var duration by mutableIntStateOf(0)
        private set
    var aspect by mutableFloatStateOf(16f / 9f)
        private set
    var failed by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = MediaPlayer()

    init {
        val media = player!!
        try {
            media.setOnPreparedListener {
                prepared = true
                duration = media.duration.coerceAtLeast(0)
                // Starts straight away, as tapping a song or clip suggests.
                media.start()
                playing = true
            }
            media.setOnVideoSizeChangedListener { _, width, height -> if (width > 0 && height > 0) aspect = width.toFloat() / height }
            media.setOnCompletionListener {
                playing = false
                position = duration
            }
            media.setOnErrorListener { _, _, _ ->
                failed = true
                playing = false
                true
            }
            media.setDataSource(file.path)
            media.prepareAsync()
        } catch (_: Exception) {
            failed = true
        }
    }

    fun setSurface(surface: Surface?) {
        try {
            player?.setSurface(surface)
        } catch (_: IllegalStateException) {
        }
    }

    fun toggle() {
        val p = player?.takeIf { prepared && !failed } ?: return
        if (playing) {
            p.pause()
            playing = false
        } else {
            if (duration > 0 && position >= duration - 250) p.seekTo(0)
            p.start()
            playing = true
        }
    }

    fun pause() {
        if (prepared && playing) {
            player?.pause()
            playing = false
        }
    }

    fun seekTo(millis: Int) {
        val p = player?.takeIf { prepared && !failed } ?: return
        val target = millis.coerceIn(0, duration)
        p.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
        position = target
    }

    /** Reads the playback position; called a few times a second while playing. */
    fun tick() {
        if (prepared && !failed) player?.let { position = it.currentPosition.coerceIn(0, duration.coerceAtLeast(0)) }
    }

    fun release() {
        player?.release()
        player = null
        playing = false
    }
}

/** Plays audio and video. In tabletop posture the picture is on the upright half and the controls on the table. */
@Composable
fun MediaScreen(vm: BrowserViewModel, target: DetailTarget, content: DetailContent.Media, showBack: Boolean, onBack: () -> Unit) {
    val file = content.file
    val state = remember(file) { PlayerState(file) }
    DisposableEffect(state) { onDispose { state.release() } }
    // Stops when the app is no longer visible; split-screen and a half-folded phone keep playing.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.pause() }
    LaunchedEffect(state, state.playing) {
        while (state.playing) {
            state.tick()
            delay(250)
        }
    }
    var showInfo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            DetailTopBar(content.info.name, showBack, onBack, subtitle = content.info.typeLabel) {
                ViewerActions(vm, content.info, file) { showInfo = !showInfo }
            }
        },
    ) { padding ->
        TabletopLayout(
            modifier = Modifier.padding(padding),
            top = {
                when {
                    state.failed -> ViewerProblem("This file cannot be played here.", file)
                    content.video -> Box(Modifier.fillMaxSize().background(Color.Black).clickable { state.toggle() }, contentAlignment = Alignment.Center) {
                        key(state) {
                            AndroidView(
                                factory = { context ->
                                    TextureView(context).apply {
                                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                            private var surface: Surface? = null

                                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                                surface = Surface(texture).also(state::setSurface)
                                            }

                                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                                state.setSurface(null)
                                                surface?.release()
                                                surface = null
                                                return true
                                            }
                                        }
                                    }
                                },
                                update = { it.keepScreenOn = state.playing },
                                modifier = Modifier.aspectRatio(state.aspect),
                            )
                        }
                        if (!state.prepared) CircularProgressIndicator()
                    }
                    else -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        KindBadge(FileKind.AUDIO, size = 120.dp)
                        Spacer(Modifier.height(16.dp))
                        val title = content.info.rows.firstOrNull { it.label == "Title" }?.value ?: file.nameWithoutExtension
                        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2)
                        content.info.rows.firstOrNull { it.label == "Artist" }?.let {
                            Text(it.value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            bottom = { tabletop ->
                Column {
                    if (!state.failed) PlayerControls(state)
                    if (tabletop || showInfo) ViewerInfo(vm, target, content.info)
                }
            },
        )
    }
}

@Composable
private fun PlayerControls(state: PlayerState) {
    // While the thumb is being dragged it follows the finger, not the player.
    var dragged by remember { mutableStateOf<Float?>(null) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Slider(
                value = dragged ?: state.position.toFloat(),
                onValueChange = { dragged = it },
                onValueChangeFinished = {
                    dragged?.let { state.seekTo(it.toInt()) }
                    dragged = null
                },
                valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(),
                enabled = state.prepared,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    MediaInfo.duration((dragged?.toInt() ?: state.position).toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { state.seekTo(state.position - 10_000) }, enabled = state.prepared) {
                    Icon(Icons.Default.Replay10, "Back 10 seconds")
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = state::toggle, enabled = state.prepared, modifier = Modifier.size(56.dp)) {
                    Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause" else "Play")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { state.seekTo(state.position + 10_000) }, enabled = state.prepared) {
                    Icon(Icons.Default.Forward10, "Forward 10 seconds")
                }
                Text(
                    MediaInfo.duration(state.duration.toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- hex

/** Reads a file a block at a time as rows scroll into view, so even huge files open at once. */
private class HexSource(file: File) {
    private val access = RandomAccessFile(file, "r")
    val length: Long = access.length()
    private val blocks = LruCache<Long, ByteArray>(32)

    /** Up to [count] bytes starting at [offset]. */
    fun read(offset: Long, count: Int): ByteArray {
        val blockIndex = offset / BLOCK
        val block = blocks[blockIndex] ?: ByteArray(minOf(BLOCK.toLong(), length - blockIndex * BLOCK).toInt().coerceAtLeast(0)).also {
            access.seek(blockIndex * BLOCK)
            access.readFully(it)
            blocks.put(blockIndex, it)
        }
        val start = (offset - blockIndex * BLOCK).toInt()
        return block.copyOfRange(start.coerceAtMost(block.size), (start + count).coerceAtMost(block.size))
    }

    fun close() = access.close()

    companion object {
        // A multiple of both row widths, so a row never straddles two blocks.
        const val BLOCK = 4096
    }
}

/** One row of a hex dump: offset, the bytes in hex, then the same bytes as text. */
fun hexRow(offset: Long, bytes: ByteArray, perRow: Int): String = buildString {
    append("%08X  ".format(offset))
    for (i in 0 until perRow) {
        append(if (i < bytes.size) "%02X ".format(bytes[i]) else "   ")
        if (i == perRow / 2 - 1) append(' ')
    }
    append(' ')
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        append(if (c in 0x20..0x7E) c.toChar() else '.')
    }
}

/** The raw bytes of a file as a hex dump. */
@Composable
fun HexScreen(vm: BrowserViewModel, content: DetailContent.Hex, showBack: Boolean, onBack: () -> Unit) {
    val opened = remember(content.file) { runCatching { HexSource(content.file) } }
    DisposableEffect(opened) { onDispose { opened.getOrNull()?.close() } }
    val source = opened.getOrNull()
    Scaffold(
        topBar = {
            DetailTopBar(content.info.name, showBack, onBack, subtitle = "Bytes · ${content.info.typeLabel}") {
                IconButton(onClick = vm::reloadDetail) { Icon(Icons.Default.Close, "Close the byte view") }
            }
        },
    ) { padding ->
        when {
            source == null -> Box(Modifier.fillMaxSize().padding(padding)) { ViewerProblem("This file could not be read.", content.file) }
            source.length == 0L -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("This file is empty") }
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                // Sixteen bytes a row where there is room (an unfolded screen), eight on a phone.
                val perRow = if (maxWidth >= 600.dp) 16 else 8
                val rows = ((source.length + perRow - 1) / perRow).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it }, contentType = { 0 }) { row ->
                        val offset = row.toLong() * perRow
                        val text = remember(source, row, perRow) {
                            runCatching { hexRow(offset, source.read(offset, perRow), perRow) }.getOrDefault("%08X  ?".format(offset))
                        }
                        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
