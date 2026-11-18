package com.amirzregat.nabbash.internal

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * SQLite storage in the app's private no-backup directory.
 *
 * Every statement uses bound arguments. `secure_delete` makes SQLite overwrite
 * deleted rows with zeros, so pruned or cleared logs do not linger in the file.
 */
internal class LogStore(context: Context, file: File) :
    SQLiteOpenHelper(context, file.absolutePath, null, SCHEMA_VERSION) {

    /** Bumped after every write so the console can refresh. */
    val version = MutableStateFlow(0L)

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                app_version TEXT NOT NULL,
                device TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                type INTEGER NOT NULL,
                time INTEGER NOT NULL,
                level INTEGER NOT NULL,
                is_error INTEGER NOT NULL,
                size INTEGER NOT NULL,
                label TEXT,
                message TEXT,
                method TEXT,
                url TEXT,
                status INTEGER,
                duration_ms INTEGER,
                res_size INTEGER,
                error TEXT,
                req_content_type TEXT,
                req_headers TEXT,
                req_body TEXT,
                req_size INTEGER,
                res_content_type TEXT,
                res_headers TEXT,
                res_body TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX events_session ON events(session_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Debug logs are disposable: a schema change starts clean instead of migrating.
        db.execSQL("DROP TABLE IF EXISTS events")
        db.execSQL("DROP TABLE IF EXISTS sessions")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    fun createSession(startedAt: Long, appVersion: String, device: String): Long {
        val values = ContentValues().apply {
            put("started_at", startedAt)
            put("app_version", appVersion)
            put("device", device)
        }
        val id = writableDatabase.insertOrThrow("sessions", null, values)
        changed()
        return id
    }

    fun insert(sessionId: Long, e: LogEvent): Long {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("type", e.type)
            put("time", e.time)
            put("level", e.level)
            put("is_error", if (e.isError) 1 else 0)
            put("size", e.storedSize())
            put("label", e.label)
            put("message", e.message)
            put("method", e.method)
            put("url", e.url)
            put("status", e.status)
            put("duration_ms", e.durationMs)
            put("res_size", e.responseSize)
            put("error", e.error)
            put("req_content_type", e.requestContentType)
            put("req_headers", encodeHeaders(e.requestHeaders))
            put("req_body", e.requestBody)
            put("req_size", e.requestSize)
            put("res_content_type", e.responseContentType)
            put("res_headers", encodeHeaders(e.responseHeaders))
            put("res_body", e.responseBody)
        }
        val id = writableDatabase.insertOrThrow("events", null, values)
        changed()
        return id
    }

    /** Newest first, without headers and bodies. */
    fun events(sessionId: Long, filter: EventFilter, search: String, limit: Int): List<LogEvent> {
        val where = StringBuilder("session_id = ?")
        val args = ArrayList<String>()
        args.add(sessionId.toString())
        when (filter) {
            EventFilter.ALL -> Unit
            EventFilter.NETWORK -> where.append(" AND type = $TYPE_NETWORK")
            EventFilter.LOGS -> where.append(" AND type = $TYPE_MESSAGE")
            EventFilter.ERRORS -> where.append(" AND is_error = 1")
        }
        val term = search.trim()
        if (term.isNotEmpty()) {
            val like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where.append(
                " AND (url LIKE ? ESCAPE '\\' OR message LIKE ? ESCAPE '\\' OR label LIKE ? ESCAPE '\\'" +
                    " OR method LIKE ? ESCAPE '\\' OR CAST(status AS TEXT) LIKE ? ESCAPE '\\')",
            )
            repeat(5) { args.add(like) }
        }
        val sql = "SELECT $LIGHT_COLUMNS FROM events WHERE $where ORDER BY id DESC LIMIT ${limit.coerceIn(1, 5000)}"
        val result = ArrayList<LogEvent>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) result.add(c.readEvent(full = false))
        }
        return result
    }

    fun event(id: Long): LogEvent? =
        readableDatabase.rawQuery("SELECT $FULL_COLUMNS FROM events WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.readEvent(full = true) else null
        }

    /** Every event of a session, oldest first, in small pages so an export never loads a session into memory. */
    fun forEachEvent(sessionId: Long, block: (LogEvent) -> Unit) {
        var after = 0L
        while (true) {
            var read = 0
            readableDatabase.rawQuery(
                "SELECT $FULL_COLUMNS FROM events WHERE session_id = ? AND id > ? ORDER BY id ASC LIMIT 50",
                arrayOf(sessionId.toString(), after.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val e = c.readEvent(full = true)
                    after = e.id
                    read++
                    block(e)
                }
            }
            if (read == 0) return
        }
    }

    fun sessions(currentId: Long): List<SessionRow> {
        val result = ArrayList<SessionRow>()
        readableDatabase.rawQuery(
            """
            SELECT s.id, s.started_at, s.app_version, s.device,
                   COUNT(e.id), COALESCE(SUM(e.is_error), 0), COALESCE(SUM(e.size), 0),
                   COALESCE(MAX(e.time), s.started_at)
            FROM sessions s LEFT JOIN events e ON e.session_id = s.id
            GROUP BY s.id ORDER BY s.id DESC
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                result.add(
                    SessionRow(
                        id = id,
                        startedAt = c.getLong(1),
                        appVersion = c.getString(2),
                        device = c.getString(3),
                        eventCount = c.getInt(4),
                        errorCount = c.getInt(5),
                        sizeBytes = c.getLong(6),
                        lastEventAt = c.getLong(7),
                        isCurrent = id == currentId,
                    ),
                )
            }
        }
        return result
    }

    fun totalBytes(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(size), 0) FROM events", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /**
     * Deletes finished sessions whose last event is older than [cutoff], finished sessions
     * with no events, and then the oldest data until the store fits in [maxBytes].
     * The current session is never deleted as a whole.
     */
    fun prune(currentId: Long, cutoff: Long, maxBytes: Long): Int {
        val db = writableDatabase
        val current = arrayOf(currentId.toString())
        // Arguments are bound as text. The left side here is an expression without column
        // affinity, and SQLite orders every integer before every text value, so without
        // the cast each finished session would count as expired.
        var removed = db.delete(
            "sessions",
            "id != ? AND COALESCE((SELECT MAX(time) FROM events WHERE session_id = sessions.id), started_at)" +
                " < CAST(? AS INTEGER)",
            arrayOf(currentId.toString(), cutoff.toString()),
        )
        removed += db.delete(
            "sessions",
            "id != ? AND NOT EXISTS (SELECT 1 FROM events WHERE session_id = sessions.id)",
            current,
        )
        var guard = 0
        while (totalBytes() > maxBytes && guard++ < 10_000) {
            val oldest = db.rawQuery("SELECT MIN(id) FROM sessions WHERE id != ?", current).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
            removed += if (oldest != null) {
                db.delete("sessions", "id = ?", arrayOf(oldest.toString()))
            } else {
                val n = db.delete("events", "id IN (SELECT id FROM events ORDER BY id ASC LIMIT 200)", null)
                if (n == 0) break
                n
            }
        }
        if (removed > 0) {
            truncateWal(db)
            changed()
        }
        return removed
    }

    /** Removes everything except the (emptied) current session. */
    fun clearAll(currentId: Long) {
        val db = writableDatabase
        db.delete("events", null, null)
        db.delete("sessions", "id != ?", arrayOf(currentId.toString()))
        truncateWal(db)
        changed()
    }

    /** Deleted rows are zeroed in the main file; this also drops their copies from the write-ahead log. */
    private fun truncateWal(db: SQLiteDatabase) {
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } catch (_: Exception) {
        }
    }

    private fun changed() {
        version.value = version.value + 1
    }

    private fun Cursor.readEvent(full: Boolean): LogEvent = LogEvent(
        id = getLong(0),
        sessionId = getLong(1),
        type = getInt(2),
        time = getLong(3),
        level = getInt(4),
        isError = getInt(5) != 0,
        label = stringOrNull(6),
        message = stringOrNull(7),
        method = stringOrNull(8),
        url = stringOrNull(9),
        status = if (isNull(10)) null else getInt(10),
        durationMs = if (isNull(11)) null else getLong(11),
        responseSize = if (isNull(12)) null else getLong(12),
        error = stringOrNull(13),
        requestContentType = if (full) stringOrNull(14) else null,
        requestHeaders = if (full) decodeHeaders(stringOrNull(15)) else null,
        requestBody = if (full) stringOrNull(16) else null,
        requestSize = if (full && !isNull(17)) getLong(17) else null,
        responseContentType = if (full) stringOrNull(18) else null,
        responseHeaders = if (full) decodeHeaders(stringOrNull(19)) else null,
        responseBody = if (full) stringOrNull(20) else null,
    )

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        const val SCHEMA_VERSION = 1
        const val LIGHT_COLUMNS =
            "id, session_id, type, time, level, is_error, label, substr(message, 1, 300), method, url, " +
                "status, duration_ms, res_size, substr(error, 1, 200)"
        const val FULL_COLUMNS =
            "id, session_id, type, time, level, is_error, label, message, method, url, " +
                "status, duration_ms, res_size, error, " +
                "req_content_type, req_headers, req_body, req_size, res_content_type, res_headers, res_body"
    }
}
