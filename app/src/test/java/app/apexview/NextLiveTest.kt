package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextLiveTest {
    private fun item(id: Long, title: String) = CatalogItem(id, title, "São Paulo Grand Prix", null, 0, live = true, startMs = 0)

    private val preRace = item(1, "Pre-Race Show")
    private val race = item(2, "Race")
    private val postRace = item(3, "Post-Race Show")

    @Test
    fun theRaceGoingLiveDuringThePreRaceShowIsOffered() {
        assertEquals(race, pickNextLive(listOf(preRace, race), watching = 1, atStart = setOf(1), playing = setOf(1), declined = emptySet()))
    }

    @Test
    fun somethingAlreadyLiveWaitsUntilWhatsOnEnds() {
        assertNull(pickNextLive(listOf(preRace, race), watching = 1, atStart = setOf(1, 2), playing = setOf(1), declined = emptySet()))
        assertEquals(race, pickNextLive(listOf(race), watching = 1, atStart = setOf(1, 2), playing = setOf(1), declined = emptySet()))
    }

    @Test
    fun thePostRaceShowFollowsTheRace() {
        assertEquals(postRace, pickNextLive(listOf(race, postRace), watching = 2, atStart = setOf(2), playing = setOf(2), declined = emptySet()))
    }

    @Test
    fun turnedDownStaysTurnedDown() {
        assertNull(pickNextLive(listOf(race, postRace), watching = 2, atStart = setOf(2), playing = setOf(2), declined = setOf(3)))
    }
}
