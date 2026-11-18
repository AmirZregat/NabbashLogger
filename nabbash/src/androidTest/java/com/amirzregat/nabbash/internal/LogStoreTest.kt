package com.amirzregat.nabbash.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device: the store depends on the platform's SQLite. */
@RunWith(AndroidJUnit4::class)
class LogStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.noBackupFilesDir, "nabbash-test/store.db")
    private lateinit var store: LogStore

    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour
    private val now = System.currentTimeMillis()
    private val week = now - 7 * day
    private val roomy = 50L * 1024L * 1024L

    @Before
    fun open() {
        file.parentFile!!.deleteRecursively()
        file.parentFile!!.mkdirs()
        store = LogStore(context, file)
    }

    @After
    fun close() {
        store.close()
        file.parentFile!!.deleteRecursively()
    }

    private fun session(startedAt: Long) = store.createSession(startedAt, "1.0 (1)", "Test device")

    private fun message(session: Long, time: Long, text: String = "hello", error: Boolean = false) =
        store.insert(session, LogEvent(type = TYPE_MESSAGE, time = time, isError = error, label = "Test", message = text))

    private fun ids(current: Long) = store.sessions(current).map { it.id }.sorted()

    @Test
    fun aSessionFromTheLastLaunchSurvivesTheNextOne() {
        val previous = session(now - 2 * 60_000)
        message(previous, now - 60_000)
        val current = session(now)

        store.prune(current, week, roomy)

        assertEquals(listOf(previous, current), ids(current))
        assertEquals(1, store.events(previous, EventFilter.ALL, "", 100).size)
    }

    @Test
    fun onlySessionsPastTheRetentionPeriodAreDeleted() {
        val expired = session(now - 9 * day)
        message(expired, now - 8 * day)
        val startedLongAgoButStillUsed = session(now - 9 * day)
        message(startedLongAgoButStillUsed, now - 6 * day)
        val yesterday = session(now - day)
        message(yesterday, now - day + 1000)
        val current = session(now)

        store.prune(current, week, roomy)

        assertEquals(listOf(startedLongAgoButStillUsed, yesterday, current), ids(current))
        assertTrue(store.events(expired, EventFilter.ALL, "", 100).isEmpty())
    }

    @Test
    fun aShorterRetentionPeriodDeletesMore() {
        val threeHoursAgo = session(now - 3 * hour)
        message(threeHoursAgo, now - 3 * hour)
        val halfAnHourAgo = session(now - hour / 2)
        message(halfAnHourAgo, now - hour / 2)
        val current = session(now)

        store.prune(current, now - hour, roomy)

        assertEquals(listOf(halfAnHourAgo, current), ids(current))
    }

    @Test
    fun finishedSessionsWithoutEventsAreDropped() {
        val empty = session(now - 1000)
        val current = session(now)

        store.prune(current, week, roomy)

        assertEquals(listOf(current), ids(current))
        assertTrue(empty != current)
    }

    @Test
    fun theCurrentSessionIsKeptEvenWhenItIsOldAndEmpty() {
        val current = session(now - 30 * day)

        store.prune(current, week, roomy)

        assertEquals(listOf(current), ids(current))
    }

    @Test
    fun theStorageLimitRemovesTheOldestSessionsFirst() {
        val big = "x".repeat(4000)
        val oldest = session(now - 3000)
        repeat(5) { message(oldest, now - 3000, big) }
        val newer = session(now - 2000)
        repeat(5) { message(newer, now - 2000, big) }
        val current = session(now)
        repeat(5) { message(current, now, big) }

        // Room for a little more than two of the three sessions.
        store.prune(current, week, 45_000)

        assertEquals(listOf(newer, current), ids(current))
        assertTrue(store.totalBytes() <= 45_000)
    }

    @Test
    fun theStorageLimitTrimsTheCurrentSessionLast() {
        val big = "x".repeat(4000)
        val current = session(now)
        repeat(300) { message(current, now + it, big) }

        store.prune(current, week, 500_000)

        val left = store.events(current, EventFilter.ALL, "", 5000)
        assertTrue(left.isNotEmpty())
        assertTrue(store.totalBytes() <= 500_000)
        // The newest events are the ones that stay.
        assertEquals(now + 299, left.first().time)
    }

    @Test
    fun clearAllLeavesOnlyAnEmptyCurrentSession() {
        val previous = session(now - 1000)
        message(previous, now - 1000)
        val current = session(now)
        message(current, now)

        store.clearAll(current)

        val rows = store.sessions(current)
        assertEquals(listOf(current), rows.map { it.id })
        assertEquals(0, rows[0].eventCount)
        assertEquals(0L, store.totalBytes())
    }

    @Test
    fun sessionsReportTheirCountsAndLastEvent() {
        val current = session(now - 5000)
        message(current, now - 4000)
        message(current, now - 3000, error = true)
        message(current, now - 1000)

        val row = store.sessions(current).single()

        assertEquals(3, row.eventCount)
        assertEquals(1, row.errorCount)
        assertEquals(now - 1000, row.lastEventAt)
        assertTrue(row.isCurrent)
    }

    @Test
    fun aNetworkEventComesBackAsItWasStored() {
        val current = session(now)
        val id = store.insert(
            current,
            LogEvent(
                type = TYPE_NETWORK,
                time = now,
                method = "POST",
                url = "https://api.example.com/v1/login?api_key=$REDACTED",
                status = 201,
                durationMs = 412,
                requestContentType = "application/json",
                requestHeaders = listOf("Authorization" to REDACTED, "Accept" to "a", "Accept" to "b"),
                requestBody = """{"user":"amir"}""",
                requestSize = 15,
                responseContentType = "application/json",
                responseHeaders = listOf("Content-Type" to "application/json"),
                responseBody = """{"ok":true}""",
                responseSize = 11,
            ),
        )

        val full = store.event(id)
        assertNotNull(full)
        assertEquals("POST", full!!.method)
        assertEquals(201, full.status)
        assertEquals(listOf("Authorization" to REDACTED, "Accept" to "a", "Accept" to "b"), full.requestHeaders)
        assertEquals("""{"user":"amir"}""", full.requestBody)
        assertEquals("""{"ok":true}""", full.responseBody)

        // The list rows leave the heavy columns out.
        val light = store.events(current, EventFilter.ALL, "", 10).single()
        assertNull(light.requestBody)
        assertNull(light.responseBody)
        assertNull(store.event(id + 1000))
    }

    @Test
    fun filtersAndSearchNarrowTheList() {
        val current = session(now)
        store.insert(current, LogEvent(type = TYPE_NETWORK, time = now, method = "GET", url = "https://a.example.com/users", status = 200))
        store.insert(current, LogEvent(type = TYPE_NETWORK, time = now, method = "GET", url = "https://a.example.com/missing", status = 404, isError = true))
        message(current, now, "Loaded 100% of the user_list")
        message(current, now, "Loaded the userXlist")

        assertEquals(4, store.events(current, EventFilter.ALL, "", 100).size)
        assertEquals(2, store.events(current, EventFilter.NETWORK, "", 100).size)
        assertEquals(2, store.events(current, EventFilter.LOGS, "", 100).size)
        assertEquals(1, store.events(current, EventFilter.ERRORS, "", 100).size)
        assertEquals(1, store.events(current, EventFilter.ALL, "404", 100).size)
        assertEquals(1, store.events(current, EventFilter.ALL, "/users", 100).size)
        // % and _ are searched for as typed, not as wildcards.
        assertEquals(1, store.events(current, EventFilter.ALL, "100%", 100).size)
        assertEquals(1, store.events(current, EventFilter.ALL, "user_list", 100).size)
        assertEquals(1, store.events(current, EventFilter.ALL, "%", 100).size)
    }

    @Test
    fun textThatLooksLikeSqlIsOnlyEverData() {
        val current = session(now)
        val nasty = "'); DROP TABLE events; --"
        message(current, now, nasty)

        assertEquals(nasty, store.events(current, EventFilter.ALL, "", 10).single().message)
        assertEquals(1, store.events(current, EventFilter.ALL, "DROP TABLE", 10).size)
        assertTrue(store.events(current, EventFilter.ALL, "' OR '1'='1", 10).isEmpty())
        assertEquals(1, store.sessions(current).single().eventCount)
    }

    @Test
    fun exportsReadEveryEventOldestFirst() {
        val current = session(now)
        repeat(130) { message(current, now + it, "event $it") }

        val seen = ArrayList<String?>()
        store.forEachEvent(current) { seen.add(it.message) }

        assertEquals(130, seen.size)
        assertEquals("event 0", seen.first())
        assertEquals("event 129", seen.last())
    }
}
