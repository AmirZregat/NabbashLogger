package com.amirzregat.nabbash.internal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import java.io.File

/**
 * Gets logs off the device, and only ever because the user asked: through the system
 * share sheet or the clipboard. Nabbash itself has no network code.
 */
internal object Sharing {

    private const val MAX_INLINE_CHARS = 40_000
    private const val MAX_CLIPBOARD_CHARS = 100_000
    private const val STALE_AFTER_MS = 10 * 60 * 1000L

    /** Writes the chosen sessions to a file in the export folder. Call off the main thread. */
    fun exportSessions(core: Core, sessions: List<SessionRow>, asJson: Boolean): File {
        val now = System.currentTimeMillis()
        val stamps = Stamps()
        val meta = ExportMeta(core.appLabel(), core.app.packageName, core.deviceName(), now)
        val file = newExportFile(core, stamps.fileName(now), if (asJson) "json" else "txt")
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            if (asJson) Exporter.json(out, meta, sessions, stamps, core.store::forEachEvent)
            else Exporter.text(out, meta, sessions, stamps, core.store::forEachEvent)
        }
        return file
    }

    fun shareFile(activity: Activity, file: File, asJson: Boolean) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".nabbash.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(if (asJson) "application/json" else "text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            // Read access to this one file, for the app the user picks, and nothing else.
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(file.name, uri)
        activity.startActivity(Intent.createChooser(intent, "Share logs"))
    }

    /** One event ready to share: plain text when it is small, a file when it is not. */
    class EventShare(val text: String?, val file: File?)

    /** Builds the share for one event. Call off the main thread. */
    fun prepareEvent(core: Core, event: LogEvent): EventShare {
        val stamps = Stamps()
        val text = Exporter.eventText(event, stamps)
        if (text.length <= MAX_INLINE_CHARS) return EventShare(text, null)
        val file = newExportFile(core, stamps.fileName(System.currentTimeMillis()) + "_entry", "txt")
        file.writeText(text, Charsets.UTF_8)
        return EventShare(null, file)
    }

    fun shareEvent(activity: Activity, share: EventShare) {
        val file = share.file
        if (file != null) {
            shareFile(activity, file, asJson = false)
        } else {
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, share.text.orEmpty())
            activity.startActivity(Intent.createChooser(intent, "Share log entry"))
        }
    }

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, text.take(MAX_CLIPBOARD_CHARS))
        // Keeps the content out of the clipboard preview on Android 13 and later.
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
    }

    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private fun newExportFile(core: Core, stamp: String, extension: String): File {
        val dir = File(core.app.cacheDir, EXPORT_DIR)
        dir.mkdirs()
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        val app = core.appLabel().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40).ifEmpty { "app" }
        return File(dir, "nabbash_${app}_$stamp.$extension")
    }
}
