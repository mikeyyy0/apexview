package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class MapRetryTest {
    @Test
    fun theMapTriesAgainSoonThenSettlesToOnceAMinute() {
        assertEquals(listOf(3_000L, 10_000L, 30_000L, 60_000L, 60_000L, 60_000L), (0..5).map { AppState.mapRetryDelayMs(it) })
    }
}
