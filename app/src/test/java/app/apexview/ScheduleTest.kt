package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    private val hour = 60 * 60 * 1000L
    private val lastStart = 100 * hour

    private fun event(start: Long, live: Boolean = false) = CatalogItem(start, "Event", null, null, 0, live, start)
    private fun weekend(vararg events: CatalogItem) = WeekendContent(emptyList(), emptyList(), schedule = events.toList())
    private val race = weekend(event(lastStart - 2 * hour), event(lastStart))

    @Test
    fun stillShowingDuringTheLastEvent() = assertFalse(race.scheduleOver(lastStart + hour))

    @Test
    fun goneAfterTheLastEventHasHadTimeToFinish() = assertTrue(race.scheduleOver(lastStart + 4 * hour))

    @Test
    fun stillShowingWhileAnEventRunsLate() =
        assertFalse(weekend(event(lastStart, live = true)).scheduleOver(lastStart + 4 * hour))
}

class ScheduleDayTest {
    private val hour = 60 * 60 * 1000L
    private fun event(id: Long, start: Long, live: Boolean = false) = CatalogItem(id, "Event", null, null, 0, live, start)

    // Thursday: a show. Friday: two practices. Saturday: a practice, then qualifying and its show.
    private val days = listOf(
        listOf(event(1, 10 * hour)),
        listOf(event(2, 34 * hour), event(3, 38 * hour)),
        listOf(event(4, 58 * hour), event(5, 62 * hour), event(6, 63 * hour)),
    )
    private val sessions = setOf(2L, 3L, 4L, 5L)
    private fun open(today: Int, now: Long, vararg watched: Long) =
        scheduleDayToOpen(days, today, now, sessions) { it in watched }

    @Test
    fun todayOnceSomethingOfTodaysHasStarted() = org.junit.Assert.assertEquals(1, open(1, 35 * hour))

    @Test
    fun yesterdayWhileTodayHasNotStarted() = org.junit.Assert.assertEquals(1, open(2, 50 * hour))

    @Test
    fun todayIfYesterdaysSessionsAreWatched() = org.junit.Assert.assertEquals(2, open(2, 50 * hour, 2, 3))

    @Test
    fun yesterdayIfOnlySomeOfItIsWatched() = org.junit.Assert.assertEquals(1, open(2, 50 * hour, 2))

    @Test
    fun aDayOfOnlyShowsCountsTheShow() {
        org.junit.Assert.assertEquals(0, open(1, 30 * hour))
        org.junit.Assert.assertEquals(1, open(1, 30 * hour, 1))
    }

    @Test
    fun theFirstDayHasNothingToFallBackTo() = org.junit.Assert.assertEquals(0, open(0, 5 * hour))

    @Test
    fun outsideTheWeekendItsStartOrEnd() {
        org.junit.Assert.assertEquals(0, open(-1, 1 * hour))
        org.junit.Assert.assertEquals(2, open(-1, 200 * hour))
    }
}
