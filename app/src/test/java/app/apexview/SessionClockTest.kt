package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionClockTest {
    private val archive = LiveTimingArchive("token", "test")
    private val t0 = java.time.Instant.parse("2026-10-31T21:00:00Z").toEpochMilli()

    private fun utc(s: String) = java.time.Instant.parse("2026-10-31T${s}Z").toEpochMilli()

    // Waiting, Q1 running, a red flag stops it without saying what it read, it restarts, then Q2.
    private val stream = listOf(
        "00:00:01.000{\"Utc\":\"2026-10-31T21:00:01.000Z\",\"Remaining\":\"00:18:00\",\"Extrapolating\":false}",
        "00:05:00.000{\"Utc\":\"2026-10-31T21:05:00.000Z\",\"Remaining\":\"00:18:00\",\"Extrapolating\":true}",
        "00:10:00.000{\"Utc\":\"2026-10-31T21:10:00.000Z\",\"Extrapolating\":false}",
        "00:15:00.000{\"Utc\":\"2026-10-31T21:15:00\",\"Extrapolating\":true}",
        "00:30:00.000{\"Utc\":\"2026-10-31T21:30:00.000Z\",\"Remaining\":\"00:15:00\",\"Extrapolating\":true}",
    ).joinToString("\r\n")

    private val parts = listOf(
        "00:00:01.000{\"Lines\":{},\"SessionPart\":1,\"CutOffTime\":\"\"}",
        "00:05:00.000{\"Lines\":{\"1\":{\"Speed\":1}}}",
        "00:30:00.000{\"SessionPart\":2}",
    ).joinToString("\r\n")

    private val clock = SessionClock(archive.parseClock(stream, t0), archive.parseParts(parts, t0))

    @Test
    fun waitsThenCountsDown() {
        val waiting = clock.at(utc("21:02:00"))!!
        assertEquals(18 * 60_000L, waiting.remainingMs)
        assertFalse(waiting.running)
        val running = clock.at(utc("21:06:00"))!!
        assertEquals(17 * 60_000L, running.remainingMs)
        assertTrue(running.running)
    }

    @Test
    fun aStopKeepsWhatItHadCountedDownTo() {
        val stopped = clock.at(utc("21:12:00"))!!
        assertEquals(13 * 60_000L, stopped.remainingMs)
        assertFalse(stopped.running)
        assertEquals(12 * 60_000L, clock.at(utc("21:16:00"))!!.remainingMs)
    }

    @Test
    fun followsThePartsOfQualifying() {
        assertEquals(1, clock.at(utc("21:06:00"))!!.part)
        assertEquals(2, clock.at(utc("21:31:00"))!!.part)
        assertEquals(14 * 60_000L, clock.at(utc("21:31:00"))!!.remainingMs)
    }

    @Test
    fun neverGoesBelowZero() {
        assertEquals(0L, clock.at(utc("22:30:00"))!!.remainingMs)
    }

    @Test
    fun aPictureBehindTheFirstMarkRunsBackFromIt() {
        val joined = SessionClock(archive.parseClock("00:00:00.000{\"Utc\":\"2026-10-31T21:05:00Z\",\"Remaining\":\"00:10:00\",\"Extrapolating\":true}", t0))
        assertEquals(11 * 60_000L, joined.at(utc("21:04:00"))!!.remainingMs)
    }

    @Test
    fun noClockNoReading() {
        assertNull(SessionClock.NONE.at(utc("21:00:00")))
    }
}
