# FileFold

A Material You file explorer for Android 17, built for foldables.

FileFold is written in Kotlin with Jetpack Compose and Material 3. It targets
Android 17 (API 37) and runs on Android 11 (API 30) and newer.

## Features

**Browsing**
- List and grid views, with thumbnails for photos and videos.
- Breadcrumb path bar and pull to refresh.
- **Search** looks through the current folder and everything below it, and
  lists matches as it finds them. Type words in any order, or a pattern such
  as `IMG_*.jpg`. Chips narrow it to a kind of file (images, videos, audio,
  documents, code, archives, apps), to files changed in the last 7 days, or
  to files over 50 MB. A chip on its own works too: tap **Images** at the top
  of storage to see every picture. It also searches inside an open archive.
- **Recent files** in the drawer lists what changed in the last week, newest
  first.
- **Favourites:** pin any folder to the drawer from the menu.
- **Storage usage** (in the menu) adds up what each sub-folder holds, puts
  the biggest first with a bar for its share, and shows how full the volume
  is. It stays on as you move between folders. The drawer shows free space
  for each volume.
- **Tabs:** keep several folders or archives open at once. Use **New tab** in
  the menu, or select a folder and choose **Open in new tab**. Each tab
  remembers its own scroll positions, and your tabs are still there next time
  you open the app.
- Each folder remembers its scroll position when you come back to it, and
  folders you have already visited appear instantly.
- **Predictive back:** as you swipe back, the current folder shrinks and slides
  away and the parent folder shows behind it, so you can see where you are
  going. Let go early to stay put. Going into a folder slides the other way.
- Storage locations: internal storage, any SD card or USB drive, and the
  standard folders (Downloads, Documents, Pictures, Camera, Music, Movies).
- Colours follow your wallpaper (Material You), in light and dark theme.
- 27 file types each get their own icon and colour: images, design files,
  video, audio, PDF, documents, spreadsheets, presentations, e-books, text,
  source code, web files, data and config, subtitles, archives, disk images,
  Android apps, programs, fonts, databases, contacts, calendars, 3D models,
  torrents, and keys and certificates.

**Settings**
- Theme: light, dark or follow the system, with an optional pure black dark
  theme for OLED screens.
- Colours: from your wallpaper (Material You, Android 12+), or pick a colour and
  a style (Tonal, Vibrant, Expressive, Faithful, Neutral, Monochrome). A picked
  colour is turned into a full Material 3 scheme with Google's own colour
  algorithm, so contrast stays right in light and dark.
- File icon colours: change the colour of any file type, and choose whether
  they blend toward your theme colour (Material colour harmonisation).
- Browsing, editor and auto-sort options.

**Sorting**
- Sort by name, date, size or type, ascending or descending, with folders
  first or mixed in.
- Names sort naturally, so `file2` comes before `file10`.
- Your choice is saved and applied automatically to every folder you open.
- **Auto-sort into folders** tidies a messy folder (Downloads, say). It moves
  loose files into `Images`, `Videos`, `Audio`, `Documents`, `Code`,
  `Archives`, `Apps` and `Other`, or into one folder per month. You see a
  preview first, nothing is ever overwritten, and one tap on **Undo** puts
  everything back.
- In Settings you can switch off any group (its files stay where they are),
  rename its folder, nest it (`Sorted/Images`), or send two groups to the same
  folder. Auto-sort can also tidy sub-folders into `Misc` or a folder you name.
  The folders it sorts into are never moved themselves.

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

**Trash**
- Deleting moves items to the trash, and a snackbar offers **Undo**. The
  trash is in the drawer: restore items to where they were, delete them for
  good, or empty it. Items are removed automatically after 30 days.
- Moving to the trash is instant, because each storage volume has its own
  trash folder (`.FileFoldTrash`, hidden, and ignored by gallery apps).
- The delete dialog can skip the trash, and Settings can turn it off.

**Batch rename**
- Select several items and choose **Rename all**. The new name is a template:
  `{name}` is the old name, `{n}` a counter and `{date}` the day the file was
  last changed, so `Holiday {n}` gives `Holiday 01.jpg`, `Holiday 02.jpg`.
  Text can also be found and replaced in the old names. Extensions are kept,
  a preview shows the result, and the rename can be undone.

**Viewers**
- **PDF:** pages in a scrolling column with zoom, drawn by Android's own
  renderer.
- **Audio and video:** a built-in player with seek and 10-second skip. Formats
  Android cannot play offer **Open with** instead.
- **Pictures:** pinch and double-tap zoom, and arrows to step through the
  other pictures in the folder.
- **Bytes:** any file can be shown as a hex dump, however large.
- **Checksums:** CRC32, MD5, SHA-1 and SHA-256 of a file, with a box to paste
  an expected value and see whether it matches.
- **Details** include picture dimensions, audio and video length, resolution
  and tags, and for an APK the app's name, package, version and the Android
  version it needs, with an **Install** button.
- PDFs, audio and video inside an archive preview without extracting it.

**Keyboard shortcuts** (hardware keyboard): **Ctrl+A** select all, **Ctrl+C**
/ **X** / **V** copy, cut, paste, **Ctrl+F** search, **Ctrl+T** / **W** new
and close tab, **Ctrl+N** new folder, **Ctrl+H** hidden files, **Delete**,
**F2** rename, **F5** refresh.

**Text editor**
- Opens source code, config, log and other plain text files up to 5 MB, and
  stays quick with big files: only the paragraph you edit is re-measured and
  only the lines on screen are drawn. Files
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

Every push to GitHub runs the tests and builds the optimised release APK. It is
attached to the run as the **FileFold-apk** artifact, so you can install it
without building anything. Install the release build rather than a debug one:
debug builds of Compose apps are much slower, and scrolling stutters.

All builds are signed with a development key kept in the repository
(`app/signing/dev.keystore`), so each new build installs over the last one and
keeps your settings. The key is not a secret. Make a private key and use it
before publishing to an app store.

On first launch FileFold asks for **All files access**. Android requires this
permission for an app to manage files outside its own folder.

## How the code is laid out

```
app/src/main/java/io/github/l0gic0l/filefold/
├── core/       File operations, sorting, search, trash, batch rename, checksums, auto-sort,
│               text encoding. Plain Kotlin, unit tested.
├── archive/    Archive formats, reading/writing/extracting, and the in-archive folder tree.
├── data/       Saved settings (DataStore).
├── util/       Formatting, storage volumes, handing files to other apps.
└── ui/
    ├── FileFoldApp.kt   Adaptive layout: panes, drawer or rail, hinge handling.
    ├── browser/         File list, dialogs, and the ViewModel holding all browsing state.
    ├── detail/          Text editor, image, PDF, media and hex viewers, file details.
    └── components/      File icons and the tabletop posture layout.
```

Everything that touches the disk lives in `core/` and `archive/`, which have
no Android dependencies, so the tests run in seconds on any computer. The
archive work uses Apache Commons Compress, XZ for Java, and junrar.

## Known limitations

- Password-protected ZIP files are listed but cannot be extracted. Commons
  Compress does not support ZIP encryption.
- Files over 5 MB are too large for the editor. They open in another app instead.
- Archives are read-only: you can create new ones, but not add files to an
  existing archive.
- The player handles what Android can decode (MP4, MKV, WebM, MP3, FLAC, OGG,
  WAV and similar). Other formats open in another app.
- Password-protected PDFs open in another app.
- A search stops at 2,000 matches and asks you to narrow it down.

## Licence

MIT. See [LICENSE](LICENSE).
