package app.apexview

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Which archived session a replay belongs to, from F1 TV's video metadata. */
data class SessionRef(
    val year: Int,
    val meetingKey: Int?,
    val sessionKey: Int?,
    val circuitKey: Int?,
    val meetingName: String,
    val title: String,
)

enum class LapState { UNKNOWN, PIT, OUT_LAP, PUSH, COOL_DOWN, KNOCKED_OUT }

class DriverInfo(
    val number: String,
    val tla: String,
    val color: Int,
    val firstName: String = "",
    val lastName: String = "",
    val team: String = "",
)

/** A team radio recording: when it was published, whose it is, and its file under the session. */
class RadioClip(val utc: Long, val number: String, val path: String)

/** A numbered corner, in archive coordinates. */
class Corner(val number: Int, val x: Float, val y: Float)

/** A circuit from MultiViewer: its outline, numbered corners, and where each marshal sector starts. */
class Circuit(val outline: FloatArray, val corners: List<Corner>, val sectors: List<Corner>)

/** A yellow (level 1), double yellow (2) or clear (0) in one marshal sector. Sector 0 is "track clear". */
class SectorFlag(val utc: Long, val sector: Int, val level: Int)

/** What the whole track is under, with the words the map shows for it. */
enum class TrackCondition(val label: String?) {
    CLEAR(null), SAFETY_CAR("Safety car"), SAFETY_CAR_ENDING("Safety car in this lap"),
    VSC("Virtual safety car"), VSC_ENDING("VSC ending"), RED("Red flag");

    /** About to go green: the yellow pulses until it does. */
    val ending get() = this == SAFETY_CAR_ENDING || this == VSC_ENDING
    val safetyCar get() = this == SAFETY_CAR || this == SAFETY_CAR_ENDING
}

/** A time race control has announced ("Q3 WILL RESUME AT 17:04"): what, and when (UTC ms). */
class Resumption(val announcedUtc: Long, val atUtc: Long, val what: String)

/**
 * The flags at one moment: the whole track's condition, the yellow sectors (number to level),
 * and the next announced restart, if one is coming.
 */
class FlagPicture(val track: TrackCondition, val yellow: Map<Int, Int>, val resumption: Resumption? = null) {
    /** The line at the foot of the map: "Red flag · Q3 resumes in 4:12", "Safety car", or null. */
    fun caption(t: Long): String? = listOfNotNull(
        track.label,
        resumption?.let { "${it.what} in ${countdown(it.atUtc - t)}" },
    ).joinToString(" · ").ifEmpty { null }

    private fun countdown(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * Race control's flags through a session: sector yellows from its messages, the whole track's
 * condition from F1's track status (4 safety car, 5 red, 6 VSC, 7 VSC ending), when the safety
 * car was called in, and announced restart times. All oldest first.
 */
class TrackFlags(
    val sectors: List<SectorFlag>,
    val status: List<Pair<Long, Int>>,
    val safetyCarIn: List<Long> = emptyList(),
    val resumptions: List<Resumption> = emptyList(),
    /** Red flags from race control, start to green (UTC ms): track status doesn't always mark them. */
    val redFlags: List<Pair<Long, Long>> = emptyList(),
) {
    fun at(t: Long): FlagPicture {
        var code = 1
        var since = 0L
        for ((utc, c) in status) {
            if (utc > t) break
            if (c != code) since = utc
            code = c
        }
        val track = if (redFlags.any { (from, to) -> t in from until to }) TrackCondition.RED else when (code) {
            // "Safety car in this lap" during this safety car period: it's coming in.
            4 -> if (safetyCarIn.any { it in since..t }) TrackCondition.SAFETY_CAR_ENDING else TrackCondition.SAFETY_CAR
            5 -> TrackCondition.RED
            6 -> TrackCondition.VSC
            7 -> TrackCondition.VSC_ENDING
            else -> TrackCondition.CLEAR
        }
        val yellow = HashMap<Int, Int>()
        for (f in sectors) {
            if (f.utc > t) break
            when {
                f.sector == 0 -> yellow.clear()
                f.level == 0 -> yellow.remove(f.sector)
                else -> yellow[f.sector] = f.level
            }
        }
        // The latest announcement still ahead; announced within the hour, so a stale one never lingers.
        val resumption = resumptions.lastOrNull { it.announcedUtc <= t && t < it.atUtc && t - it.announcedUtc < 3_600_000 }
        return FlagPicture(track, yellow, resumption)
    }

    companion object {
        val NONE = TrackFlags(emptyList(), emptyList())
    }
}

/**
 * The circuit as a closed loop, with distance along it. [progress] turns a car's position into
 * how far round the lap it is (0..1), measured from the loop's first point.
 */
class TrackGeometry(val outline: FloatArray) {
    private val n = outline.size / 2
    private val along = FloatArray(n)
    val length: Float

    /**
     * True when the lap runs counter-clockwise as the track map draws it. The map keeps the
     * archive's y-up orientation (rotation only), so the sign of the outline's area decides.
     */
    val counterClockwise: Boolean

    init {
        for (i in 1 until n) along[i] = along[i - 1] + dist(i - 1, i)
        length = if (n > 1) along[n - 1] + dist(n - 1, 0) else 0f
        var area = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += outline[i * 2].toDouble() * outline[j * 2 + 1] - outline[j * 2].toDouble() * outline[i * 2 + 1]
        }
        counterClockwise = area > 0
    }

    private fun dist(a: Int, b: Int) =
        hypot(outline[a * 2] - outline[b * 2], outline[a * 2 + 1] - outline[b * 2 + 1])

    fun nearestIndex(x: Float, y: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val dx = outline[i * 2] - x
            val dy = outline[i * 2 + 1] - y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * The point [f] of the way round the lap (0..1 from the loop's first point) and the unit
     * direction of travel there, as x, y, dx, dy.
     */
    fun pointAt(f: Float): FloatArray {
        if (n < 2 || length == 0f) return floatArrayOf(0f, 0f, 1f, 0f)
        val target = f.mod(1f) * length
        for (i in 0 until n) {
            val j = (i + 1) % n
            val end = if (j == 0) length else along[j]
            if (target <= end || j == 0) {
                val span = end - along[i]
                val k = if (span == 0f) 0f else ((target - along[i]) / span).coerceIn(0f, 1f)
                val ax = outline[i * 2]; val ay = outline[i * 2 + 1]
                val bx = outline[j * 2]; val by = outline[j * 2 + 1]
                val len = hypot(bx - ax, by - ay).coerceAtLeast(0.001f)
                return floatArrayOf(ax + (bx - ax) * k, ay + (by - ay) * k, (bx - ax) / len, (by - ay) / len)
            }
        }
        return floatArrayOf(outline[0], outline[1], 1f, 0f)
    }

    /** Projected onto the nearest segment, so the value moves smoothly between outline points. */
    fun progress(x: Float, y: Float): Float {
        if (n < 2 || length == 0f) return 0f
        val i = nearestIndex(x, y)
        var best = along[i]
        var bestD = Float.MAX_VALUE
        for (j in listOf((i - 1 + n) % n, i)) {
            val k = (j + 1) % n
            val ax = outline[j * 2]; val ay = outline[j * 2 + 1]
            val bx = outline[k * 2]; val by = outline[k * 2 + 1]
            val vx = bx - ax; val vy = by - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 == 0f) 0f else (((x - ax) * vx + (y - ay) * vy) / len2).coerceIn(0f, 1f)
            val px = ax + vx * t; val py = ay + vy * t
            val d = (px - x) * (px - x) + (py - y) * (py - y)
            if (d < bestD) {
                bestD = d
                val start = along[j]
                val segment = hypot(vx, vy)
                best = if (k == 0) start + segment * t else start + (along[k] - start) * t
            }
        }
        return (best / length).mod(1f)
    }
}

/** One car's positions over the session, in archive coordinates (1/10 m). */
class DriverTrack(private val t: LongArray, private val x: FloatArray, private val y: FloatArray, private val n: Int) {
    val startMs get() = if (n > 0) t[0] else 0L
    val endMs get() = if (n > 0) t[n - 1] else 0L

    /** Position at [utcMs], interpolated between samples. Null outside the data or across gaps. */
    fun at(utcMs: Long): Pair<Float, Float>? {
        if (n == 0 || utcMs < t[0] || utcMs > t[n - 1]) return null
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= utcMs) lo = mid else hi = mid
        }
        val span = t[hi] - t[lo]
        if (span > MAX_GAP_MS) return null
        val f = if (span == 0L) 0f else (utcMs - t[lo]).toFloat() / span
        return Pair(x[lo] + (x[hi] - x[lo]) * f, y[lo] + (y[hi] - y[lo]) * f)
    }

    /** As [at], but a car not heard from yet waits where it was first seen. For showing on the map. */
    fun atOrFirst(utcMs: Long): Pair<Float, Float>? =
        if (n > 0 && utcMs < t[0]) Pair(x[0], y[0]) else at(utcMs)

    companion object {
        private const val MAX_GAP_MS = 5_000L
    }
}

/** A driver's lap states as they changed through the session. */
class StateLog(private val t: LongArray, private val s: Array<LapState>) {
    fun at(utcMs: Long): LapState {
        var result = LapState.UNKNOWN
        for (i in t.indices) {
            if (t[i] > utcMs) break
            result = s[i]
        }
        return result
    }
}

/** A car's speed through a session, as F1's car data gives it (km/h, a few times a second). */
class SpeedLog(private val t: LongArray, private val v: ShortArray, private val n: Int) {
    /** The latest reading at [utcMs]. Null before the first, and once they've stopped coming. */
    fun kmhAt(utcMs: Long): Int? {
        if (n == 0 || utcMs < t[0]) return null
        var lo = 0
        var hi = n
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= utcMs) lo = mid else hi = mid
        }
        return if (utcMs - t[lo] > STALE_MS) null else v[lo].toInt()
    }

    private companion object {
        const val STALE_MS = 5_000L
    }
}

class SpeedBuilder {
    private var t = LongArray(4096)
    private var v = ShortArray(4096)
    private var size = 0

    fun add(time: Long, kmh: Int) {
        if (size > 0 && time <= t[size - 1]) return
        if (size == t.size) {
            t = t.copyOf(size * 2)
            v = v.copyOf(size * 2)
        }
        t[size] = time
        v[size] = kmh.toShort()
        size++
    }

    fun build() = SpeedLog(t, v, size)
}

/** A speed in the unit chosen in Settings. */
internal fun speedIn(kmh: Int, unit: String) = if (unit == Settings.KMH) kmh else (kmh * 0.621371).roundToInt()

class SessionTiming(
    val type: String,
    val drivers: Map<String, DriverInfo>,
    val tracks: Map<String, DriverTrack>,
    val states: Map<String, StateLog>,
    /** The circuit as x0,y0,x1,y1,... traced from one clean lap, starting at the line when known. */
    val outline: FloatArray,
    /** True when [outline] starts at the start/finish line. */
    val hasStartFinish: Boolean,
    val corners: List<Corner>,
    val startUtcMs: Long,
    val circuitKey: Int? = null,
    /**
     * When the session got going (UTC ms): lights out for a race, the first green otherwise.
     * Null when the archive doesn't say.
     */
    val startedUtc: Long? = null,
    /** The (last) chequered flag, UTC ms. */
    val finishedUtc: Long? = null,
    /** Each driver's sectors and lap times through the session, for the onboard timing card. */
    val laps: Map<String, DriverLaps> = emptyMap(),
    /** Team radio clips, oldest first. */
    val radio: List<RadioClip> = emptyList(),
    /** Each driver's tyres, for the timing tower. */
    val tyres: Map<String, TyreLog> = emptyMap(),
    /** Where the session's files live; a clip's address is this plus its path. Public, no login. */
    val radioBase: String = "",
    /** Where each marshal sector starts, for yellow flags in one sector. */
    val marshalSectors: List<Corner> = emptyList(),
    val flags: TrackFlags = TrackFlags.NONE,
    /** "Race", "Sprint", "Qualifying"...: [type] says "Race" for a sprint too. */
    val name: String = type,
    /** Live only: the leader's lap and the race distance, from F1's lap counter. */
    val currentLap: Int? = null,
    val totalLaps: Int? = null,
    /** The session's countdown, for sessions that run on time. */
    val clock: SessionClock = SessionClock.NONE,
    /** Live: the feed brings the cars' speeds. A replay's come from the archive when first wanted. */
    val live: Boolean = false,
) {
    val geometry = TrackGeometry(outline)

    /** Each car's speed, by racing number. */
    var speeds by mutableStateOf<Map<String, SpeedLog>>(emptyMap())

    /**
     * Where the race starts, as the fraction of a lap after the finish line (the outline's first
     * point). Found from a race's grid; for other sessions it comes from a race seen earlier at
     * the same circuit, or stays null.
     */
    var startLine: Float? = null

    /**
     * Live: the earliest moment there are car positions for, which is when Apexview joined live
     * timing, unless it kept a recording from earlier or F1 has since published the archive.
     */
    var positionsFromUtc: Long? = null

    /** Live, and the picture is from before there are positions: nothing to show the cars from. */
    fun beforePositions(now: Long): Boolean = live && positionsFromUtc?.let { now < it - POSITIONS_SLACK_MS } == true

    val isRace get() = type.contains("Race", ignoreCase = true)

    /** The Grand Prix itself: Driver of the Day is voted on this one, not the sprint. */
    val isGrandPrix get() = isRace && !name.contains("Sprint", ignoreCase = true)

    /** Flying-lap highlights only mean something in qualifying. */
    val showsPushLaps get() = type.contains("Qualifying", ignoreCase = true)
}

/** The countdown at one moment: what's left, whether it's running, and the part of qualifying. */
class ClockReading(val remainingMs: Long, val running: Boolean, val part: Int?)

/**
 * F1's session clock. It only says what the clock read at a moment and whether it was running from
 * there; red flags stop it, and each part of qualifying sets it again. Read at the picture's time,
 * it matches what's on screen rather than the timing that runs ahead of it.
 */
class SessionClock(private val marks: List<Mark>, private val parts: List<Pair<Long, Int>> = emptyList()) {
    class Mark(val utc: Long, val remainingMs: Long, val running: Boolean)

    fun at(utc: Long): ClockReading? {
        if (marks.isEmpty()) return null
        // Before the first mark (a live picture behind it), it's run back from that mark.
        val mark = marks.lastOrNull { it.utc <= utc } ?: marks.first()
        val left = if (mark.running) mark.remainingMs - (utc - mark.utc) else mark.remainingMs
        return ClockReading(left.coerceAtLeast(0), mark.running, (parts.lastOrNull { it.first <= utc } ?: parts.firstOrNull())?.second)
    }

    companion object {
        val NONE = SessionClock(emptyList())
    }
}

/** Lap states per driver, plus every moment a car crossed the line (to locate it). */
class StateParse(val states: Map<String, StateLog>, val crossings: List<Pair<String, Long>>)

/**
 * Reads a past session from F1's live timing archive (livetiming.formula1.com/static). The archive
 * now needs the F1 TV subscription token as a Bearer header, the way FastF1 sends it.
 * File formats follow FastF1's parser: jsonStream lines are "HH:MM:SS.mmm" + JSON, and the ".z"
 * streams hold base64 raw-deflate JSON.
 */
/**
 * A jsonStream line's prefix: its offset from the stream's start, "HH:MM:SS.mmm", with "d." in
 * front once F1's recorder has been running more than a day (it can run across a weekend; the
 * 2026 Mexico race's radio was all "1.02:32:50.914"). Read as a fixed 12 characters, every line
 * from that point on was dropped, and the radio stopped there.
 */
private val STREAM_PREFIX = Regex("""^\uFEFF?(?:(\d+)\.)?(\d{2}):(\d{2}):(\d{2})\.(\d{3})""")

/** How many characters the prefix takes, so the payload starts there; the whole line if it has none. */
/** A picture this close before the first positions still shows the cars where they first appear. */
internal const val POSITIONS_SLACK_MS = 5_000L

/** A jsonStream line: the offset from the stream's start as "HH:MM:SS.mmm", then the JSON. */
internal fun streamLine(offsetMs: Long, json: String): String =
    "%02d:%02d:%02d.%03d".format(offsetMs / 3_600_000, offsetMs / 60_000 % 60, offsetMs / 1000 % 60, offsetMs % 1000) + json + '\n'

/**
 * An archive stream's lines from before [untilUtc], re-counted from [newStart] instead of the
 * archive's own start [archiveStart], to go in front of a live session's.
 */
internal fun rebaseStream(stream: String, archiveStart: Long, newStart: Long, untilUtc: Long): String {
    val out = StringBuilder()
    for (line in stream.lineSequence()) {
        val prefix = streamPrefixLength(line)
        if (line.length <= prefix) continue
        val utc = archiveStart + streamOffsetMs(line)
        if (utc >= untilUtc) break
        if (utc < newStart) continue
        out.append(streamLine(utc - newStart, line.substring(prefix)))
    }
    return out.toString()
}

internal fun streamPrefixLength(line: String): Int = STREAM_PREFIX.find(line)?.range?.last?.plus(1) ?: line.length

/** The line's offset from the stream's start, in ms. */
internal fun streamOffsetMs(line: String): Long {
    val g = STREAM_PREFIX.find(line)?.groupValues ?: throw IllegalArgumentException("no stream offset")
    val days = g[1].ifEmpty { "0" }.toLong()
    return (((days * 24 + g[2].toLong()) * 60 + g[3].toLong()) * 60 + g[4].toLong()) * 1000 + g[5].toLong()
}

class LiveTimingArchive(private val token: String, private val userAgent: String) {

    /** [watchedUtc]: when the video on screen was broadcast, if known; finds the session by time. */
    suspend fun load(ref: SessionRef, watchedUtc: Long? = null): SessionTiming = withContext(Dispatchers.IO) {
        val (path, type) = findSession(ref, watchedUtc)
        val drivers = parseDrivers(JSONObject(fetch(path + "DriverList.json")))
        val positions = fetch(path + "Position.z.jsonStream")
        val tracks = parsePositions(positions)
        if (tracks.isEmpty()) throw IOException("The archive has no car positions for this session")

        // The heartbeat ties the streams' offsets to the clock. The 2026 Mexico race's put them
        // 33 hours late, leaving the laps a day and a half away from the cars; where the cars'
        // own timestamps disagree with it by more than a minute, they're the ones to trust.
        val streamStart = streamStart(fetch(path + "Heartbeat.jsonStream"), positions)
        val timingStream = runCatching { fetch(path + "TimingData.jsonStream") }.getOrNull()
        val parsed = timingStream?.let { runCatching { parseStates(it, streamStart) }.getOrNull() }
            ?: StateParse(emptyMap(), emptyList())
        val laps = timingStream?.let { runCatching { DriverTimingParser.parse(it, streamStart) }.getOrNull() }.orEmpty()

        val built = tracks.mapValues { it.value.build() }
        val traced = traceOutline(tracks.values.toList())
        val line = findStartFinish(traced, parsed.crossings, built)
        val outline = if (line != null) startAt(traced, line) else traced
        // Turn numbers and marshal sectors are a nice extra from a third party; the map works without them.
        val circuit = ref.circuitKey?.let { runCatching { fetchCircuit(it, ref.year) }.getOrNull() }
        val corners = circuit?.corners.orEmpty()

        val started = runCatching { parseSessionStarted(fetch(path + "SessionStatus.jsonStream"), streamStart) }.getOrNull()
        val control = runCatching { fetch(path + "RaceControlMessages.jsonStream") }.getOrNull()
        val chequered = control?.let { runCatching { parseChequered(it, streamStart) }.getOrNull() }
        // Restart times come in the track's local time; the session's offset from GMT turns them to UTC.
        val gmtOffset = runCatching { JSONObject(fetch(path + "SessionInfo.json")).optString("GmtOffset") }.getOrNull()
        val flags = TrackFlags(
            control?.let { runCatching { parseSectorFlags(it, streamStart) }.getOrNull() }.orEmpty(),
            runCatching { parseTrackStatus(fetch(path + "TrackStatus.jsonStream"), streamStart) }.getOrDefault(emptyList()),
            control?.let { runCatching { parseSafetyCarIn(it, streamStart) }.getOrNull() }.orEmpty(),
            control?.let { c -> gmtOffset?.let { runCatching { parseResumptions(c, streamStart, it) }.getOrNull() } }.orEmpty(),
            control?.let { runCatching { parseRedFlags(it, streamStart) }.getOrNull() }.orEmpty(),
        )
        val tyres = runCatching { TyreParser.parse(fetch(path + "TimingAppData.jsonStream"), streamStart) }.getOrDefault(emptyMap())
        val radio = runCatching { parseRadio(fetch(path + "TeamRadio.jsonStream")) }
            .onSuccess { android.util.Log.i("ApexviewRadio", "archive radio for $path: ${it.size} clips") }
            .onFailure { android.util.Log.w("ApexviewRadio", "archive radio for $path: ${it.message}") }
            .getOrDefault(emptyList())
        val isRace = type.contains("Race", ignoreCase = true)
        val totalLaps = if (isRace) runCatching { parseTotalLaps(fetch(path + "LapCount.jsonStream")) }.getOrNull() else null
        val grid = if (isRace && line != null) findGrid(TrackGeometry(outline), built, chequered) else null

        SessionTiming(
            type = type,
            drivers = drivers,
            tracks = built,
            states = parsed.states,
            outline = outline,
            hasStartFinish = line != null,
            corners = corners,
            startUtcMs = streamStart,
            circuitKey = ref.circuitKey,
            startedUtc = grid?.lightsOutUtc ?: started,
            finishedUtc = chequered,
            laps = laps,
            radio = radio,
            radioBase = BASE + path,
            tyres = tyres,
            marshalSectors = circuit?.sectors.orEmpty(),
            flags = flags,
            totalLaps = totalLaps,
            clock = runCatching {
                SessionClock(parseClock(fetch(path + "ExtrapolatedClock.jsonStream"), streamStart), parseParts(timingStream.orEmpty(), streamStart))
            }.getOrDefault(SessionClock.NONE),
        ).also { it.startLine = grid?.startLine }
    }

    /** LapCount.jsonStream: the race distance, as last given (it can change when a race is shortened). */
    internal fun parseTotalLaps(stream: String): Int? =
        stream.lineSequence().mapNotNull { line ->
            if (line.length <= streamPrefixLength(line)) null
            else runCatching { JSONObject(line.substring(streamPrefixLength(line))) }.getOrNull()?.takeIf { it.has("TotalLaps") }?.optInt("TotalLaps")
        }.lastOrNull()?.takeIf { it > 0 }

    /** TeamRadio.jsonStream: each line adds Captures, as a list or as numbered entries. */
    internal fun parseRadio(stream: String): List<RadioClip> {
        val clips = mutableListOf<RadioClip>()
        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            runCatching { JSONObject(line.substring(streamPrefixLength(line))) }.getOrNull()?.let { addRadio(it, clips) }
        }
        return clips.sortedBy { it.utc }
    }

    internal fun addRadio(json: JSONObject, into: MutableList<RadioClip>) {
        val captures = json.opt("Captures")
        val list = when (captures) {
            is org.json.JSONArray -> (0 until captures.length()).mapNotNull { captures.optJSONObject(it) }
            is JSONObject -> captures.keys().asSequence().mapNotNull { captures.optJSONObject(it) }.toList()
            else -> emptyList()
        }
        list.forEach { c ->
            val utc = c.optString("Utc").let { if (it.isNotEmpty() && !it.endsWith("Z")) it + "Z" else it }
            val time = runCatching { Instant.parse(utc).toEpochMilli() }.getOrNull() ?: return@forEach
            val path = c.optString("Path").ifEmpty { return@forEach }
            if (into.none { it.path == path }) into += RadioClip(time, c.optString("RacingNumber"), path)
        }
    }

    /** The first time the session status went to Started (for a race, the formation lap). */
    internal fun parseSessionStarted(stream: String, streamStart: Long): Long? {
        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            val status = runCatching { JSONObject(line.substring(streamPrefixLength(line))).optString("Status") }.getOrNull()
            if (status == "Started") return streamStart + offsetMs(line)
        }
        return null
    }

    /** Race control's messages, each with its time (UTC ms), oldest first. */
    private fun controlMessages(stream: String, streamStart: Long): List<Pair<JSONObject, Long>> {
        val out = mutableListOf<Pair<JSONObject, Long>>()
        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            val messages = runCatching { JSONObject(line.substring(streamPrefixLength(line))).opt("Messages") }.getOrNull() ?: return@forEach
            val list = when (messages) {
                is org.json.JSONArray -> (0 until messages.length()).map { messages.getJSONObject(it) }
                is JSONObject -> messages.keys().asSequence().mapNotNull { messages.optJSONObject(it) }.toList()
                else -> emptyList()
            }
            list.forEach { m ->
                // Utc comes with or without a zone; without one it's UTC anyway.
                val utc = m.optString("Utc").let { if (it.isNotEmpty() && !it.endsWith("Z")) it + "Z" else it }
                out += m to (runCatching { Instant.parse(utc).toEpochMilli() }.getOrNull() ?: (streamStart + offsetMs(line)))
            }
        }
        return out
    }

    /** The last chequered flag in race control's messages. */
    internal fun parseChequered(stream: String, streamStart: Long): Long? =
        controlMessages(stream, streamStart).lastOrNull { it.first.optString("Flag") == "CHEQUERED" }?.second

    /**
     * Yellows and clears in single marshal sectors ("DOUBLE YELLOW IN TRACK SECTOR 10"), and
     * "TRACK CLEAR", which clears them all.
     */
    internal fun parseSectorFlags(stream: String, streamStart: Long): List<SectorFlag> =
        controlMessages(stream, streamStart).mapNotNull { (m, utc) ->
            val scope = m.optString("Scope")
            when (m.optString("Flag")) {
                "YELLOW" -> m.optInt("Sector", 0).takeIf { scope == "Sector" && it > 0 }?.let { SectorFlag(utc, it, 1) }
                "DOUBLE YELLOW" -> m.optInt("Sector", 0).takeIf { scope == "Sector" && it > 0 }?.let { SectorFlag(utc, it, 2) }
                "CLEAR", "GREEN" -> when (scope) {
                    "Sector" -> m.optInt("Sector", 0).takeIf { it > 0 }?.let { SectorFlag(utc, it, 0) }
                    "Track" -> SectorFlag(utc, 0, 0)
                    else -> null
                }
                else -> null
            }
        }.sortedBy { it.utc }

    /**
     * Each red flag from race control, until the next green light, chequered flag or session
     * (re)start; open-ended while it's still out.
     */
    internal fun parseRedFlags(stream: String, streamStart: Long): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var since: Long? = null
        controlMessages(stream, streamStart).forEach { (m, utc) ->
            val flag = m.optString("Flag")
            // Not "TRACK CLEAR": race control sends that while the session is still stopped (Spa 2026, Q3).
            val green = flag == "GREEN" || flag == "CHEQUERED" ||
                (m.optString("Category") == "SessionStatus" && m.optString("Message").contains("STARTED"))
            when {
                flag == "RED" -> if (since == null) since = utc
                green -> since?.let { out += it to utc; since = null }
            }
        }
        since?.let { out += it to Long.MAX_VALUE }
        return out
    }

    /** When race control said "SAFETY CAR IN THIS LAP". */
    internal fun parseSafetyCarIn(stream: String, streamStart: Long): List<Long> =
        controlMessages(stream, streamStart)
            .filter { (m, _) -> m.optString("Category") == "SafetyCar" && m.optString("Message").contains("IN THIS LAP") }
            .map { it.second }

    /**
     * Announced restarts: "Q3 WILL RESUME AT 17:04", "RACE WILL RESUME AT 15:42", "SQ2 WILL START
     * AT 17:08", "ESTIMATED TIME OF SQ1 RESUMPTION - 16:59". Times are the track's local time;
     * [gmtOffset] ("02:00:00") is the session's offset from GMT.
     */
    internal fun parseResumptions(stream: String, streamStart: Long, gmtOffset: String): List<Resumption> {
        val zone = parseOffset(gmtOffset)
        return controlMessages(stream, streamStart).mapNotNull { (m, utc) ->
            val text = m.optString("Message").uppercase()
            WILL_AT.find(text)?.groupValues?.let { g -> return@mapNotNull resumption(utc, zone, g[1], g[2], g[3], g[4]) }
            ESTIMATED.find(text)?.groupValues?.let { g ->
                return@mapNotNull resumption(utc, zone, g[1], if (g[2] == "RESUMPTION") "RESUME" else "START", g[3], g[4])
            }
            null
        }
    }

    /** One announcement as a [Resumption]: the next time it's [h]:[min] at the track after it was made. */
    private fun resumption(utc: Long, zone: java.time.ZoneOffset, who: String, verb: String, h: String, min: String): Resumption? {
        val said = java.time.Instant.ofEpochMilli(utc).atOffset(zone)
        var at = said.withHour(h.toIntOrNull() ?: return null).withMinute(min.toIntOrNull() ?: return null).withSecond(0).withNano(0)
        if (at.isBefore(said.minusHours(1))) at = at.plusDays(1)
        val name = who.removePrefix("THE ").trim().let { w ->
            if (w.matches(Regex("""S?Q\d"""))) w else w.lowercase().replaceFirstChar { it.uppercase() }
        }
        val action = when (verb) {
            "RESUME" -> "resumes"
            "RESTART" -> "restarts"
            else -> "starts"
        }
        return Resumption(utc, at.toInstant().toEpochMilli(), "$name $action")
    }

    /** TrackStatus.jsonStream: the whole track's status code as it changes. */
    internal fun parseTrackStatus(stream: String, streamStart: Long): List<Pair<Long, Int>> =
        stream.lineSequence().mapNotNull { line ->
            if (line.length <= streamPrefixLength(line)) return@mapNotNull null
            val code = runCatching { JSONObject(line.substring(streamPrefixLength(line))).optString("Status").toInt() }.getOrNull()
                ?: return@mapNotNull null
            streamStart + offsetMs(line) to code
        }.toList()

    /**
     * ExtrapolatedClock.jsonStream: lines of Utc, Remaining ("00:17:59") and Extrapolating, any of
     * which a line can leave out. Without Remaining, the clock reads what it had counted down to.
     */
    internal fun parseClock(stream: String, streamStart: Long): List<SessionClock.Mark> {
        val marks = mutableListOf<SessionClock.Mark>()
        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            val json = runCatching { JSONObject(line.substring(streamPrefixLength(line))) }.getOrNull() ?: return@forEach
            val utc = json.optString("Utc").takeIf { it.isNotEmpty() }
                ?.let { text -> runCatching { java.time.Instant.parse(if (text.endsWith("Z")) text else text + "Z").toEpochMilli() }.getOrNull() }
                ?: (streamStart + offsetMs(line))
            val last = marks.lastOrNull()
            val remaining = json.optString("Remaining").takeIf { it.isNotEmpty() }?.let(::clockMs)
                ?: last?.let { if (it.running) (it.remainingMs - (utc - it.utc)).coerceAtLeast(0) else it.remainingMs }
                ?: return@forEach
            val running = if (json.has("Extrapolating")) json.optBoolean("Extrapolating") else last?.running ?: false
            marks += SessionClock.Mark(utc, remaining, running)
        }
        return marks.sortedBy { it.utc }
    }

    /** "00:17:59" in ms. */
    private fun clockMs(text: String): Long? {
        val parts = text.split(":").map { it.toDoubleOrNull() ?: return null }
        if (parts.size != 3) return null
        return ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000).toLong()
    }

    /** Which part of qualifying is running, as TimingData says when it changes. */
    internal fun parseParts(stream: String, streamStart: Long): List<Pair<Long, Int>> =
        stream.lineSequence().mapNotNull { line ->
            if (line.length <= streamPrefixLength(line)) return@mapNotNull null
            val part = SESSION_PART.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            streamStart + offsetMs(line) to part
        }.toList()

    internal class Grid(val startLine: Float, val lightsOutUtc: Long)

    /**
     * Finds a race's grid from the cars themselves. Before the start the field sits still near the
     * line twice: on the grid before the formation lap, then again after it, just before lights
     * out. The second stop gives both the start (its front car is on pole, the line a few metres
     * ahead) and the moment of lights out (when it ends). Stops after the chequered flag
     * ([before]) don't count: that's parc fermé.
     */
    internal fun findGrid(geometry: TrackGeometry, tracks: Map<String, DriverTrack>, before: Long?): Grid? {
        if (geometry.length == 0f || tracks.size < GRID_CARS) return null
        val from = tracks.values.minOf { it.startMs } + STILL_MS
        val to = minOf(before ?: Long.MAX_VALUE, tracks.values.maxOf { it.endMs })
        // Each stop: first moment, last moment, and its front car's lap fraction at the last moment.
        val stops = mutableListOf<Triple<Long, Long, Float>>()
        var t = from
        while (t <= to) {
            var count = 0
            var front = -Float.MAX_VALUE
            for (track in tracks.values) {
                val now = track.at(t) ?: continue
                val earlier = track.at(t - STILL_MS) ?: continue
                if (hypot(now.first - earlier.first, now.second - earlier.second) > STILL_UNITS) continue
                var s = geometry.progress(now.first, now.second)
                if (s > 0.5f) s -= 1f
                if (s < -GRID_ZONE || s > GRID_ZONE / 2) continue
                count++
                if (s > front) front = s
            }
            if (count >= GRID_CARS) {
                val last = stops.lastOrNull()
                if (last == null || t - last.second > MOVING_MS) stops += Triple(t, t, front)
                else stops[stops.lastIndex] = Triple(last.first, t, front)
            }
            // Two stops and the field long gone: the race is under way.
            if (stops.size >= 2 && t - stops[1].second > MOVING_MS * 2) break
            t += STEP_MS
        }
        val stop = stops.getOrNull(1) ?: stops.firstOrNull() ?: return null
        return Grid(
            startLine = (stop.third + POLE_TO_LINE_UNITS / geometry.length).mod(1f),
            lightsOutUtc = stop.second + STEP_MS / 2,
        )
    }

    /**
     * Where cars were when their lap count ticked over, pinned to the outline. The busiest spot
     * wins, so pit-lane crossings and timing lag don't drag it off the line.
     */
    internal fun findStartFinish(
        outline: FloatArray,
        crossings: List<Pair<String, Long>>,
        tracks: Map<String, DriverTrack>,
    ): Int? {
        if (outline.size < 8) return null
        val geometry = TrackGeometry(outline)
        val n = outline.size / 2
        val counts = IntArray(n)
        var found = 0
        crossings.forEach { (number, time) ->
            val (x, y) = tracks[number]?.at(time) ?: return@forEach
            counts[geometry.nearestIndex(x, y)]++
            found++
        }
        if (found < 5) return null
        // Busiest stretch of 7 points, then its busiest point (else ties pick the stretch's start).
        var best = 0
        var bestScore = -1L
        for (i in 0 until n) {
            var c = 0
            for (k in -3..3) c += counts[(i + k + n) % n]
            val score = c * 100_000L + counts[i]
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }

    /** The same loop, starting at point [index]. */
    internal fun startAt(outline: FloatArray, index: Int): FloatArray {
        val n = outline.size / 2
        return FloatArray(outline.size) { i -> outline[((i / 2 + index) % n) * 2 + i % 2] }
    }

    /**
     * The circuit's outline (x0,y0,x1,y1,...), numbered corners and marshal sectors, from
     * MultiViewer's public circuit data, which uses the same coordinates as F1's position feed.
     * Sent without the F1 token: it's a third party. The outline doesn't start at the line; a
     * live session needs it before any car has done a lap to trace one from.
     */
    internal fun fetchCircuit(circuitKey: Int, year: Int): Circuit? {
        val conn = URL("https://api.multiviewer.app/api/v1/circuits/$circuitKey/$year").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", userAgent)
            if (conn.responseCode >= 400) return null
            val json = JSONObject(conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
            val xs = json.optJSONArray("x")
            val ys = json.optJSONArray("y")
            val outline = if (xs != null && ys != null && xs.length() == ys.length()) {
                FloatArray(xs.length() * 2) { i -> (if (i % 2 == 0) xs else ys).optDouble(i / 2).toFloat() }
            } else FloatArray(0)
            fun points(key: String): List<Corner> {
                val list = json.optJSONArray(key) ?: return emptyList()
                return (0 until list.length()).mapNotNull { i ->
                    val c = list.getJSONObject(i)
                    val p = c.optJSONObject("trackPosition") ?: return@mapNotNull null
                    Corner(c.optInt("number"), p.optDouble("x").toFloat(), p.optDouble("y").toFloat())
                }
            }
            return Circuit(outline, points("corners"), points("marshalSectors"))
        } finally {
            conn.disconnect()
        }
    }

    /** Finds the session's archive path by F1's session key, else by meeting and session name. */
    private fun findSession(ref: SessionRef, watchedUtc: Long?): Pair<String, String> {
        val meetings = JSONObject(fetch("${ref.year}/Index.json")).getJSONArray("Meetings")
        var byName: Pair<String, String>? = null
        var byNameLength = 0
        // The meeting's sessions with their start times, for when the name doesn't say which;
        // and its folder and time zone, to look for a session the index doesn't list yet.
        val meetingSessions = mutableListOf<Pair<Long, Pair<String, String>>>()
        var meetingFolder: String? = null
        var meetingOffset: String? = null
        for (i in 0 until meetings.length()) {
            val meeting = meetings.getJSONObject(i)
            val sameMeeting = meeting.optInt("Key") == ref.meetingKey ||
                meeting.optString("Name").equals(ref.meetingName, ignoreCase = true)
            val sessions = meeting.getJSONArray("Sessions")
            for (j in 0 until sessions.length()) {
                val session = sessions.getJSONObject(j)
                val path = session.optString("Path")
                if (path.isEmpty()) continue
                val found = path to session.optString("Type")
                if (ref.sessionKey != null && session.optInt("Key") == ref.sessionKey) return found
                if (sameMeeting) {
                    sessionStartUtc(session)?.let { meetingSessions += it to found }
                    meetingFolder = path.split('/').take(2).joinToString("/", postfix = "/")
                    meetingOffset = session.optString("GmtOffset").ifEmpty { null }
                }
                val name = session.optString("Name")
                // "Sprint Qualifying" also contains "Qualifying"; keep the longest matching name.
                if (sameMeeting && name.length > byNameLength && ref.title.contains(name, ignoreCase = true)) {
                    byName = found
                    byNameLength = name.length
                }
            }
        }
        byName?.let { return it }
        // F1 TV's live broadcasts (and their replays straight after) are titled with just the
        // event, with no session key: take the weekend's session that had started by the time
        // the video was broadcast, the latest such.
        if (watchedUtc != null) {
            fun recent(start: Long) = start <= watchedUtc + SESSION_SLACK_MS && watchedUtc - start < SESSION_SPAN_MS
            meetingSessions.filter { recent(it.first) }.maxByOrNull { it.first }?.let { return it.second }
            // Just finished: the index can lag well behind. Sessions live in predictable
            // folders, so look for that day's under the weekend's folder.
            val folder = meetingFolder
            if (folder != null) {
                val zone = parseOffset(meetingOffset ?: "00:00:00")
                val day = java.time.Instant.ofEpochMilli(watchedUtc).atOffset(zone).toLocalDate()
                SESSION_FOLDERS.mapNotNull { name ->
                    val path = "$folder${day}_$name/"
                    runCatching { JSONObject(fetch(path + "SessionInfo.json")) }.getOrNull()?.let { info ->
                        sessionStartUtc(info)?.let { it to (path to info.optString("Type")) }
                    }
                }.filter { recent(it.first) }.maxByOrNull { it.first }?.let { return it.second }
            }
        }
        throw IOException("Couldn't find \"${ref.title}\" in F1's ${ref.year} timing archive")
    }

    /** A session's start in UTC: the archive gives local time plus its offset from GMT. */
    private fun sessionStartUtc(session: JSONObject): Long? = runCatching {
        val local = java.time.LocalDateTime.parse(session.getString("StartDate"))
        local.toInstant(parseOffset(session.optString("GmtOffset", "00:00:00"))).toEpochMilli()
    }.getOrNull()

    /** "-05:00:00" to a zone offset. */
    internal fun parseOffset(text: String): java.time.ZoneOffset {
        val negative = text.startsWith("-")
        val (h, m) = text.trimStart('-', '+').split(":").let { it[0].toInt() to it.getOrElse(1) { "0" }.toInt() }
        return java.time.ZoneOffset.ofHoursMinutes(if (negative) -h else h, if (negative) -m else m)
    }

    internal fun parseDrivers(json: JSONObject): Map<String, DriverInfo> {
        val result = mutableMapOf<String, DriverInfo>()
        json.keys().forEach { number ->
            val d = json.optJSONObject(number) ?: return@forEach
            val color = runCatching { Color.parseColor("#" + d.optString("TeamColour")) }.getOrDefault(Color.WHITE)
            result[number] = DriverInfo(
                number, d.optString("Tla", number), color,
                firstName = d.optString("FirstName"),
                lastName = d.optString("LastName"),
                team = d.optString("TeamName"),
            )
        }
        return result
    }

    internal fun parsePositions(stream: String): Map<String, TrackBuilder> {
        val tracks = mutableMapOf<String, TrackBuilder>()
        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            val json = runCatching { JSONObject(inflate(line.substring(streamPrefixLength(line)))) }.getOrNull() ?: return@forEach
            addPositions(json, tracks)
        }
        return tracks
    }

    /**
     * A replay's car speeds. A large file, and only onboards show them, so it's read line by line
     * once one is on screen rather than holding up the map.
     */
    suspend fun loadSpeeds(base: String): Map<String, SpeedLog> = withContext(Dispatchers.IO) {
        val cars = mutableMapOf<String, SpeedBuilder>()
        open(base.removePrefix(BASE) + "CarData.z.jsonStream").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                ensureActive()
                val text = line.removePrefix("\uFEFF")
                if (text.length <= streamPrefixLength(text)) return@forEach
                runCatching { JSONObject(inflate(text.substring(streamPrefixLength(text)))) }.getOrNull()?.let { addCarData(it, cars) }
            }
        }
        cars.mapValues { it.value.build() }
    }

    /** One decoded CarData message (archive line or live update): channel 2 is the speed. */
    internal fun addCarData(json: JSONObject, cars: MutableMap<String, SpeedBuilder>) {
        val entries = json.optJSONArray("Entries") ?: return
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val time = runCatching { Instant.parse(entry.getString("Utc")).toEpochMilli() }.getOrNull() ?: continue
            val list = entry.optJSONObject("Cars") ?: continue
            list.keys().forEach { number ->
                val kmh = list.optJSONObject(number)?.optJSONObject("Channels")?.optInt("2", -1) ?: -1
                if (kmh >= 0) cars.getOrPut(number) { SpeedBuilder() }.add(time, kmh)
            }
        }
    }

    /** One decoded Position message (archive line or live update) onto the cars' tracks. */
    internal fun addPositions(json: JSONObject, tracks: MutableMap<String, TrackBuilder>) {
        val samples = json.optJSONArray("Position") ?: return
        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val time = runCatching { Instant.parse(sample.getString("Timestamp")).toEpochMilli() }.getOrNull() ?: continue
            val entries = sample.optJSONObject("Entries") ?: continue
            entries.keys().forEach { number ->
                val e = entries.getJSONObject(number)
                if (e.optString("Status") == "OffTrack") return@forEach
                tracks.getOrPut(number) { TrackBuilder() }
                    .add(time, e.optDouble("X").toFloat(), e.optDouble("Y").toFloat())
            }
        }
    }

    /**
     * jsonStream lines carry an offset from the stream start; the heartbeat ties it to UTC. The
     * median of its first beats, so one odd beat can't move every time in the session.
     */
    internal fun parseStreamStart(stream: String): Long {
        val starts = stream.lineSequence().mapNotNull { line ->
            if (line.length <= streamPrefixLength(line)) return@mapNotNull null
            val utc = runCatching { JSONObject(line.substring(streamPrefixLength(line))).getString("Utc") }.getOrNull() ?: return@mapNotNull null
            runCatching { Instant.parse(utc).toEpochMilli() - offsetMs(line) }.getOrNull()
        }.take(START_SAMPLES).toList().sorted()
        if (starts.isEmpty()) throw IOException("The archive's heartbeat has no clock")
        return starts[starts.size / 2]
    }

    /**
     * The stream start the car positions imply: each line's latest sample time less the line's
     * offset (the line goes out a moment after its samples, so this runs a touch early).
     */
    internal fun positionsStreamStart(stream: String): Long? {
        val starts = stream.lineSequence().mapNotNull { line ->
            if (line.length <= streamPrefixLength(line)) return@mapNotNull null
            val json = runCatching { JSONObject(inflate(line.substring(streamPrefixLength(line)))) }.getOrNull() ?: return@mapNotNull null
            val samples = json.optJSONArray("Position") ?: return@mapNotNull null
            val latest = (0 until samples.length()).mapNotNull { i ->
                runCatching { Instant.parse(samples.getJSONObject(i).getString("Timestamp")).toEpochMilli() }.getOrNull()
            }.maxOrNull() ?: return@mapNotNull null
            runCatching { latest - offsetMs(line) }.getOrNull()
        }.take(START_SAMPLES).toList().sorted()
        return if (starts.isEmpty()) null else starts[starts.size / 2]
    }

    internal fun parseStates(stream: String, streamStart: Long): StateParse {
        val crossings = mutableListOf<Pair<String, Long>>()
        val times = mutableMapOf<String, MutableList<Long>>()
        val values = mutableMapOf<String, MutableList<LapState>>()
        val current = mutableMapOf<String, LapState>()
        val laps = mutableMapOf<String, Int>()

        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            val lines = runCatching { JSONObject(line.substring(streamPrefixLength(line))).optJSONObject("Lines") }.getOrNull()
                ?: return@forEach
            val time = streamStart + offsetMs(line)
            lines.keys().forEach { number ->
                val d = lines.optJSONObject(number) ?: return@forEach
                var state = current[number] ?: LapState.UNKNOWN
                if (d.optBoolean("InPit")) state = LapState.PIT
                // Leaving the pits: F1 sends PitOut, but before a race the last run to the grid
                // can end with just InPit going false. Either way the car is out; without the
                // second case, cars that hadn't stopped yet stayed "in the pits" (dimmed) all race.
                if (d.optBoolean("PitOut") || (d.has("InPit") && !d.optBoolean("InPit") && state == LapState.PIT)) {
                    state = LapState.OUT_LAP
                }
                if (d.has("NumberOfLaps")) {
                    val n = d.optInt("NumberOfLaps")
                    val before = laps[number]
                    laps[number] = n
                    // Crossing the line: an out lap becomes a push lap, push and cool-down alternate.
                    if (before != null && n > before) {
                        crossings += number to time
                        state = when (state) {
                            LapState.OUT_LAP, LapState.COOL_DOWN -> LapState.PUSH
                            LapState.PUSH -> LapState.COOL_DOWN
                            else -> state
                        }
                    }
                }
                if (d.optBoolean("KnockedOut")) state = LapState.KNOCKED_OUT
                if (state != current[number]) {
                    current[number] = state
                    times.getOrPut(number) { mutableListOf() }.add(time)
                    values.getOrPut(number) { mutableListOf() }.add(state)
                }
            }
        }
        return StateParse(
            states = times.mapValues { (number, t) -> StateLog(t.toLongArray(), values.getValue(number).toTypedArray()) },
            crossings = crossings,
        )
    }

    /**
     * Traces the circuit from one lap: from a point on track, follow a car until it comes back
     * to the same spot. The shortest such loop across a few cars is a clean racing line, not a
     * trip through the pit lane.
     */
    internal fun traceOutline(tracks: List<TrackBuilder>): FloatArray {
        var best: FloatArray? = null
        var bestTime = Long.MAX_VALUE
        for (track in tracks.sortedByDescending { it.size }.take(4)) {
            for (k in 1..9) {
                val start = track.size * k / 10
                val loop = track.loopFrom(start) ?: continue
                if (loop.second < bestTime) {
                    bestTime = loop.second
                    best = loop.first
                }
            }
        }
        return best ?: FloatArray(0)
    }

    /** When a session's streams count from: the heartbeat's clock, unless the cars' own times disagree. */
    internal fun streamStart(heartbeatStream: String, positions: String): Long {
        val heartbeat = parseStreamStart(heartbeatStream)
        return positionsStreamStart(positions)?.takeIf { abs(it - heartbeat) > STREAM_START_SLACK_MS } ?: heartbeat
    }

    internal fun fetchStream(path: String): String = fetch(path)

    private fun fetch(path: String): String =
        open(path).use { it.readBytes() }.toString(Charsets.UTF_8).removePrefix("﻿")

    private fun open(path: String): java.io.InputStream {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        if (code == 401 || code == 403) throw IOException("F1's timing archive refused the login (HTTP $code)")
        if (code >= 400) throw IOException("HTTP $code for ${path.substringAfterLast('/')}")
        return conn.inputStream
    }

    internal fun inflate(quoted: String): String {
        val bytes = Base64.getDecoder().decode(quoted.trim().trim('"'))
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            return out.toString("UTF-8").removePrefix("﻿")
        } finally {
            inflater.end()
        }
    }

    private fun offsetMs(line: String): Long = streamOffsetMs(line)

    private val WILL_AT = Regex("""^(?:THE )?([A-Z0-9 ]+?) WILL (RESUME|RESTART|START) AT (\d{1,2}):(\d{2})""")
    private val ESTIMATED = Regex("""ESTIMATED TIME OF (?:THE )?([A-Z0-9 ]+?) (RESUMPTION|RESTART|START) ?-? ?(\d{1,2}):(\d{2})""")

    companion object {
        internal const val BASE = "https://livetiming.formula1.com/static/"
        /** Lines read to find a stream's start. */
        private const val START_SAMPLES = 50
        /** The heartbeat and the car positions may disagree by this much before the cars win. */
        private const val STREAM_START_SLACK_MS = 60_000L
        private val SESSION_PART = Regex("\"SessionPart\":(\\d+)")
        /** A video may start a little before its session officially does (build-up). */
        private const val SESSION_SLACK_MS = 30 * 60_000L
        /** A session plus its build-up and aftermath: a video this long after a start still belongs to it. */
        private const val SESSION_SPAN_MS = 4 * 3_600_000L
        /** How F1 names session folders, for finding one the index doesn't list yet. */
        private val SESSION_FOLDERS = listOf(
            "Race", "Sprint", "Qualifying", "Sprint_Qualifying", "Sprint_Shootout",
            "Practice_1", "Practice_2", "Practice_3",
        )
        // Grid search: sample every second; a car is still if it moved under 3 m in 4 s; the
        // grid sits within 12% of a lap before the line; at least 12 cars make a grid; stops
        // closer than a minute apart are one stop; pole sits about 5 m behind the line.
        private const val STEP_MS = 1_000L
        private const val STILL_MS = 4_000L
        private const val STILL_UNITS = 30f
        private const val GRID_ZONE = 0.12f
        private const val GRID_CARS = 12
        private const val MOVING_MS = 60_000L
        private const val POLE_TO_LINE_UNITS = 50f
    }
}

/** Grows one car's samples, then freezes them into a [DriverTrack]. */
class TrackBuilder {
    private var t = LongArray(4096)
    private var x = FloatArray(4096)
    private var y = FloatArray(4096)
    var size = 0
        private set

    fun add(time: Long, px: Float, py: Float) {
        // Samples can repeat or arrive slightly out of order; keep time increasing.
        if (size > 0 && time <= t[size - 1]) return
        if (size == t.size) {
            t = t.copyOf(size * 2)
            x = x.copyOf(size * 2)
            y = y.copyOf(size * 2)
        }
        t[size] = time
        x[size] = px
        y[size] = py
        size++
    }

    fun build() = DriverTrack(t, x, y, size)

    /** As [build], with [earlier]'s samples from before these in front: the archive's part of a live session. */
    fun build(earlier: TrackBuilder?): DriverTrack {
        if (earlier == null) return build()
        val first = if (size > 0) t[0] else Long.MAX_VALUE
        var k = 0
        while (k < earlier.size && earlier.t[k] < first) k++
        if (k == 0) return build()
        val n = k + size
        val nt = LongArray(n)
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        System.arraycopy(earlier.t, 0, nt, 0, k)
        System.arraycopy(earlier.x, 0, nx, 0, k)
        System.arraycopy(earlier.y, 0, ny, 0, k)
        System.arraycopy(t, 0, nt, k, size)
        System.arraycopy(x, 0, nx, k, size)
        System.arraycopy(y, 0, ny, k, size)
        return DriverTrack(nt, nx, ny, n)
    }

    /** The points of one lap starting at [start] and its duration, or null if it doesn't close. */
    fun loopFrom(start: Int): Pair<FloatArray, Long>? {
        var travelled = 0f
        for (i in start + 1 until size) {
            if (t[i] - t[i - 1] > 2_000) return null
            travelled += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            if (travelled > 120_000) return null
            if (travelled > 20_000 && hypot(x[i] - x[start], y[i] - y[start]) < 300f) {
                val points = FloatArray((i - start + 1) * 2)
                for (k in start..i) {
                    points[(k - start) * 2] = x[k]
                    points[(k - start) * 2 + 1] = y[k]
                }
                return points to (t[i] - t[start])
            }
        }
        return null
    }
}
