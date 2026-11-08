package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Feeds the archive parser data shaped like F1's files (per FastF1's parser): jsonStream lines are
 * "HH:MM:SS.mmm" + payload, and Position.z payloads are quoted base64 raw-deflate JSON with
 * 7-digit fractional timestamps.
 */
class LiveTimingTest {
    private val archive = LiveTimingArchive("token", "test")
    private val t0 = Instant.parse("2026-10-24T20:00:00Z").toEpochMilli()

    @Test
    fun leavingThePitsWithoutPitOutStillCountsAsOut() {
        val stream = listOf(
            "00:00:01.000{\"Lines\":{\"4\":{\"InPit\":true,\"NumberOfLaps\":0}}}",
            "00:05:00.000{\"Lines\":{\"4\":{\"InPit\":false}}}",
        ).joinToString("\n")
        val states = archive.parseStates(stream, t0).states.getValue("4")
        assertEquals(LapState.PIT, states.at(t0 + 60_000))
        assertEquals(LapState.OUT_LAP, states.at(t0 + 400_000))
    }

    private fun stamp(offsetMs: Long): String {
        val s = offsetMs / 1000
        return "%02d:%02d:%02d.%03d".format(s / 3600, s / 60 % 60, s % 60, offsetMs % 1000)
    }

    private fun deflate(text: String): String {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(text.toByteArray())
        deflater.finish()
        val buffer = ByteArray(64 * 1024)
        val n = deflater.deflate(buffer)
        deflater.end()
        return Base64.getEncoder().encodeToString(buffer.copyOf(n))
    }

    /** Car [number] lapping a circle of radius 10000 (1 km) every [lapMs], sampled every 220 ms. */
    private fun circle(number: String, lapMs: Long, atMs: Long): String {
        val a = 2 * PI * (atMs % lapMs) / lapMs
        val x = 10_000 * cos(a)
        val y = 10_000 * sin(a)
        return "\"$number\":{\"Status\":\"OnTrack\",\"X\":${x.toInt()},\"Y\":${y.toInt()},\"Z\":0}"
    }

    private fun positionStream(): String {
        val lines = StringBuilder()
        var at = 0L
        while (at < 400_000) {
            // Two samples per line, like the real feed batches them.
            val samples = listOf(at, at + 220).joinToString(",") { ms ->
                val ts = Instant.ofEpochMilli(t0 + ms).toString().replace("Z", "0000Z").let {
                    if (it.contains('.')) it else it.replace("0000Z", ".0000000Z")
                }
                val garage = "\"99\":{\"Status\":\"OffTrack\",\"X\":5,\"Y\":5,\"Z\":0}"
                "{\"Timestamp\":\"$ts\",\"Entries\":{${circle("44", 80_000, ms)},${circle("1", 90_000, ms)},$garage}}"
            }
            lines.append(stamp(at)).append('"').append(deflate("{\"Position\":[$samples]}")).append("\"\r\n")
            at += 440
        }
        return lines.toString()
    }

    @Test
    fun positionsDecodeAndInterpolate() {
        val tracks = archive.parsePositions(positionStream())
        assertEquals(setOf("44", "1"), tracks.keys) // OffTrack car 99 is dropped

        val track = tracks.getValue("44").build()
        val p = assertNotNullAndGet(track.at(t0 + 20_000)) // a quarter lap in: (0, 10000)
        assertTrue("x was ${p.first}", kotlin.math.abs(p.first) < 300)
        assertTrue("y was ${p.second}", kotlin.math.abs(p.second - 10_000) < 300)
        assertNull(track.at(t0 - 1_000))
    }

    @Test
    fun outlineIsOneClosedLapOfTheFastestCar() {
        val tracks = archive.parsePositions(positionStream())
        val outline = archive.traceOutline(tracks.values.toList())
        val points = outline.size / 2
        // Car 44 laps in 80 s at 220 ms per sample: about 364 points.
        assertTrue("points: $points", points in 340..380)
        val gap = hypot(outline[0] - outline[outline.size - 2], outline[1] - outline[outline.size - 1])
        assertTrue("loop gap $gap", gap < 300)
    }

    @Test
    fun heartbeatGivesStreamStart() {
        val heartbeat = stamp(5_000) + "{\"Utc\":\"2026-10-24T20:00:05.0000000Z\",\"_kf\":true}\r\n"
        assertEquals(t0, archive.parseStreamStart(heartbeat))
    }

    @Test
    fun aStreamPastADayKeepsItsLines() {
        // F1's recorder can run across a weekend; past a day its offsets gain a "d." in front.
        val radio = listOf(
            "23:59:59.000{\"Captures\":[{\"Utc\":\"2026-11-01T19:55:24.566Z\",\"RacingNumber\":\"50\",\"Path\":\"TeamRadio/A.mp3\"}]}",
            "1.02:36:53.637{\"Captures\":{\"1\":{\"Utc\":\"2026-11-01T19:59:28.007Z\",\"RacingNumber\":\"1\",\"Path\":\"TeamRadio/B.mp3\"}}}",
        ).joinToString("\r\n")
        assertEquals(listOf("TeamRadio/A.mp3", "TeamRadio/B.mp3"), archive.parseRadio(radio).map { it.path })
        assertEquals(((24 + 2) * 3600 + 36 * 60 + 53) * 1000L + 637, streamOffsetMs("1.02:36:53.637{}"))
        assertEquals(5_000L, streamOffsetMs("﻿00:00:05.000{}"))
    }

    @Test
    fun oneOddBeatDoesntMoveTheStreamStart() {
        val heartbeat = listOf(
            stamp(5_000) + "{\"Utc\":\"2026-10-26T05:00:05.0000000Z\"}",
            stamp(10_000) + "{\"Utc\":\"2026-10-24T20:00:10.0000000Z\"}",
            stamp(15_000) + "{\"Utc\":\"2026-10-24T20:00:15.0000000Z\"}",
        ).joinToString("\r\n")
        assertEquals(t0, archive.parseStreamStart(heartbeat))
    }

    @Test
    fun theCarsOwnTimesGiveTheStreamStartToo() {
        // Each line's newer sample is 220 ms after its offset.
        val start = assertNotNullAndGet(archive.positionsStreamStart(positionStream()))
        assertEquals(t0 + 220, start)
    }

    @Test
    fun lapStatesFollowPitAndLapCount() {
        val timing = listOf(
            stamp(1_000) + "{\"Lines\":{\"44\":{\"InPit\":true,\"NumberOfLaps\":0}},\"_kf\":true}",
            stamp(60_000) + "{\"Lines\":{\"44\":{\"InPit\":false,\"PitOut\":true}}}",
            stamp(120_000) + "{\"Lines\":{\"44\":{\"NumberOfLaps\":1}}}",
            stamp(210_000) + "{\"Lines\":{\"44\":{\"NumberOfLaps\":2}}}",
            stamp(300_000) + "{\"Lines\":{\"44\":{\"InPit\":true}}}",
        ).joinToString("\r\n")
        val parsed = archive.parseStates(timing, t0)
        val log = parsed.states.getValue("44")
        assertEquals(LapState.PIT, log.at(t0 + 30_000))
        assertEquals(LapState.OUT_LAP, log.at(t0 + 90_000))
        assertEquals(LapState.PUSH, log.at(t0 + 150_000))
        assertEquals(LapState.COOL_DOWN, log.at(t0 + 250_000))
        assertEquals(LapState.PIT, log.at(t0 + 310_000))
        assertEquals(listOf("44" to t0 + 120_000, "44" to t0 + 210_000), parsed.crossings)
    }

    @Test
    fun startFinishIsWhereLapsTickOver() {
        val tracks = archive.parsePositions(positionStream())
        val outline = archive.traceOutline(tracks.values.toList())
        val built = tracks.mapValues { it.value.build() }
        // Both cars cross at angle 0, the point (10000, 0): car 44 every 80 s, car 1 every 90 s.
        val crossings = (1..4).map { "44" to t0 + it * 80_000L } + (1..4).map { "1" to t0 + it * 90_000L }
        val index = archive.findStartFinish(outline, crossings, built)!!
        val x = outline[index * 2]
        val y = outline[index * 2 + 1]
        assertTrue("line at ($x, $y)", hypot(x - 10_000, y) < 400)
    }

    @Test
    fun progressGoesRoundTheLapSmoothly() {
        val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val g = TrackGeometry(square)
        assertEquals(400f, g.length, 0.01f)
        assertEquals(0.125f, g.progress(50f, -5f), 0.01f)  // halfway along the first side
        assertEquals(0.375f, g.progress(105f, 50f), 0.01f)
        assertEquals(0.875f, g.progress(-3f, 50f), 0.01f)  // closing side, back to the start
    }

    @Test
    fun mapFitKeepsTrackOnScreen() {
        val outline = archive.traceOutline(archive.parsePositions(positionStream()).values.toList())
        val fit = MapFit.of(outline, 2000f, 1000f, 50f)!!
        for (i in outline.indices step 2) {
            val p = fit.map(outline[i], outline[i + 1])
            assertTrue("x ${p.x}", p.x in 49f..1951f)
            assertTrue("y ${p.y}", p.y in 49f..951f)
        }
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
