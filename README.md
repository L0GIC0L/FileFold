# FileFold

A Material You file explorer for Android 17, built for foldables.

FileFold is written in Kotlin with Jetpack Compose and Material 3. It targets
Android 17 (API 37) and runs on Android 11 (API 30) and newer.

## Features

**Browsing**
- List and grid views, with thumbnails for photos.
- Breadcrumb path bar, per-folder search, and pull to refresh.
- Each folder remembers its scroll position when you come back to it.
- Storage locations: internal storage, any SD card or USB drive, and the
  standard folders (Downloads, Documents, Pictures, Camera, Music, Movies).
- Colours follow your wallpaper (Material You), in light and dark theme.

**Sorting**
- Sort by name, date, size or type, ascending or descending, with folders
  first or mixed in.
- Names sort naturally, so `file2` comes before `file10`.
- Your choice is saved and applied automatically to every folder you open.
- **Auto-sort into folders** tidies a messy folder (Downloads, say). It moves
  loose files into `Images`, `Videos`, `Audio`, `Documents`, `Archives`,
  `Apps` and `Other`, or into one folder per month. You see a preview first,
  nothing is ever overwritten, and one tap on **Undo** puts everything back.

**File operations**
- Copy, move, delete, rename, share, new folder and new text file.
- Long jobs show a progress dialog that you can cancel at any point. A
  cancelled copy or archive never leaves a half-written file behind.
- Name clashes never overwrite anything: the new file becomes `report (1).pdf`.

**Archives**
- Archives open like folders, so you can browse them without extracting.
  A banner shows the format, the file count, the total size, and how much
  space the compression saves.
- Text files and images inside an archive preview in place.
- Extract everything, extract a selection, or copy entries out of the
  archive and paste them into any folder.
- When an archive holds a single top-level item, extraction puts it straight
  next to the archive instead of wrapping it in a new folder.
- APK, JAR, DOCX, XLSX and EPUB files are ZIP files inside. Open one and tap
  **Browse contents** to look inside it.
- Password-protected 7z and RAR archives ask for the password.
- Archives that try to write outside the destination folder ("zip slip"
  attacks) are blocked.

| Format | Browse and extract | Create |
|---|:---:|:---:|
| ZIP (also APK, JAR, DOCX, EPUB…) | ✓ | ✓ |
| 7z (including password-protected) | ✓ | ✓ |
| TAR, TAR.GZ / TGZ, TAR.BZ2, TAR.XZ | ✓ | ✓ |
| RAR 4 and RAR 5 (including password-protected) | ✓ | — |
| TAR.LZMA, TAR.Z, GZ, BZ2, XZ, LZMA, Z | ✓ | — |
| AR / DEB, CPIO, ARJ | ✓ | — |

Creating RAR files needs WinRAR's proprietary compressor, so no free app can
do it.

**Text editor**
- Opens source code, config, log and other plain text files up to 2 MB. Files
  without an extension, such as `README` or `Makefile`, open too, as long as
  they hold text.
- Undo and redo, find and replace (match case, replace all), word wrap, line
  numbers, adjustable text size.
- A status bar shows the line, column, line count, encoding and line endings.
- Saving keeps the file's encoding (UTF-8, UTF-16 or Windows-1252), its byte
  order mark and its Windows (CRLF) line endings exactly as they were. Saves
  are atomic: the new text goes to a temporary file that then replaces the
  original, so a crash mid-save cannot leave a half-written file.
- With a hardware keyboard: **Ctrl+S** saves, **Ctrl+F** opens find, and
  **Ctrl+G** / **Ctrl+Shift+G** jump to the next or previous match.
- Asks before you leave a file with unsaved changes.

## Foldable support

The layout follows the size and shape of the window, not the device model.

| State | Layout |
|---|---|
| Folded, or a normal phone | One pane at a time. Storage locations open from the menu button. |
| Unfolded (600 dp wide and up) | The file list and the open file side by side, with a divider you can drag to resize them. |
| Unfolded and wide (840 dp and up) | As above, with a navigation rail for storage locations. |
| Book posture (half open, hinge vertical) | The two panes sit on either side of the hinge, never across it. |
| Tabletop posture (half open, hinge horizontal) | The editor and image viewer put the content on the upright half and every control on the flat half, like a small laptop. |

Folding or unfolding mid-task keeps everything: the current folder, scroll
position, selection, clipboard, and any unsaved text in the editor. The
activity is never restarted for a size change.

## Building

You need JDK 17 or newer and the Android SDK, with platform 37 installed.

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests for the file, archive, sort and text code
./gradlew installDebug           # build and install on a connected device
```

Every push to GitHub runs the same build and tests. The debug APK is attached
to the run as an artifact, so you can install it without building anything.

On first launch FileFold asks for **All files access**. Android requires this
permission for an app to manage files outside its own folder.

## How the code is laid out

```
app/src/main/java/io/github/l0gic0l/filefold/
├── core/       File operations, sorting, auto-sort, text encoding. Plain Kotlin, unit tested.
├── archive/    Archive formats, reading/writing/extracting, and the in-archive folder tree.
├── data/       Saved settings (DataStore).
├── util/       Formatting, storage volumes, handing files to other apps.
└── ui/
    ├── FileFoldApp.kt   Adaptive layout: panes, drawer or rail, hinge handling.
    ├── browser/         File list, dialogs, and the ViewModel holding all browsing state.
    ├── detail/          Text editor, image viewer, file details.
    └── components/      File icons and the tabletop posture layout.
```

Everything that touches the disk lives in `core/` and `archive/`, which have
no Android dependencies, so the tests run in seconds on any computer. The
archive work uses Apache Commons Compress, XZ for Java, and junrar.

## Known limitations

- Password-protected ZIP files are listed but cannot be extracted. Commons
  Compress does not support ZIP encryption.
- Files over 2 MB are too large for the editor. They open in another app instead.
- Archives are read-only: you can create new ones, but not add files to an
  existing archive.

## Licence

MIT. See [LICENSE](LICENSE).
