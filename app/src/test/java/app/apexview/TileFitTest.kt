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

    @Test
    fun aFeedAloneHasTheWholeConnection() {
        assertEquals(listOf(1f), bandwidthShares(listOf(1920f * 1080f)))
    }

    @Test
    fun equalTilesShareEqually() {
        assertEquals(listOf(0.25f, 0.25f, 0.25f, 0.25f), bandwidthShares(List(4) { 960f * 540f }))
    }

    @Test
    fun theBigTileGetsTheMostAndSmallOnesKeepAFloor() {
        val shares = bandwidthShares(listOf(1600f * 900f, 100f * 56f, 100f * 56f, 100f * 56f))
        assertEquals(0.988f, shares[0], 0.001f)
        assertEquals(0.1f, shares[1], 0.001f)
    }

    @Test
    fun tilesWithNoSizeYetSplitEvenly() {
        assertEquals(listOf(0.5f, 0.5f), bandwidthShares(listOf(0f, 0f)))
    }
}
