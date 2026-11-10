package io.github.l0gic0l.filefold.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.l0gic0l.filefold.core.FileTypes
import java.io.File

/** Hands files to other apps. */
object Launch {

    fun mimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileTypes.extensionOf(name)) ?: "application/octet-stream"

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun openWith(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeType(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        start(context, Intent.createChooser(intent, "Open with"))
    }

    fun share(context: Context, files: List<File>) {
        val shareable = files.filter { it.isFile }
        if (shareable.isEmpty()) {
            Toast.makeText(context, "Folders cannot be shared directly. Compress them first.", Toast.LENGTH_LONG).show()
            return
        }
        val uris = ArrayList(shareable.map { uriFor(context, it) })
        val types = shareable.map { mimeType(it.name) }.distinct()
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = types.singleOrNull() ?: "*/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, "Share"))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, "This file cannot be shared from its current location", Toast.LENGTH_SHORT).show()
        }
    }
}
