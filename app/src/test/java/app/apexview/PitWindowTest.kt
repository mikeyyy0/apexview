package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(21_000L to 1, PitWindow.sessionLoss(timing))
    }
}
