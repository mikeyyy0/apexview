package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {
    @Test
    fun eachSpeedReadsTheWayPeopleWriteIt() {
        assertEquals(listOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×"), AppState.SPEEDS.map(::speedLabel))
    }
}
