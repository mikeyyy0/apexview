package app.apexview

import org.json.JSONArray
import org.json.JSONObject

/** How a sector or lap compares: the broadcast's yellow, green and purple. */
enum class Pace { NONE, YELLOW, GREEN, PURPLE }

class SectorCell(val time: String, val pace: Pace)

/** What a driver's timing card shows at one moment. */
class CardState(
    val position: Int?,
    /** S1..S3 of the lap in progress; null where not yet set. */
    val sectors: List<SectorCell?>,
    /** S1..S3 of the last full lap, shown with its lap time just after the line. */
    val lapSectors: List<SectorCell?>,
    /** Time into the current lap, ms; null in the pits or before the first crossing. */
    val runningMs: Long?,
    val lastLap: SectorCell?,
    /** Gap to the fastest in qualifying, else the interval to the car ahead. */
    val gap: String?,
    val inPit: Boolean,
    /** Gap to the leader (races) or to the fastest lap (qualifying), for the timing tower. */
    val toLeader: String? = null,
    /** Gap to the car ahead, for the timing tower. */
    val ahead: String? = null,
)

/**
 * One driver's timing through the session, as recorded snapshots: each change to the sectors,
 * last lap, gap, position or pit status keeps a full copy of the state, so [at] is a lookup
 * rather than a replay.
 */
class DriverLaps(
    private val times: LongArray,
    private val states: List<Snapshot>,
    private val crossings: LongArray,
    /** When the driver's completed-lap count changed, and to what (F1's NumberOfLaps). */
    private val lapTimes: LongArray = LongArray(0),
    private val lapCounts: IntArray = IntArray(0),
) {
    class Snapshot(
        val position: Int?,
        val sectors: List<SectorCell?>,
        val lapSectors: List<SectorCell?>,
        val lastLap: SectorCell?,
        val gap: String?,
        val inPit: Boolean,
        val toLeader: String?,
        val ahead: String?,
    )

    /**
     * The lap the driver was on at [utcMs]: one more than F1's count of laps completed then.
     * Null before the first count received (a live feed joined mid-session knows nothing
     * earlier). Counting crossings instead started every live session at lap 1.
     */
    fun lapAt(utcMs: Long): Int? {
        val i = lapTimes.indexOfLast { it <= utcMs }
        return if (i < 0) null else lapCounts[i] + 1
    }

    /** Each trip down the pit lane that came back out, as (in, out) UTC ms, from F1's InPit/PitOut. */
    val pitStops: List<Pair<Long, Long>> by lazy {
        val stops = mutableListOf<Pair<Long, Long>>()
        var inAt: Long? = null
        states.forEachIndexed { i, s ->
            if (s.inPit && inAt == null) inAt = times[i]
            if (!s.inPit) inAt?.let { stops += it to times[i]; inAt = null }
        }
        stops
    }

    /**
     * The driver's pace just before [utcMs]: the median of their last few laps, leaving out any
     * too slow or too fast to be laps run on track (a red flag, a lap counted twice).
     */
    fun typicalLapMs(utcMs: Long): Long? {
        val laps = lapWindows(utcMs).map { (start, end) -> end - start }.takeLast(RECENT_LAPS).sorted()
        return if (laps.isEmpty()) null else laps[laps.size / 2]
    }

    /** Every lap finished by [utcMs] that could have been run on track, as (start, end) UTC ms, oldest first. */
    fun lapWindows(utcMs: Long): List<Pair<Long, Long>> =
        (1 until crossings.size)
            .filter { crossings[it] <= utcMs }
            .map { crossings[it - 1] to crossings[it] }
            .filter { (start, end) -> end - start in MIN_LAP_MS..MAX_LAP_MS }

    /** When the driver's current spell in the pit lane began, if they're in it at [utcMs]. */
    fun inPitSince(utcMs: Long): Long? {
        var i = times.indexOfLast { it <= utcMs }
        if (i < 0 || !states[i].inPit) return null
        while (i > 0 && states[i - 1].inPit) i--
        return times[i]
    }

    /** When the driver last crossed the line, by [utcMs]. */
    fun lastCrossing(utcMs: Long): Long? = crossings.lastOrNull { it <= utcMs }

    fun at(utcMs: Long): CardState? {
        var i = -1
        var lo = 0
        var hi = times.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= utcMs) { i = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (i < 0) return null
        val s = states[i]
        val lapStart = crossings.lastOrNull { it <= utcMs }
        return CardState(
            position = s.position,
            sectors = s.sectors,
            lapSectors = s.lapSectors,
            runningMs = if (s.inPit || lapStart == null) null else utcMs - lapStart,
            lastLap = s.lastLap,
            gap = s.gap,
            inPit = s.inPit,
            toLeader = s.toLeader,
            ahead = s.ahead,
        )
    }

    private companion object {
        const val MIN_LAP_MS = 40_000L
        const val MAX_LAP_MS = 240_000L
        const val RECENT_LAPS = 5
    }
}

/**
 * Builds every driver's [DriverLaps] from F1's TimingData stream. Lines are "HH:MM:SS.mmm" +
 * JSON; the first is a full snapshot, the rest are partial updates under Lines.<number>. Sectors
 * come as a list in the snapshot and as an index-keyed object in updates; a sector's Value goes
 * empty when a new lap starts.
 */
object DriverTimingParser {
    private const val TS = 12
    private const val LATE_SECTOR_MS = 3_000L

    private class Live {
        var position: Int? = null
        val sector = arrayOfNulls<SectorCell>(3)
        val sectorFlags = Array(3) { BooleanArray(2) }   // personal, overall
        val sectorValue = Array(3) { "" }
        val lapSectors = arrayOfNulls<SectorCell>(3)
        var crossedAt: Long? = null
        var lastLap: SectorCell? = null
        var lastLapValue = ""
        val lastLapFlags = BooleanArray(2)
        var gap: String? = null
        var toLeader: String? = null
        var ahead: String? = null
        var inPit = false
        var laps: Int? = null
        val times = ArrayList<Long>()
        val snaps = ArrayList<DriverLaps.Snapshot>()
        val crossings = ArrayList<Long>()
        val lapTimes = ArrayList<Long>()
        val lapCounts = ArrayList<Int>()

        fun snap(t: Long) {
            val copy = DriverLaps.Snapshot(position, sector.toList(), lapSectors.toList(), lastLap, gap, inPit, toLeader, ahead)
            if (times.isNotEmpty() && times.last() == t) {
                snaps[snaps.lastIndex] = copy
            } else {
                times += t
                snaps += copy
            }
        }
    }

    fun parse(stream: String, streamStart: Long): Map<String, DriverLaps> {
        val drivers = mutableMapOf<String, Live>()
        stream.lineSequence().forEach { line ->
            if (line.length <= TS) return@forEach
            val lines = runCatching { JSONObject(line.substring(TS)).optJSONObject("Lines") }.getOrNull() ?: return@forEach
            val t = streamStart + offsetMs(line)
            lines.keys().forEach { number ->
                val d = lines.optJSONObject(number) ?: return@forEach
                val live = drivers.getOrPut(number) { Live() }
                apply(live, d, t)
                live.snap(t)
            }
        }
        return drivers.mapValues { (_, l) -> DriverLaps(l.times.toLongArray(), l.snaps, l.crossings.toLongArray(), l.lapTimes.toLongArray(), l.lapCounts.toIntArray()) }
    }

    private fun apply(l: Live, d: JSONObject, t: Long) {
        d.optString("Position").toIntOrNull()?.let { l.position = it }
        if (d.has("InPit")) l.inPit = d.optBoolean("InPit")
        if (d.optBoolean("PitOut")) l.inPit = false
        // Qualifying gives the gap to the fastest; races the interval to the car ahead.
        d.optString("TimeDiffToFastest").takeIf { d.has("TimeDiffToFastest") }?.let { l.gap = it.ifEmpty { null } }
        d.optJSONObject("IntervalToPositionAhead")?.let { o ->
            if (o.has("Value")) l.gap = o.optString("Value").ifEmpty { null }
        }
        // For the tower: both gaps, under whichever names this session uses.
        if (d.has("GapToLeader")) l.toLeader = d.optString("GapToLeader").ifEmpty { null }
        if (d.has("TimeDiffToFastest")) l.toLeader = d.optString("TimeDiffToFastest").ifEmpty { null }
        d.optJSONObject("IntervalToPositionAhead")?.let { o -> if (o.has("Value")) l.ahead = o.optString("Value").ifEmpty { null } }
        if (d.has("TimeDiffToPositionAhead")) l.ahead = d.optString("TimeDiffToPositionAhead").ifEmpty { null }

        when (val sectors = d.opt("Sectors")) {
            is JSONArray -> for (i in 0 until minOf(3, sectors.length())) sectors.optJSONObject(i)?.let { applySector(l, i, it, t) }
            is JSONObject -> sectors.keys().forEach { k ->
                val i = k.toIntOrNull() ?: return@forEach
                if (i in 0..2) sectors.optJSONObject(k)?.let { applySector(l, i, it, t) }
            }
        }
        d.optJSONObject("LastLapTime")?.let { o ->
            if (o.has("Value")) l.lastLapValue = o.optString("Value")
            if (o.has("PersonalFastest")) l.lastLapFlags[0] = o.optBoolean("PersonalFastest")
            if (o.has("OverallFastest")) l.lastLapFlags[1] = o.optBoolean("OverallFastest")
            l.lastLap = l.lastLapValue.takeIf { it.isNotEmpty() }?.let { SectorCell(it, pace(l.lastLapFlags)) }
        }
        // After the sectors, so a final sector sent with the crossing still counts for the lap
        // it finished. F1 leaves the old sectors up well into the next lap; the card doesn't.
        if (d.has("NumberOfLaps")) {
            val n = d.optInt("NumberOfLaps")
            if (l.laps != null && n > l.laps!!) {
                l.crossings += t
                l.crossedAt = t
                l.sector.copyInto(l.lapSectors)
                l.sector.fill(null)
                l.sectorValue.fill("")
                l.sectorFlags.forEach { it.fill(false) }
            }
            l.laps = n
            if (l.lapCounts.lastOrNull() != n) {
                l.lapTimes += t
                l.lapCounts += n
            }
        }
    }

    private fun applySector(l: Live, i: Int, o: JSONObject, t: Long) {
        // The last sector can land just after the crossing: it belongs to the lap just done.
        val crossed = l.crossedAt
        if (i == 2 && crossed != null && t - crossed in 1..LATE_SECTOR_MS && l.sector[0] == null) {
            val value = o.optString("Value")
            if (value.isNotEmpty()) {
                val flags = booleanArrayOf(o.optBoolean("PersonalFastest"), o.optBoolean("OverallFastest"))
                l.lapSectors[2] = SectorCell(value, pace(flags))
            }
            return
        }
        if (o.has("Value")) {
            val value = o.optString("Value")
            // A sector emptied means a new lap: its flags no longer apply.
            if (value.isEmpty()) l.sectorFlags[i].fill(false)
            l.sectorValue[i] = value
        }
        if (o.has("PersonalFastest")) l.sectorFlags[i][0] = o.optBoolean("PersonalFastest")
        if (o.has("OverallFastest")) l.sectorFlags[i][1] = o.optBoolean("OverallFastest")
        l.sector[i] = l.sectorValue[i].takeIf { it.isNotEmpty() }?.let { SectorCell(it, pace(l.sectorFlags[i])) }
    }

    private fun pace(flags: BooleanArray) = when {
        flags[1] -> Pace.PURPLE
        flags[0] -> Pace.GREEN
        else -> Pace.YELLOW
    }

    private fun offsetMs(line: String): Long {
        val h = line.substring(0, 2).toLong()
        val m = line.substring(3, 5).toLong()
        val s = line.substring(6, 8).toLong()
        val ms = line.substring(9, 12).toLong()
        return ((h * 60 + m) * 60 + s) * 1000 + ms
    }
}

/** A tyre: its compound (SOFT, MEDIUM, HARD, INTERMEDIATE, WET) and laps done on it. */
class Tyre(val compound: String, val laps: Int)

/** One driver's tyres through the session. */
class TyreLog(private val times: LongArray, private val tyres: List<Tyre>) {
    fun at(utcMs: Long): Tyre? {
        val i = times.indexOfLast { it <= utcMs }
        return if (i < 0) null else tyres[i]
    }
}

/**
 * Reads F1's TimingAppData stream: per driver, a list of stints (as a list in the first line,
 * then index-keyed updates). The current tyre is the last stint's compound and its lap count.
 */
object TyreParser {
    private const val TS = 12

    fun parse(stream: String, streamStart: Long): Map<String, TyreLog> {
        val stints = mutableMapOf<String, MutableMap<Int, Pair<String, Int>>>()
        val times = mutableMapOf<String, MutableList<Long>>()
        val tyres = mutableMapOf<String, MutableList<Tyre>>()
        stream.lineSequence().forEach { line ->
            if (line.length <= TS) return@forEach
            val lines = runCatching { org.json.JSONObject(line.substring(TS)).optJSONObject("Lines") }.getOrNull() ?: return@forEach
            val t = streamStart + offsetMs(line)
            lines.keys().forEach { number ->
                val d = lines.optJSONObject(number) ?: return@forEach
                val list = stints.getOrPut(number) { mutableMapOf() }
                val entries: List<Pair<Int, org.json.JSONObject>> = when (val s = d.opt("Stints")) {
                    is org.json.JSONArray -> (0 until s.length()).mapNotNull { i -> s.optJSONObject(i)?.let { i to it } }
                    is org.json.JSONObject -> s.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { i -> s.optJSONObject(k)?.let { i to it } } }.toList()
                    else -> return@forEach
                }
                entries.forEach { (i, o) ->
                    val (compound, laps) = list[i] ?: ("" to 0)
                    list[i] = o.optString("Compound").ifEmpty { compound } to (if (o.has("TotalLaps")) o.optInt("TotalLaps") else laps)
                }
                val current = list.maxByOrNull { it.key }?.value ?: return@forEach
                if (current.first.isEmpty()) return@forEach
                times.getOrPut(number) { mutableListOf() } += t
                tyres.getOrPut(number) { mutableListOf() } += Tyre(current.first, current.second)
            }
        }
        return times.mapValues { (n, ts) -> TyreLog(ts.toLongArray(), tyres.getValue(n)) }
    }

    private fun offsetMs(line: String): Long {
        val h = line.substring(0, 2).toLong()
        val m = line.substring(3, 5).toLong()
        val s = line.substring(6, 8).toLong()
        val ms = line.substring(9, 12).toLong()
        return ((h * 60 + m) * 60 + s) * 1000 + ms
    }
}
