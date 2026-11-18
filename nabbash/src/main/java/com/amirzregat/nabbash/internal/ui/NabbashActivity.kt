package com.amirzregat.nabbash.internal.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.amirzregat.nabbash.internal.Core
import com.amirzregat.nabbash.internal.LogEvent
import com.amirzregat.nabbash.internal.NabbashRuntime
import com.amirzregat.nabbash.internal.SessionRow
import com.amirzregat.nabbash.internal.Sharing
import com.amirzregat.nabbash.internal.TAG

/**
 * The console. Declared `exported="false"`, so only the host app itself (and its own
 * launcher shortcut) can start it. It reads nothing from the intent that starts it.
 */
internal class NabbashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = NabbashRuntime.core
        if (core == null) {
            // Not a debuggable build: there is nothing to show.
            finish()
            return
        }
        try {
            enableEdgeToEdge()
        } catch (t: Throwable) {
            Log.w(TAG, "edge-to-edge unavailable", t)
        }
        setContent { NabbashApp(core, this) }
    }

    override fun onResume() {
        super.onResume()
        NabbashRuntime.core?.consoleVisible = true
    }

    override fun onPause() {
        NabbashRuntime.core?.consoleVisible = false
        super.onPause()
    }
}

internal sealed interface Screen {
    /** [sessionId] null means the session that is being recorded now. */
    data class Console(val sessionId: Long?) : Screen
    data class Detail(val eventId: Long) : Screen
    data object Sessions : Screen
    data object Settings : Screen
}

internal class Nav(val push: (Screen) -> Unit, val pop: () -> Unit)

/** Exports off the main thread, then hands the result to the system share sheet. */
internal class ShareController(private val activity: Activity, private val core: Core) {

    var busy by mutableStateOf(false)
        private set

    fun sessions(rows: List<SessionRow>, asJson: Boolean) {
        if (busy || rows.isEmpty()) return
        busy = true
        // Queued behind pending writes, so the export contains everything logged so far.
        core.afterPendingWrites {
            val file = try {
                Sharing.exportSessions(core, rows, asJson)
            } catch (t: Throwable) {
                Log.w(TAG, "export failed", t)
                null
            }
            finish { if (file == null) toast("Could not export the logs") else Sharing.shareFile(activity, file, asJson) }
        }
    }

    fun event(event: LogEvent) {
        if (busy) return
        busy = true
        core.afterPendingWrites {
            val share = try {
                Sharing.prepareEvent(core, event)
            } catch (t: Throwable) {
                Log.w(TAG, "export failed", t)
                null
            }
            finish { if (share == null) toast("Could not share this entry") else Sharing.shareEvent(activity, share) }
        }
    }

    fun copy(label: String, text: String) {
        try {
            Sharing.copy(activity, label, text)
            // Android 13 and later show their own confirmation.
            if (Build.VERSION.SDK_INT < 33) toast("Copied")
        } catch (t: Throwable) {
            Log.w(TAG, "copy failed", t)
            toast("Could not copy")
        }
    }

    private fun finish(then: () -> Unit) {
        activity.runOnUiThread {
            busy = false
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                then()
            } catch (t: Throwable) {
                Log.w(TAG, "share failed", t)
                toast("No app available to share with")
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun NabbashApp(core: Core, activity: Activity) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Console(null)) }
    val share = remember { ShareController(activity, core) }
    val nav = remember {
        Nav(
            push = { stack.add(it) },
            pop = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else activity.finish() },
        )
    }
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = stack.size > 1) { nav.pop() }

    NabbashTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalColors.current.bg)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val screen = stack.last()
            // Keeps each screen's filter, search and scroll position while another is on top.
            holder.SaveableStateProvider("${stack.size}:$screen") {
                when (screen) {
                    is Screen.Console -> ConsoleScreen(core, screen.sessionId, isRoot = stack.size == 1, nav = nav, share = share)
                    is Screen.Detail -> DetailScreen(core, screen.eventId, nav, share)
                    Screen.Sessions -> SessionsScreen(core, nav, share)
                    Screen.Settings -> SettingsScreen(core, nav)
                }
            }
        }
    }
}
