package com.amirzregat.nabbash.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.amirzregat.nabbash.NabbashConfig
import com.amirzregat.nabbash.R
import com.amirzregat.nabbash.internal.ui.NabbashActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal const val TAG = "Nabbash"
internal const val DATA_DIR = "nabbash"
internal const val EXPORT_DIR = "nabbash_exports"

/** What is in force right now: code configuration with the in-app settings applied on top. */
internal class Effective(
    val retentionMs: Long,
    val maxStorageBytes: Long,
    val maxStorageMb: Int,
    val maxBodyChars: Int,
    val shakeToOpen: Boolean,
    val launcherShortcut: Boolean,
    val redactor: Redactor,
)

internal object NabbashRuntime {

    /** Null unless the host app is debuggable. Every public entry point checks this first. */
    @Volatile
    var core: Core? = null
        private set

    private val lock = Any()
    private val config = NabbashConfig()

    fun configure(block: NabbashConfig.() -> Unit) {
        try {
            synchronized(lock) { config.block() }
            core?.configChanged()
        } catch (t: Throwable) {
            Log.w(TAG, "configure failed", t)
        }
    }

    fun effective(settings: Settings): Effective = synchronized(lock) {
        val retention = settings.retentionMs
            ?: config.sessionRetention.inWholeMilliseconds
        val storageMb = (settings.maxStorageMb ?: config.maxStorageMb).coerceIn(1, 2048)
        Effective(
            retentionMs = retention.coerceIn(MIN_RETENTION_MS, MAX_RETENTION_MS),
            maxStorageBytes = storageMb * 1024L * 1024L,
            maxStorageMb = storageMb,
            maxBodyChars = config.maxBodyBytes.coerceIn(1_000, 2_000_000),
            shakeToOpen = settings.shakeToOpen ?: config.shakeToOpen,
            launcherShortcut = config.launcherShortcut,
            redactor = Redactor(
                extraHeaders = config.redactHeaders.toList(),
                extraBodyKeys = config.redactBodyKeys.toList(),
                redactHeaders = settings.redactHeaders,
                redactFields = settings.redactFields,
            ),
        )
    }

    /** Called once per process by [NabbashInitProvider]. Must never throw into the host app. */
    fun install(context: Context) {
        if (core != null) return
        try {
            val app = context.applicationContext ?: context
            val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (!debuggable) {
                // The real library ended up in a non-debuggable build. Stay off and make
                // sure nothing recorded by an earlier debuggable install is left behind.
                Thread({ wipe(app) }, "nabbash-wipe").apply { isDaemon = true }.start()
                Log.i(TAG, "Not a debuggable build: Nabbash is disabled.")
                return
            }
            synchronized(lock) {
                if (core == null) core = Core(app)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Nabbash could not start and stays disabled", t)
        }
    }

    private fun wipe(app: Context) {
        try {
            File(app.noBackupFilesDir, DATA_DIR).deleteRecursively()
            File(app.cacheDir, EXPORT_DIR).deleteRecursively()
            if (Build.VERSION.SDK_INT >= 24) app.deleteSharedPreferences(Settings.FILE)
        } catch (_: Throwable) {
        }
    }

    private const val MIN_RETENTION_MS = 60L * 60L * 1000L
    private const val MAX_RETENTION_MS = 365L * 24L * 60L * 60L * 1000L
}

/** Everything that exists only in a debuggable build. */
internal class Core(val app: Context) {

    val settings = Settings(app)
    val store: LogStore

    @Volatile
    var sessionId: Long = -1L
        private set

    @Volatile
    var effective: Effective = NabbashRuntime.effective(settings)
        private set

    /** True while the console is on screen, so a shake does not stack a second one. */
    @Volatile
    var consoleVisible: Boolean = false

    private val io = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "nabbash-io").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val pending = AtomicInteger(0)
    private var insertsSincePrune = 0
    private var resumed: WeakReference<Activity>? = null
    private val shake = ShakeDetector(app) { onShake() }

    init {
        val dir = File(app.noBackupFilesDir, DATA_DIR)
        dir.mkdirs()
        store = LogStore(app, File(dir, "nabbash.db"))
        io.execute {
            guarded("start session") {
                File(app.cacheDir, EXPORT_DIR).deleteRecursively()
                sessionId = store.createSession(System.currentTimeMillis(), appVersion(), deviceName())
            }
        }
        // Delayed so a Nabbash.configure call in Application.onCreate is in force first.
        io.schedule({ guarded("startup") { applyShortcut(); prune() } }, 5, TimeUnit.SECONDS)
        (app as? Application)?.registerActivityLifecycleCallbacks(Lifecycle())
    }

    // region recording

    fun recordMessage(time: Long, level: Int, label: String, message: String, throwable: Throwable?) {
        val max = effective.maxBodyChars
        val text = cut(message, max)
        val length = message.length
        submit {
            val r = effective.redactor
            val stack = throwable?.let { cut(r.text(stackTrace(it)), max) }
            store.insert(
                sessionId,
                LogEvent(
                    type = TYPE_MESSAGE,
                    time = time,
                    level = level.coerceIn(0, LEVEL_ERROR),
                    isError = level >= LEVEL_ERROR,
                    label = label.take(120),
                    message = withMarker(r.body(text, null), length, max),
                    error = stack,
                ),
            )
        }
    }

    fun recordNetwork(
        time: Long,
        method: String,
        url: String,
        requestHeaders: List<Pair<String, String>>,
        requestBody: String?,
        requestSize: Long?,
        status: Int?,
        responseHeaders: List<Pair<String, String>>,
        responseBody: String?,
        responseSize: Long?,
        durationMs: Long?,
        error: String?,
        requestContentType: String? = null,
        responseContentType: String? = null,
    ) {
        val max = effective.maxBodyChars
        // Cut on the caller's thread so a huge body is not kept alive in the queue.
        val reqBody = requestBody?.let { cut(it, max) }
        val resBody = responseBody?.let { cut(it, max) }
        val reqLength = requestBody?.length ?: 0
        val resLength = responseBody?.length ?: 0
        submit {
            val r = effective.redactor
            val reqType = requestContentType ?: headerValue(requestHeaders, "Content-Type")
            val resType = responseContentType ?: headerValue(responseHeaders, "Content-Type")
            store.insert(
                sessionId,
                LogEvent(
                    type = TYPE_NETWORK,
                    time = time,
                    level = if (error != null || (status ?: 0) >= 500) LEVEL_ERROR else 1,
                    isError = error != null || (status ?: 0) >= 400,
                    method = method.take(16).uppercase(),
                    url = r.url(cut(url, 8_000)),
                    status = status,
                    durationMs = durationMs,
                    requestContentType = reqType?.take(200),
                    requestHeaders = requestHeaders.take(200).map { (n, v) -> n.take(200) to r.text(r.header(n, cut(v, 8_000))) },
                    requestBody = reqBody?.let { withMarker(r.body(it, reqType), reqLength, max) },
                    requestSize = requestSize,
                    responseContentType = resType?.take(200),
                    responseHeaders = responseHeaders.take(200).map { (n, v) -> n.take(200) to r.text(r.header(n, cut(v, 8_000))) },
                    responseBody = resBody?.let { withMarker(r.body(it, resType), resLength, max) },
                    responseSize = responseSize,
                    error = error?.let { r.text(cut(it, 4_000)) },
                ),
            )
        }
    }

    private fun submit(work: () -> Unit) {
        // A runaway logging loop must not grow the queue without bound.
        if (pending.get() >= MAX_PENDING) return
        pending.incrementAndGet()
        try {
            io.execute {
                try {
                    work()
                    if (++insertsSincePrune >= PRUNE_EVERY) prune()
                } catch (t: Throwable) {
                    Log.w(TAG, "could not store event", t)
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (_: Throwable) {
            pending.decrementAndGet()
        }
    }

    // endregion

    // region maintenance (all on the io thread)

    fun configChanged() {
        effective = NabbashRuntime.effective(settings)
        io.execute {
            guarded("apply config") {
                applyShortcut()
                prune()
            }
        }
        refreshShake()
    }

    fun clearAll(done: () -> Unit) {
        io.execute {
            guarded("clear") {
                store.clearAll(sessionId)
                File(app.cacheDir, EXPORT_DIR).deleteRecursively()
            }
            done()
        }
    }

    /** Runs [work] after everything queued so far has been written. */
    fun afterPendingWrites(work: () -> Unit) {
        io.execute { guarded("task", work) }
    }

    private fun prune() {
        insertsSincePrune = 0
        if (sessionId < 0) return
        val e = effective
        store.prune(sessionId, System.currentTimeMillis() - e.retentionMs, e.maxStorageBytes)
    }

    private fun applyShortcut() {
        if (effective.launcherShortcut) {
            val intent = Intent(app, NabbashActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val shortcut = ShortcutInfoCompat.Builder(app, SHORTCUT_ID)
                .setShortLabel(app.getString(R.string.nabbash_shortcut_label))
                .setLongLabel(app.getString(R.string.nabbash_shortcut_label))
                .setIcon(IconCompat.createWithResource(app, R.drawable.nabbash_ic_shortcut))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.addDynamicShortcuts(app, listOf(shortcut))
        } else {
            ShortcutManagerCompat.removeDynamicShortcuts(app, listOf(SHORTCUT_ID))
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "$what failed", t)
        }
    }

    // endregion

    // region console

    fun openConsole(context: Context) {
        try {
            if (consoleVisible) return
            val intent = Intent(context, NabbashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "could not open the console", t)
        }
    }

    private fun onShake() {
        val activity = resumed?.get() ?: return
        if (activity.isFinishing) return
        openConsole(activity)
    }

    private fun refreshShake() {
        val activity = resumed?.get()
        try {
            if (activity != null && activity !is NabbashActivity && effective.shakeToOpen) shake.start() else shake.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "shake detection unavailable", t)
        }
    }

    /** The accelerometer is only listened to while one of the app's own activities is in front. */
    private inner class Lifecycle : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
            refreshShake()
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
            refreshShake()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    // endregion

    fun appLabel(): String = try {
        app.applicationInfo.loadLabel(app.packageManager).toString()
    } catch (_: Throwable) {
        app.packageName
    }

    fun appVersion(): String = try {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName ?: "?"} ($code)"
    } catch (_: Throwable) {
        "?"
    }

    fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        return "$name · Android ${Build.VERSION.RELEASE}"
    }

    private companion object {
        const val MAX_PENDING = 2_000
        const val PRUNE_EVERY = 200
        const val SHORTCUT_ID = "nabbash_console"

        fun cut(text: String, max: Int): String = if (text.length <= max) text else text.substring(0, max)

        fun withMarker(text: String, originalLength: Int, max: Int): String =
            if (originalLength <= max) text
            else "$text\n… [cut by Nabbash: only the first $max characters are stored]"

        fun headerValue(headers: List<Pair<String, String>>, name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        fun stackTrace(t: Throwable): String {
            val out = StringWriter()
            t.printStackTrace(PrintWriter(out))
            return out.toString()
        }
    }
}
