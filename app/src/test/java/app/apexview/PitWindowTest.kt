package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PitWindowTest {
    private fun snap(inPit: Boolean) = DriverLaps.Snapshot(1, emptyList(), emptyList(), null, null, inPit, null, null)

    /** Laps of 90 s from t = 0; into the pit lane at 400 s, out at 430 s. */
    private val laps = DriverLaps(
        times = longArrayOf(0, 400_000, 430_000),
        states = listOf(snap(false), snap(true), snap(false)),
        crossings = longArrayOf(0, 90_000, 180_000, 270_000, 360_000),
    )

    @Test
    fun aStopIsItsTimeInTheLaneLessTheStretchItSkips() {
        // 30 s down a lane that skips a tenth of a 90 s lap: 21 s lost.
        assertEquals(21_000L, PitWindow.lossFromStop(0, 30_000, 0.2f, 0.3f, 90_000))
    }

    @Test
    fun aLaneThatCrossesTheLineStillMeasures() {
        assertEquals(21_000L, PitWindow.lossFromStop(0, 30_000, 0.95f, 0.05f, 90_000))
    }

    @Test
    fun aLongStayIsNotAStop() {
        assertNull(PitWindow.lossFromStop(0, 600_000, 0.2f, 0.3f, 90_000))
    }

    @Test
    fun theGhostSitsAsFarBackAsTheStopCosts() {
        assertEquals(0.4f, PitWindow.ghost(0.5f, 9_000, 90_000), 0.0001f)
        // Back past the line.
        assertEquals(0.95f, PitWindow.ghost(0.05f, 9_000, 90_000), 0.0001f)
    }

    @Test
    fun theCarsEitherSideOfTheGhostAcrossTheLine() {
        val near = PitWindow.neighbours(0.98f, listOf("1" to 0.02f, "44" to 0.9f, "16" to 0.5f), 100_000)
        assertEquals("1" to 4_000L, near.ahead)
        assertEquals("44" to 8_000L, near.behind)
    }

    @Test
    fun stopsComeFromThePitFlags() {
        assertEquals(listOf(400_000L to 430_000L), laps.pitStops)
    }

    @Test
    fun paceIsTheMedianOfRecentLaps() {
        assertEquals(90_000L, laps.typicalLapMs(400_000))
        assertNull(laps.typicalLapMs(50_000))
    }

    @Test
    fun theSessionsStopsAreMeasuredFromWhereTheCarWent() {
        // A square lap 4000 long: into the lane at 0.2 of the way round, out at 0.3.
        val outline = floatArrayOf(0f, 0f, 1000f, 0f, 1000f, 1000f, 0f, 1000f)
        // Positions come a few times a second; these are the ones either side of each moment.
        val track = DriverTrack(
            longArrayOf(399_000, 400_000, 401_000, 429_000, 430_000, 431_000),
            floatArrayOf(780f, 800f, 810f, 1000f, 1000f, 1000f),
            floatArrayOf(0f, 0f, 0f, 190f, 200f, 220f),
            6,
        )
        val timing = SessionTiming(
            type = "Race",
            drivers = emptyMap(),
            tracks = mapOf("44" to track),
            states = emptyMap(),
            outline = outline,
            hasStartFinish = true,
            corners = emptyList(),
            startUtcMs = 0,
            laps = mapOf("44" to laps),
        )
        val lane = PitWindow.pitLane(timing)!!
        assertEquals(21_000L, lane.lossMs)
        assertEquals(1, lane.stops)
        assertEquals(0.2f, lane.entry, 0.001f)
        assertEquals(0.3f, lane.exit, 0.001f)
        assertEquals(30_000L, lane.laneMs)
    }

    @Test
    fun theCircleIsLaidOutInTime() {
        // A 100 s lap: the first half of the track takes 25 s, the second 75 s.
        val samples = (0..100).map { s ->
            val ms = s * 1_000L
            val progress = if (s <= 25) s / 50f else 0.5f + (s - 25) / 150f
            ms to progress.mod(1f)
        }
        val profile = LapProfile.from(samples, 100_000)!!
        assertEquals(0f, profile.timeFraction(0f), 0.01f)
        assertEquals(0.25f, profile.timeFraction(0.5f), 0.01f)
        assertEquals(0.625f, profile.timeFraction(0.75f), 0.01f)
    }

    @Test
    fun aLineThatIsntTheOutlinesStartStillReadsFromTheLine() {
        // The same even lap, but the line is 0.3 of the way round the outline.
        val samples = (0..100).map { s -> s * 1_000L to (0.3f + s / 100f).mod(1f) }
        val profile = LapProfile.from(samples, 100_000)!!
        assertEquals(0f, profile.timeFraction(0.3f), 0.01f)
        assertEquals(0.2f, profile.timeFraction(0.5f), 0.01f)
        assertEquals(0.8f, profile.timeFraction(0.1f), 0.01f)
    }

    @Test
    fun crossingTheLineGoesTheShortWayRound() {
        // The line a hair past the outline's start: the profile wraps inside its first step.
        val samples = (0..100).map { s -> s * 1_000L to (0.002f + s / 100f).mod(1f) }
        val profile = LapProfile.from(samples, 100_000)!!
        listOf(0f, 0.001f, 0.002f, 0.003f, 0.004f).forEach { p ->
            val f = profile.timeFraction(p)
            assertTrue("$p read as $f", f > 0.99f || f < 0.01f)
        }
    }

    @Test
    fun aTimeOnTheLapFindsItsPlaceOnTrack() {
        // The slow-then-fast lap from above, and a line 0.3 round the outline.
        val samples = (0..100).map { s ->
            val progress = if (s <= 25) s / 50f else 0.5f + (s - 25) / 150f
            s * 1_000L to (0.3f + progress).mod(1f)
        }
        val profile = LapProfile.from(samples, 100_000)!!
        listOf(0.05f, 0.3f, 0.55f, 0.8f, 0.99f).forEach { p ->
            assertEquals(p, profile.progressAt(profile.timeFraction(p)), 0.01f)
        }
    }

    @Test
    fun gapsReadAsSeconds() {
        assertEquals(1.234f, PitWindow.gapSeconds("+1.234")!!, 0.0001f)
        assertNull(PitWindow.gapSeconds("1L"))
        assertNull(PitWindow.gapSeconds(null))
    }

    @Test
    fun halfALapIsntOneToLayOut() {
        assertNull(LapProfile.from((0..50).map { s -> s * 1_000L to s / 100f }, 100_000))
    }

    @Test
    fun theLapCountIsPutBackOnTheLapThePositionSays() {
        // Counted onto lap 5 a moment before the car reaches the line.
        assertEquals(4.98f, PitWindow.raceDistance(5, 0.98f, 0.01f), 0.0001f)
        // Over the line, not yet counted.
        assertEquals(5.02f, PitWindow.raceDistance(4, 0.02f, 0.99f), 0.0001f)
        assertEquals(4.5f, PitWindow.raceDistance(4, 0.5f, 0.5f), 0.0001f)
    }
}
