package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class TileFitTest {
    private val renditions = listOf(640 to 360, 1280 to 720, 1920 to 1080)

    @Test
    fun theSmallestThatFillsTheTile() {
        assertEquals(1280 * 720, pixelCeiling(renditions, 700, 400))
        assertEquals(640 * 360, pixelCeiling(renditions, 640, 360))
    }

    @Test
    fun aTileBiggerThanEverythingGetsTheLargest() {
        assertEquals(1920 * 1080, pixelCeiling(renditions, 2076, 1168))
    }

    @Test
    fun bothSidesMustFill() {
        assertEquals(1920 * 1080, pixelCeiling(renditions, 1000, 800))
    }

    @Test
    fun beforeAnySizeIsKnownNothingIsHeldBack() {
        assertEquals(1920 * 1080, pixelCeiling(renditions, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
