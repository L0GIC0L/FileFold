package io.github.l0gic0l.filefold.ui.browser

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.l0gic0l.filefold.archive.ArchiveFormat
import io.github.l0gic0l.filefold.archive.ArchiveIndex
import io.github.l0gic0l.filefold.archive.ArchivePasswordException
import io.github.l0gic0l.filefold.archive.Archives
import io.github.l0gic0l.filefold.core.AutoSortMode
import io.github.l0gic0l.filefold.core.AutoSorter
import io.github.l0gic0l.filefold.core.FileItem
import io.github.l0gic0l.filefold.core.FileKind
import io.github.l0gic0l.filefold.core.FileOps
import io.github.l0gic0l.filefold.core.FileTypes
import io.github.l0gic0l.filefold.core.Progress
import io.github.l0gic0l.filefold.core.SortOptions
import io.github.l0gic0l.filefold.core.TextDocument
import io.github.l0gic0l.filefold.data.AppSettings
import io.github.l0gic0l.filefold.data.SettingsRepository
import io.github.l0gic0l.filefold.ui.detail.DetailContent
import io.github.l0gic0l.filefold.ui.detail.DetailState
import io.github.l0gic0l.filefold.ui.detail.DetailTarget
import io.github.l0gic0l.filefold.ui.detail.EditorSession
import io.github.l0gic0l.filefold.ui.detail.FileInfo
import io.github.l0gic0l.filefold.ui.detail.InfoRow
import io.github.l0gic0l.filefold.util.Format
import io.github.l0gic0l.filefold.util.Launch
import io.github.l0gic0l.filefold.util.Place
import io.github.l0gic0l.filefold.util.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * State and actions for browsing. One instance serves both panes, so a
 * foldable can open, close or rotate without losing anything.
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val previewCache = File(app.cacheDir, "preview")

    var settings by mutableStateOf(AppSettings())
        private set

    var location by mutableStateOf<Location>(Location.Disk(Storage.primaryRoot.absolutePath))
        private set

    /** Storage volumes (internal, SD card, USB) followed by the standard folders. Refreshed on resume. */
    var places by mutableStateOf(Storage.volumes(app) + Storage.shortcuts())
        private set
    private val volumes: List<Place> get() = places.filter { it.isVolume }

    /** The current folder's contents, sorted and filtered. */
    var items by mutableStateOf<List<FileItem>>(emptyList())
        private set
    private var rawItems: List<FileItem> = emptyList()

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Filters the current folder by name; null when search is closed. */
    var search by mutableStateOf<String?>(null)
        private set

    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    val selectionMode: Boolean get() = selection.isNotEmpty()

    var clipboard by mutableStateOf<Clipboard?>(null)
        private set

    var archive by mutableStateOf<ArchiveSession?>(null)
        private set

    var dialog by mutableStateOf<BrowserDialog?>(null)

    var detail by mutableStateOf<DetailState?>(null)
        private set

    /** Set when the detail pane should be brought into view; the UI drives the navigator and clears it. */
    var detailRequest by mutableStateOf<String?>(null)
        private set

    private val _operation = MutableStateFlow<OperationState?>(null)
    val operation: StateFlow<OperationState?> = _operation

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** First visible item and offset for folders visited earlier, so going back restores the view. */
    val scrollMemory = HashMap<String, Pair<Int, Int>>()

    private var loadJob: Job? = null
    private var operationJob: Job? = null
    private var detailJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { previewCache.deleteRecursively() }
        viewModelScope.launch {
            var first = true
            settingsRepo.settings.collect { new ->
                val old = settings
                settings = new
                when {
                    first -> load()
                    new.showHidden != old.showHidden -> load()
                    new.sort != old.sort -> applyFilterAndSort()
                }
                first = false
            }
        }
    }

    // ------------------------------------------------------------ navigation

    fun navigate(to: Location) {
        if (to == location) return
        location = to
        selection = emptySet()
        search = null
        // Clear straight away so the old folder's files never show under the new folder's name.
        rawItems = emptyList()
        items = emptyList()
        error = null
        load()
    }

    fun openPlace(path: String) = navigate(Location.Disk(path))

    /** Called when the app returns to the foreground: storage may have been plugged in and files changed elsewhere. */
    fun onResume() {
        places = Storage.volumes(getApplication()) + Storage.shortcuts()
        if (operationJob?.isActive != true) load()
    }

    /** Goes one level up. Returns false at the top of a storage volume. */
    fun navigateUp(): Boolean {
        val parent = parentOf(location) ?: return false
        navigate(parent)
        return true
    }

    private fun parentOf(location: Location): Location? = when (location) {
        is Location.InArchive ->
            if (location.dir.isEmpty()) Location.Disk(File(location.archivePath).parent ?: return null)
            else Location.InArchive(location.archivePath, ArchiveIndex.parentOf(location.dir))
        is Location.Disk -> {
            val root = Storage.volumeRootOf(volumes, location.path)
            if (root == null || location.path == root) null else File(location.path).parent?.let { Location.Disk(it) }
        }
    }

    val canNavigateUp: Boolean get() = parentOf(location) != null

    /** Handles the back gesture in the browser. Returns false when there is nothing left to undo. */
    fun back(): Boolean = when {
        selectionMode -> { clearSelection(); true }
        search != null -> { closeSearch(); true }
        else -> navigateUp()
    }

    fun breadcrumbs(): List<Crumb> {
        val crumbs = ArrayList<Crumb>()
        val diskPath = when (val loc = location) {
            is Location.Disk -> loc.path
            is Location.InArchive -> loc.archivePath
        }
        val root = Storage.volumeRootOf(volumes, diskPath)
        if (root != null) {
            crumbs += Crumb(volumes.first { it.path == root }.label, Location.Disk(root))
            var path = root
            diskPath.removePrefix(root).split('/').filter { it.isNotEmpty() }.forEach { part ->
                path = "$path/$part"
                crumbs += Crumb(part, Location.Disk(path))
            }
        } else {
            crumbs += Crumb(File(diskPath).name.ifEmpty { "/" }, Location.Disk(diskPath))
        }
        val loc = location
        if (loc is Location.InArchive) {
            // The archive itself stands for its root folder.
            crumbs[crumbs.lastIndex] = Crumb(File(loc.archivePath).name, Location.InArchive(loc.archivePath, ""))
            var dir = ""
            loc.dir.split('/').filter { it.isNotEmpty() }.forEach { part ->
                dir = if (dir.isEmpty()) part else "$dir/$part"
                crumbs += Crumb(part, Location.InArchive(loc.archivePath, dir))
            }
        }
        return crumbs
    }

    /** What happens when an item is tapped. */
    fun open(item: FileItem) {
        if (selectionMode) {
            toggleSelection(item)
            return
        }
        when (val loc = location) {
            is Location.Disk -> when {
                item.isDirectory -> navigate(Location.Disk(item.path))
                ArchiveFormat.fromName(item.name) != null -> navigate(Location.InArchive(item.path, ""))
                else -> showDetail(DetailTarget.Disk(item.path))
            }
            is Location.InArchive ->
                if (item.isDirectory) navigate(Location.InArchive(loc.archivePath, item.path))
                else showDetail(DetailTarget.Entry(loc.archivePath, item.path))
        }
    }

    /** Opens a ZIP-style container that has another extension, such as an APK or DOCX. */
    fun browseAsArchive(path: String) = navigate(Location.InArchive(path, ""))

    // ------------------------------------------------------------ loading

    fun refresh() = load()

    private fun load() {
        loadJob?.cancel()
        val target = location
        loadJob = viewModelScope.launch {
            loading = true
            try {
                var problem: String? = null
                rawItems = when (target) {
                    is Location.Disk -> withContext(Dispatchers.IO) { FileOps.list(File(target.path), settings.showHidden) }
                    is Location.InArchive -> {
                        val session = openArchive(target.archivePath)
                        when {
                            session == null -> {
                                problem = "Enter the password to open this archive"
                                emptyList()
                            }
                            !session.index.hasFolder(target.dir) -> throw IOException("Folder not found in archive")
                            else -> session.index.list(target.dir)
                        }
                    }
                }
                error = problem
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rawItems = emptyList()
                error = e.message ?: "Could not open this folder"
            } finally {
                loading = false
            }
            applyFilterAndSort()
        }
    }

    /**
     * Returns the cached session for an archive, reading its index when it is
     * new or has changed on disk. Asks for a password when the index itself is
     * encrypted; returns null if the user gives up.
     */
    private suspend fun openArchive(path: String, password: String? = archive?.takeIf { it.file.path == path }?.password): ArchiveSession? {
        val file = File(path)
        archive?.let { if (it.file.path == path && it.lastModified == file.lastModified()) return it }
        val format = withContext(Dispatchers.IO) { ArchiveFormat.of(file) }
            ?: throw IOException("This is not an archive FileFold can read")
        return try {
            val entries = withContext(Dispatchers.IO) { Archives.list(file, format, password) }
            ArchiveSession(file, format, ArchiveIndex(format, entries), password, file.lastModified()).also { archive = it }
        } catch (e: ArchivePasswordException) {
            requestPassword(file.name, wrong = password != null,
                onSubmit = { pw -> viewModelScope.launch { if (openArchive(path, pw) != null) load() } },
                onCancel = { if (location is Location.InArchive) navigate(parentOf(Location.InArchive(path, ""))!!) },
            )
            null
        }
    }

    private fun requestPassword(name: String, wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
        dialog = BrowserDialog.Password(name, wrong,
            onSubmit = { dialog = null; onSubmit(it) },
            onCancel = { dialog = null; onCancel() },
        )
    }

    private fun applyFilterAndSort() {
        val query = search?.trim().orEmpty()
        items = rawItems
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .sortedWith(settings.sort.comparator())
        // Drop selected paths that are no longer listed, e.g. after a delete.
        val present = rawItems.mapTo(HashSet()) { it.path }
        if (selection.any { it !in present }) selection = selection.filterTo(HashSet()) { it in present }
    }

    // ------------------------------------------------------------ search, sort, view

    fun openSearch() {
        search = ""
    }

    fun updateSearch(query: String) {
        search = query
        applyFilterAndSort()
    }

    fun closeSearch() {
        search = null
        applyFilterAndSort()
    }

    fun setSort(sort: SortOptions) = viewModelScope.launch { settingsRepo.setSort(sort) }
    fun setShowHidden(value: Boolean) = viewModelScope.launch { settingsRepo.setShowHidden(value) }
    fun setGridView(value: Boolean) = viewModelScope.launch { settingsRepo.setGridView(value) }
    fun setEditorWordWrap(value: Boolean) = viewModelScope.launch { settingsRepo.setEditorWordWrap(value) }
    fun setEditorLineNumbers(value: Boolean) = viewModelScope.launch { settingsRepo.setEditorLineNumbers(value) }
    fun setEditorFontSize(value: Float) = viewModelScope.launch { settingsRepo.setEditorFontSize(value.coerceIn(8f, 32f)) }

    // ------------------------------------------------------------ selection

    fun toggleSelection(item: FileItem) {
        selection = if (item.path in selection) selection - item.path else selection + item.path
    }

    fun selectAll() {
        selection = items.mapTo(HashSet()) { it.path }
    }

    fun clearSelection() {
        selection = emptySet()
    }

    val selectedItems: List<FileItem> get() = items.filter { it.path in selection }

    // ------------------------------------------------------------ clipboard

    fun copySelection(cut: Boolean) {
        val selected = selectedItems
        if (selected.isEmpty()) return
        clipboard = when (val loc = location) {
            is Location.Disk -> Clipboard.Files(selected.map { File(it.path) }, cut)
            is Location.InArchive -> {
                val session = archive ?: return
                Clipboard.Entries(session.file, session.format, session.password, selected.map { it.path }, loc.dir)
            }
        }
        clearSelection()
        val verb = when {
            location is Location.InArchive -> "ready to extract"
            cut -> "ready to move"
            else -> "ready to copy"
        }
        message("${selected.size} ${if (selected.size == 1) "item" else "items"} $verb. Open a folder and tap Paste.")
    }

    fun clearClipboard() {
        clipboard = null
    }

    val canPaste: Boolean get() = clipboard != null && location is Location.Disk

    fun paste() {
        val clip = clipboard ?: return
        val dest = (location as? Location.Disk)?.path?.let(::File) ?: return
        when (clip) {
            is Clipboard.Files -> runOperation(if (clip.cut) "Moving" else "Copying") { progress ->
                val done = if (clip.cut) FileOps.move(clip.files, dest, progress) else FileOps.copy(clip.files, dest, progress)
                if (clip.cut) withContext(Dispatchers.Main) { clipboard = null }
                "${if (clip.cut) "Moved" else "Copied"} ${plural(done.size, "item")}"
            }
            is Clipboard.Entries -> extract(clip.archive, clip.format, clip.password, dest, clip.entries, clip.baseDir) {
                clipboard = null
            }
        }
    }

    // ------------------------------------------------------------ file operations

    fun createFolder(name: String) = fileAction {
        val dir = currentDir() ?: return@fileAction
        FileOps.createFolder(dir, name)
    }

    fun createFile(name: String) = fileAction {
        val dir = currentDir() ?: return@fileAction
        val file = FileOps.createFile(dir, name)
        withContext(Dispatchers.Main) { showDetail(DetailTarget.Disk(file.path)) }
    }

    fun rename(item: FileItem, newName: String) = fileAction {
        val renamed = FileOps.rename(File(item.path), newName)
        withContext(Dispatchers.Main) {
            // Keep the detail pane pointing at the file under its new name.
            if ((detail?.target as? DetailTarget.Disk)?.path == item.path && detail?.isDirty != true) {
                showDetail(DetailTarget.Disk(renamed.path))
            }
            clearSelection()
        }
    }

    fun delete(items: List<FileItem>) {
        val files = items.map { File(it.path) }
        clearSelection()
        runOperation("Deleting") { progress ->
            FileOps.delete(files, progress)
            withContext(Dispatchers.Main) {
                val open = (detail?.target as? DetailTarget.Disk)?.path
                if (open != null && files.any { open == it.path || open.startsWith(it.path + "/") }) closeDetail()
            }
            "Deleted ${plural(files.size, "item")}"
        }
    }

    fun compress(files: List<File>, name: String, format: ArchiveFormat) {
        val dir = currentDir() ?: return
        clearSelection()
        runOperation("Compressing") { progress ->
            val target = FileOps.uniqueTarget(dir, "$name.${format.extension}")
            Archives.create(target, format, files, progress)
            "Created ${target.name} (${Format.size(target.length())})"
        }
    }

    /** Opens the extract dialog for an archive on disk, or for the entries selected inside one. */
    fun promptExtract(archivePath: String, entries: List<String>? = null, baseDir: String = "") {
        viewModelScope.launch {
            val file = File(archivePath)
            val session = try {
                if (archive?.file?.path == archivePath) archive else openArchive(archivePath)
            } catch (e: IOException) {
                message(e.message ?: "Could not read archive")
                null
            } ?: return@launch
            // A single top-level item can go straight next to the archive without cluttering it.
            val roots = (entries ?: session.index.list("").map { it.path })
                .map { (if (baseDir.isEmpty()) it else it.removePrefix("$baseDir/")).substringBefore('/') }
                .toSet()
            dialog = BrowserDialog.Extract(file, session.format, entries, baseDir, singleRoot = roots.size == 1)
        }
    }

    /** Extracts into [dest], creating it if needed. [onDone] runs on the main thread after success. */
    fun extract(
        archiveFile: File,
        format: ArchiveFormat,
        password: String?,
        dest: File,
        entries: List<String>?,
        baseDir: String,
        onDone: () -> Unit = {},
    ) {
        clearSelection()
        runOperation(
            "Extracting",
            onPassword = { pw ->
                if (archive?.file == archiveFile) archive = archive?.copy(password = pw)
                extract(archiveFile, format, pw, dest, entries, baseDir, onDone)
            },
            archiveName = archiveFile.name,
            hadPassword = password != null,
        ) { progress ->
            val count = Archives.extract(archiveFile, format, dest, password, entries, baseDir, progress)
            withContext(Dispatchers.Main) {
                onDone()
                message("Extracted ${plural(count, "file")} to ${dest.name}", "Open") { navigate(Location.Disk(dest.path)) }
            }
            null
        }
    }

    /** Extract dialog confirmed. [folderName] is null to extract straight into the archive's folder. */
    fun confirmExtract(dialog: BrowserDialog.Extract, folderName: String?) {
        val parent = dialog.archive.parentFile ?: return
        val dest = if (folderName == null) parent else FileOps.uniqueTarget(parent, folderName)
        val password = archive?.takeIf { it.file == dialog.archive }?.password
        extract(dialog.archive, dialog.format, password, dest, dialog.entries, dialog.baseDir)
    }

    fun planAutoSort(mode: AutoSortMode) {
        val dir = currentDir() ?: return
        viewModelScope.launch {
            val plan = withContext(Dispatchers.IO) { AutoSorter.plan(dir, mode, settings.showHidden) }
            dialog = if (plan.moves.isEmpty()) {
                message("Nothing to sort: there are no loose files here")
                null
            } else {
                BrowserDialog.AutoSortPreview(plan)
            }
        }
    }

    fun runAutoSort(plan: AutoSorter.Plan) {
        runOperation("Sorting") { progress ->
            val result = AutoSorter.execute(plan, progress)
            withContext(Dispatchers.Main) {
                message("Sorted ${plural(result.moved.size, "file")} into ${plural(plan.summary.size, "folder")}", "Undo") {
                    viewModelScope.launch {
                        val restored = withContext(Dispatchers.IO) { AutoSorter.undo(result) }
                        message("Restored ${plural(restored, "file")}")
                        load()
                    }
                }
            }
            null
        }
    }

    fun share(items: List<FileItem>) {
        Launch.share(getApplication(), items.map { File(it.path) })
        clearSelection()
    }

    private fun currentDir(): File? = (location as? Location.Disk)?.path?.let(::File)

    /** Runs a quick file action off the main thread, reporting errors, then reloads. */
    private fun fileAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: IOException) {
                message(e.message ?: "Something went wrong")
            }
            load()
        }
    }

    /**
     * Runs a long job with the progress dialog. [block] returns the message to
     * show when it finishes. When an archive turns out to need a password,
     * [onPassword] is offered the password the user types.
     */
    private fun runOperation(
        title: String,
        onPassword: ((String) -> Unit)? = null,
        archiveName: String = "",
        hadPassword: Boolean = false,
        block: suspend (Progress) -> String?,
    ) {
        if (operationJob?.isActive == true) {
            message("Please wait for the current task to finish")
            return
        }
        operationJob = viewModelScope.launch {
            _operation.value = OperationState(title, 0, 0, "")
            try {
                val result = withContext(Dispatchers.IO) {
                    val job = coroutineContext.job
                    val progress = Progress(isActive = { job.isActive }) { done, total, current ->
                        _operation.value = OperationState(title, done, total, current)
                    }
                    block(progress)
                }
                result?.let { message(it) }
            } catch (e: CancellationException) {
                message("$title cancelled")
            } catch (e: ArchivePasswordException) {
                if (onPassword != null) {
                    requestPassword(archiveName, wrong = hadPassword || e.wrongPassword, onSubmit = onPassword, onCancel = {})
                } else {
                    message(e.message ?: "Password required")
                }
            } catch (e: Exception) {
                message(e.message ?: "$title failed")
            } finally {
                _operation.value = null
                load()
            }
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    // ------------------------------------------------------------ detail pane

    /** Opens [target] in the detail pane, asking first if the current file has unsaved edits. */
    fun showDetail(target: DetailTarget) {
        val current = detail
        if (current != null && current.target != target && current.isDirty) {
            val editor = (current.content as DetailContent.Text).editor
            dialog = BrowserDialog.UnsavedChanges(
                current.target.name,
                onSave = {
                    dialog = null
                    viewModelScope.launch {
                        if (saveEditor(editor)) showDetail(target)
                    }
                },
                onDiscard = {
                    dialog = null
                    editor.revert()
                    showDetail(target)
                },
            )
            return
        }
        detailRequest = target.key
        if (current?.target == target && current.content !is DetailContent.Failed) return
        loadDetail(target)
    }

    fun consumeDetailRequest() {
        detailRequest = null
    }

    /** Called by the detail pane when the navigator shows a key we have no state for (e.g. after process death). */
    fun restoreDetail(key: String) {
        if (detail?.target?.key == key) return
        DetailTarget.fromKey(key)?.let(::loadDetail)
    }

    fun closeDetail() {
        detailJob?.cancel()
        detail = null
    }

    /** Re-opens the current file as text even though it did not look like text. */
    fun forceOpenAsText() {
        val target = detail?.target ?: return
        loadDetail(target, forceText = true)
    }

    suspend fun saveEditor(editor: EditorSession): Boolean = try {
        editor.save()
        message("Saved ${editor.file.name}")
        load()
        true
    } catch (e: IOException) {
        message("Could not save: ${e.message}")
        false
    }

    private fun loadDetail(target: DetailTarget, forceText: Boolean = false) {
        detailJob?.cancel()
        detail = DetailState(target, DetailContent.Loading)
        detailJob = viewModelScope.launch {
            val content = try {
                when (target) {
                    is DetailTarget.Disk -> withContext(Dispatchers.IO) { diskContent(File(target.path), forceText) }
                    is DetailTarget.Entry -> entryContent(target, forceText)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ArchivePasswordException) {
                val archiveName = (target as? DetailTarget.Entry)?.archivePath?.let { File(it).name }.orEmpty()
                requestPassword(archiveName, e.wrongPassword,
                    onSubmit = { pw ->
                        archive = archive?.copy(password = pw)
                        loadDetail(target, forceText)
                    },
                    onCancel = {},
                )
                DetailContent.Failed(e.message ?: "Password required")
            } catch (e: Exception) {
                DetailContent.Failed(e.message ?: "Could not open this file")
            }
            detail = DetailState(target, content)
        }
    }

    private fun diskContent(file: File, forceText: Boolean, readOnly: Boolean = false, entryRows: List<InfoRow>? = null): DetailContent {
        if (!file.exists()) throw IOException("\"${file.name}\" no longer exists")
        val kind = FileTypes.kindOf(file.name, file.isDirectory)
        val rows = entryRows ?: diskRows(file)
        val browsable = entryRows == null && file.isFile && ArchiveFormat.fromName(file.name) == null && ArchiveFormat.sniff(file) != null
        fun info(note: String? = null, canOpenAsText: Boolean = false) = DetailContent.Info(
            FileInfo(
                name = file.name,
                isDirectory = file.isDirectory,
                typeLabel = FileTypes.describe(file.name, file.isDirectory),
                rows = rows,
                localFile = file.takeIf { entryRows == null || it.isFile },
                browsableArchive = browsable,
                note = note,
                canOpenAsText = canOpenAsText,
            ),
        )
        if (file.isDirectory) return info()
        if (FileTypes.isPreviewableImage(file.name)) {
            return DetailContent.Image(file, info().info)
        }
        val textCandidate = forceText || kind == FileKind.TEXT || kind == FileKind.OTHER
        if (!textCandidate || browsable) return info()
        if (file.length() > TextDocument.EDIT_LIMIT) {
            val note = "This file is ${Format.size(file.length())}, too large for the built-in editor (limit ${Format.size(TextDocument.EDIT_LIMIT)})."
            return info(note = note.takeIf { kind == FileKind.TEXT })
        }
        val bytes = file.readBytes()
        if (!forceText && TextDocument.looksBinary(bytes)) {
            return info(note = "This does not look like a text file.", canOpenAsText = true)
        }
        val writable = !readOnly && file.canWrite()
        return DetailContent.Text(EditorSession(file, readOnly = !writable, TextDocument.decode(bytes)))
    }

    private fun diskRows(file: File): List<InfoRow> = buildList {
        add(InfoRow("Type", FileTypes.describe(file.name, file.isDirectory)))
        if (file.isDirectory) {
            val children = file.listFiles().orEmpty()
            add(InfoRow("Contains", "${plural(children.count { it.isFile }, "file")}, ${plural(children.count { it.isDirectory }, "folder")}"))
            add(InfoRow("Total size", Format.size(FileOps.totalSize(listOf(file)))))
        } else {
            add(InfoRow("Size", "${Format.size(file.length())} (${"%,d".format(file.length())} bytes)"))
            add(InfoRow("MIME type", Launch.mimeType(file.name)))
        }
        add(InfoRow("Modified", Format.date(file.lastModified())))
        add(InfoRow("Location", file.parent ?: "/"))
        add(InfoRow("Permissions", buildString {
            append(if (file.canRead()) "Read" else "No read")
            append(if (file.canWrite()) " · Write" else " · Read only")
        }))
    }

    /** Pulls an entry out of its archive into the cache so it can be previewed. */
    private suspend fun entryContent(target: DetailTarget.Entry, forceText: Boolean): DetailContent {
        // openArchive has already asked for the password when it returns null.
        val session = openArchive(target.archivePath) ?: return DetailContent.Failed("This archive is password protected")
        val parentDir = ArchiveIndex.parentOf(target.entryPath)
        val item = session.index.list(parentDir).firstOrNull { it.path == target.entryPath }
            ?: throw IOException("\"${target.name}\" is not in this archive")
        val rows = buildList {
            add(InfoRow("Type", FileTypes.describe(item.name, false)))
            add(InfoRow("Size", Format.size(item.size)))
            if (item.compressedSize >= 0) {
                add(InfoRow("Compressed", Format.size(item.compressedSize) + (Format.ratio(item.compressedSize, item.size)?.let { " · $it" } ?: "")))
            }
            add(InfoRow("Modified", Format.date(item.modified)))
            add(InfoRow("In archive", "${session.file.name} › ${target.entryPath}"))
        }
        val previewable = FileTypes.isPreviewableImage(item.name) || FileTypes.kindOf(item.name) in setOf(FileKind.TEXT, FileKind.OTHER) || forceText
        if (!previewable || item.size > PREVIEW_LIMIT) {
            return DetailContent.Info(
                FileInfo(item.name, false, FileTypes.describe(item.name, false), rows, null, false,
                    note = if (item.size > PREVIEW_LIMIT) "Too large to preview. Extract it to open." else null),
            )
        }
        val cached = File(previewCache, "${target.key.hashCode().toUInt()}/${item.name}")
        return withContext(Dispatchers.IO) {
            if (!cached.exists()) Archives.extractEntry(session.file, session.format, target.entryPath, cached, session.password)
            diskContent(cached, forceText, readOnly = true, entryRows = rows)
        }
    }

    // ------------------------------------------------------------ messages

    fun message(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _messages.trySend(UiMessage(text, actionLabel, action))
    }

    private fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"

    companion object {
        /** Largest archive entry pulled out automatically for a preview. */
        private const val PREVIEW_LIMIT = 64L * 1024 * 1024
    }
}
