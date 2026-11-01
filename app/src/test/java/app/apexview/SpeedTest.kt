package app.apexview

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SpeedTest {
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    private val message = JSONObject(
        """{"Entries":[
            {"Utc":"2026-11-01T20:00:00.1234567Z","Cars":{"1":{"Channels":{"0":11000,"2":300,"3":8}},"44":{"Channels":{"0":9000,"2":120}}}},
            {"Utc":"2026-11-01T20:00:00.4000000Z","Cars":{"1":{"Channels":{"0":11100,"2":305}},"44":{"Channels":{"0":9000}}}}
        ]}"""
    )

    private fun speeds(): Map<String, SpeedLog> {
        val cars = mutableMapOf<String, SpeedBuilder>()
        LiveTimingArchive("", "").addCarData(message, cars)
        return cars.mapValues { it.value.build() }
    }

    @Test
    fun readsChannelTwoAtThePicturesTime() {
        val car = speeds().getValue("1")
        assertNull(car.kmhAt(ms("2026-11-01T20:00:00.000Z")))
        assertEquals(300, car.kmhAt(ms("2026-11-01T20:00:00.200Z")))
        assertEquals(305, car.kmhAt(ms("2026-11-01T20:00:01.000Z")))
    }

    @Test
    fun aCarMissingTheChannelKeepsItsLastReading() {
        assertEquals(120, speeds().getValue("44").kmhAt(ms("2026-11-01T20:00:00.500Z")))
    }

    @Test
    fun readingsThatStoppedShowNothing() {
        assertNull(speeds().getValue("1").kmhAt(ms("2026-11-01T20:00:06.000Z")))
    }

    @Test
    fun milesPerHourAndKilometres() {
        assertEquals(186, speedIn(300, Settings.MPH))
        assertEquals(300, speedIn(300, Settings.KMH))
    }
}
