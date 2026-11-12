package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameThinnerTest {
    /** How many of [count] frames, 20 ms apart (50 fps), are shown at [speed]. */
    private fun shown(speed: Float, count: Int = 300): Int {
        val thinner = FrameThinner()
        return (0 until count).count { thinner.keep(it * 20_000L, speed) }
    }

    @Test
    fun normalSpeedAndTheSyncNudgesShowEveryFrame() {
        assertEquals(300, shown(1f))
        assertEquals(300, shown(1.1f))
        assertEquals(300, shown(0.5f))
    }

    @Test
    fun spedUpShowsAsManyFramesASecondAsNormalSpeed() {
        // 300 frames is 6 s of stream; at speed s that's 6/s seconds, at 50 a second (give or
        // take the first couple, shown while it learns the frame rate).
        assertEquals(150, shown(2f), 3)
        assertEquals(200, shown(1.5f), 3)
        assertEquals(240, shown(1.25f), 3)
        assertEquals(171, shown(1.75f), 3)
    }

    @Test
    fun skipsEvenly() {
        val thinner = FrameThinner()
        val pattern = (0 until 12).map { thinner.keep(it * 20_000L, 1.5f) }
        // Two shown, one skipped, over and over: never two skipped in a row.
        assert(pattern.windowed(2).none { (a, b) -> !a && !b }) { pattern.toString() }
    }

    @Test
    fun startsOverAfterGoingBack() {
        val thinner = FrameThinner()
        (0 until 50).forEach { thinner.keep(10_000_000L + it * 20_000L, 2f) }
        // A seek back: the first frame there is shown straight away.
        assert(thinner.keep(1_000_000L, 2f))
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) =
        assert(kotlin.math.abs(expected - actual) <= delta) { "expected $expected ± $delta, was $actual" }
}
