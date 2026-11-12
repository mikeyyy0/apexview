package app.apexview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** A circuit traced in an earlier session, kept so a live session has its map from the start. */
class SavedOutline(val outline: FloatArray, val hasStartFinish: Boolean) {
    fun toJson(): String = JSONObject()
        .put("outline", JSONArray(outline.map { it.toDouble() }))
        .put("hasStartFinish", hasStartFinish)
        .toString()

    companion object {
        fun fromJson(text: String?): SavedOutline? = runCatching {
            val j = JSONObject(text ?: return null)
            val a = j.getJSONArray("outline")
            SavedOutline(FloatArray(a.length()) { a.getDouble(it).toFloat() }, j.getBoolean("hasStartFinish"))
        }.getOrNull()
    }
}

/**
 * F1's live timing, as it happens, over its SignalR Core hub (the feed MultiViewer and FastF1
 * use). Needs the F1 TV subscription token, like the archive. Keeps everything received and
 * hands out a [SessionTiming] built from it every couple of seconds, so the map, the cards and
 * the lap states work on a live session exactly as on a replay. The broadcast runs well behind
 * the timing, so there's always data ahead of the picture.
 *
 * Messages are JSON separated by 0x1E. After the handshake, Subscribe returns each topic's state
 * so far (a completion, type 3); then every change arrives as a "feed" invocation (type 1) with
 * the topic, the change and its UTC time.
 */
class LiveTimingFeed(
    private val context: android.content.Context,
    private val token: String,
    private val userAgent: String,
    private val ref: SessionRef,
    private val saved: SavedOutline?,
) {
    private val archive = LiveTimingArchive(token, userAgent)

    // Everything received, guarded by `this`.
    private val tracks = mutableMapOf<String, TrackBuilder>()
    private val speeds = mutableMapOf<String, SpeedBuilder>()
    private val driverList = JSONObject()
    private val timingLines = StreamText()
    private val tyreLines = StreamText()
    private val statusLines = StreamText()
    private val controlLines = StreamText()
    /** The cars before connecting, from the archive: live timing only sends what happens from then on. */
    private var pastTracks: Map<String, TrackBuilder> = emptyMap()
    private var streamStart: Long? = null
    private var sessionType = ""
    private var sessionName = ""
    /** The race's lap count (CurrentLap, TotalLaps); F1 sends it for races only. */
    private var currentLap: Int? = null
    private var totalLaps: Int? = null
    private var sessionPath = ""
    private val radio = mutableListOf<RadioClip>()

    // Worked out from the above now and then: it gets slower to redo as the session goes on.
    private var parsedAt = 0L
    private var states = StateParse(emptyMap(), emptyList())
    private var laps: Map<String, DriverLaps> = emptyMap()
    private var tyres: Map<String, TyreLog> = emptyMap()
    private var traced: SavedOutline? = saved
    private var tracedAt = 0L
    private var corners: List<Corner>? = null
    private var sectors: List<Corner> = emptyList()
    private val trackStatusLines = StreamText()
    private val clockLines = StreamText()
    private var parts: List<Pair<Long, Int>> = emptyList()
    /** The track's offset from GMT ("02:00:00"), for restart times given in local time. */
    private var gmtOffset: String? = null
    /** The published circuit shape, used until a lap with its finish line is traced. */
    private var circuitOutline: FloatArray? = null
    private val topicCounts = mutableMapOf<String, Int>()

    /** A track this session traced for itself, worth keeping for next time. */
    var newOutline: SavedOutline? = null
        private set

    /**
     * Follows the shared live connection until cancelled: first what it has kept of the session,
     * then each change. [onTiming] gets each new picture of the session; [onStatus] a line for
     * the map sheet (null when all is well). Ends with an error if live timing moves on to
     * another session.
     */
    suspend fun run(onTiming: (SessionTiming) -> Unit, onStatus: (String?) -> Unit): Nothing = coroutineScope {
        val handle = LiveConnection.open(context, token, userAgent)
        val moved = kotlinx.coroutines.CompletableDeferred<Unit>()
        val listener = object : LiveConnection.Listener {
            override fun onMessage(message: LiveConnection.Message) =
                receive(message.topic, message.data, message.utc, message.opening)
            override fun onStatus(status: String?) = onStatus(status)
            override fun onSessionChanged() {
                moved.complete(Unit)
            }
        }
        try {
            launch {
                while (true) {
                    delay(REFRESH_MS)
                    val timing = withContext(Dispatchers.Default) { snapshot() }
                    if (timing != null) {
                        onTiming(timing)
                        onStatus(null)
                    } else if (streamStart != null) {
                        onStatus("Live map: waiting for the circuit…")
                    }
                    if (tracks.isEmpty() && streamStart != null) onStatus("Live timing is connected, but F1 isn't sending car positions")
                }
            }
            launch(Dispatchers.IO) { backfill() }
            // The circuit's own outline and corners, so the track is up before any car has done a
            // lap since connecting. A traced lap from an earlier session (with its finish line) wins.
            if (corners == null) launch(Dispatchers.IO) {
                val circuit = ref.circuitKey?.let { runCatching { archive.fetchCircuit(it, ref.year) }.getOrNull() }
                corners = circuit?.corners.orEmpty()
                sectors = circuit?.sectors.orEmpty()
                circuit?.outline?.takeIf { it.size >= 8 }?.let { synchronized(this@LiveTimingFeed) { circuitOutline = it } }
                Log.i(TAG, "circuit ${ref.circuitKey}: ${circuit?.outline?.size?.div(2)} outline points, ${corners?.size} corners")
            }
            launch {
                LiveConnection.listen(listener)
                // Read it at the next refresh, not whenever the next parse falls due.
                synchronized(this@LiveTimingFeed) { parsedAt = 0L }
            }
            moved.await()
            throw IOException("live timing moved on to another session")
        } finally {
            LiveConnection.unlisten(listener)
            handle.close()
        }
    }

    /** One topic's change (or its state on subscribing). */
    @Synchronized
    private fun receive(topic: String, data: Any?, utc: Long, opening: Boolean = false) {
        if (data == null) return
        val seen = (topicCounts[topic] ?: 0) + 1
        topicCounts[topic] = seen
        if (seen == 1 || seen % 500 == 0) Log.i(TAG, "$topic x$seen, ${tracks.size} cars tracked")
        val start = streamStart ?: (utc - HEAD_START_MS).also { streamStart = it }
        when (topic) {
            "Position.z" -> runCatching { JSONObject(archive.inflate(data.toString())) }.getOrNull()
                ?.let { archive.addPositions(it, tracks) }
            "CarData.z" -> runCatching { JSONObject(archive.inflate(data.toString())) }.getOrNull()
                ?.let { archive.addCarData(it, speeds) }
            "DriverList" -> (data as? JSONObject)?.let { merge(driverList, it) }
            "SessionInfo" -> (data as? JSONObject)?.let { info ->
                info.optString("Type").takeIf { it.isNotEmpty() }?.let { sessionType = it }
                info.optString("Name").takeIf { it.isNotEmpty() }?.let { sessionName = it }
                info.optString("Path").takeIf { it.isNotEmpty() }?.let { sessionPath = it }
                info.optString("GmtOffset").takeIf { it.isNotEmpty() }?.let { gmtOffset = it }
            }
            "TeamRadio" -> (data as? JSONObject)?.let { archive.addRadio(it, radio) }
            "LapCount" -> (data as? JSONObject)?.let { count ->
                if (count.has("CurrentLap")) currentLap = count.optInt("CurrentLap")
                if (count.has("TotalLaps")) totalLaps = count.optInt("TotalLaps")
            }
            "TimingData" -> append(timingLines, start, utc, data, opening)
            "TimingAppData" -> append(tyreLines, start, utc, data, opening)
            "SessionStatus" -> append(statusLines, start, utc, data, opening)
            "RaceControlMessages" -> append(controlLines, start, utc, data, opening)
            "TrackStatus" -> append(trackStatusLines, start, utc, data, opening)
            "ExtrapolatedClock" -> append(clockLines, start, utc, data, opening)
        }
    }

    /** Adds a change as a jsonStream line ("HH:MM:SS.mmm" + JSON), the form the archive parsers read. */
    private fun append(to: StreamText, start: Long, utc: Long, data: Any, opening: Boolean = false) {
        val offset = if (opening) 0L else (utc - start).coerceAtLeast(0)
        val line = streamLine(offset, data.toString())
        // Any changes that beat the state here came after it.
        if (opening) to.opening.insert(0, line) else to.lines.append(line)
    }

    /**
     * One topic as jsonStream text: the state on first subscribing (or, once the archive has
     * been read, the session up to then) and the changes since.
     */
    private class StreamText {
        val opening = StringBuilder()
        val lines = StringBuilder()
        var past: String? = null
        override fun toString(): String = (past ?: opening.toString()) + lines
    }

    /**
     * The session before connecting, from F1's archive, which it writes as the session goes.
     * Without it, a picture from before then (started from the beginning, or wound back) had
     * every car standing where it was first seen. Tried again while the archive lags behind.
     */
    private suspend fun backfill() {
        repeat(BACKFILL_TRIES) {
            val path = synchronized(this) { sessionPath }
            val joined = synchronized(this) { streamStart }?.plus(HEAD_START_MS)
            if (path.isEmpty() || joined == null) {
                delay(1_000)
                return@repeat
            }
            try {
                val positions = archive.fetchStream(path + "Position.z.jsonStream")
                val archiveStart = archive.streamStart(archive.fetchStream(path + "Heartbeat.jsonStream"), positions)
                val cars = archive.parsePositions(positions)
                fun past(file: String) = runCatching { rebaseStream(archive.fetchStream(path + file), archiveStart, joined - HEAD_START_MS, joined) }
                    .onFailure { Log.w(TAG, "archive $file: ${it.message}") }.getOrNull()
                val timing = past("TimingData.jsonStream")
                val tyre = past("TimingAppData.jsonStream")
                val status = past("SessionStatus.jsonStream")
                val control = past("RaceControlMessages.jsonStream")
                val trackStatus = past("TrackStatus.jsonStream")
                val clock = past("ExtrapolatedClock.jsonStream")
                val reaches = cars.values.maxOfOrNull { it.build().endMs } ?: 0L
                synchronized(this) {
                    pastTracks = cars
                    timingLines.past = timing
                    tyreLines.past = tyre
                    statusLines.past = status
                    controlLines.past = control
                    trackStatusLines.past = trackStatus
                    clockLines.past = clock
                    parsedAt = 0L
                }
                Log.i(TAG, "archive backfill: ${cars.size} cars, reaching ${(joined - reaches) / 1000}s before connecting")
                if (joined - reaches < BACKFILL_GAP_MS) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Refused (403) until F1 publishes it, after the session.
                Log.w(TAG, "archive backfill: ${e.message}")
            }
            delay(BACKFILL_RETRY_MS)
        }
    }

    /** Partial updates fill in or change fields; nested objects merge the same way. */
    private fun merge(into: JSONObject, from: JSONObject) {
        from.keys().forEach { key ->
            val value = from.get(key)
            val existing = into.optJSONObject(key)
            if (value is JSONObject && existing != null) merge(existing, value) else into.put(key, value)
        }
    }

    /** The session so far, or null until there's a track to draw it on. */
    @Synchronized
    private fun snapshot(): SessionTiming? {
        val start = streamStart ?: return null
        val now = System.currentTimeMillis()
        if (now - parsedAt > PARSE_MS) {
            parsedAt = now
            val text = timingLines.toString()
            states = runCatching { archive.parseStates(text, start) }.getOrDefault(states)
            laps = runCatching { DriverTimingParser.parse(text, start) }.getOrDefault(laps)
            tyres = runCatching { TyreParser.parse(tyreLines.toString(), start) }.getOrDefault(tyres)
            parts = runCatching { archive.parseParts(text, start) }.getOrDefault(parts)
        }
        val built = (tracks.keys + pastTracks.keys).associateWith { number ->
            tracks[number]?.build(pastTracks[number]) ?: pastTracks.getValue(number).build()
        }
        // No track from an earlier session: trace one from a lap driven since connecting.
        if (traced == null && now - tracedAt > TRACE_MS) {
            tracedAt = now
            val loop = archive.traceOutline(tracks.values.toList())
            if (loop.isNotEmpty()) {
                val line = archive.findStartFinish(loop, states.crossings, built)
                traced = SavedOutline(if (line != null) archive.startAt(loop, line) else loop, line != null)
                if (line != null) newOutline = traced
            }
        }
        val outline = traced ?: circuitOutline?.let { shape ->
            // Turned to start at the finish line once lap crossings show where it is.
            val line = archive.findStartFinish(shape, states.crossings, built)
            SavedOutline(if (line != null) archive.startAt(shape, line) else shape, line != null)
        } ?: return null
        val status = statusLines.toString()
        return SessionTiming(
            type = sessionType.ifEmpty { ref.title },
            name = sessionName.ifEmpty { ref.title },
            drivers = archive.parseDrivers(driverList),
            tracks = built,
            states = states.states,
            outline = outline.outline,
            hasStartFinish = outline.hasStartFinish,
            corners = corners.orEmpty(),
            startUtcMs = start + HEAD_START_MS,
            circuitKey = ref.circuitKey,
            startedUtc = runCatching { archive.parseSessionStarted(status, start) }.getOrNull(),
            finishedUtc = runCatching { archive.parseChequered(controlLines.toString(), start) }.getOrNull(),
            marshalSectors = sectors,
            flags = controlLines.toString().let { control ->
                TrackFlags(
                    runCatching { archive.parseSectorFlags(control, start) }.getOrDefault(emptyList()),
                    runCatching { archive.parseTrackStatus(trackStatusLines.toString(), start) }.getOrDefault(emptyList()),
                    runCatching { archive.parseSafetyCarIn(control, start) }.getOrDefault(emptyList()),
                    gmtOffset?.let { runCatching { archive.parseResumptions(control, start, it) }.getOrNull() }.orEmpty(),
                    runCatching { archive.parseRedFlags(control, start) }.getOrDefault(emptyList()),
                )
            },
            laps = laps,
            radio = radio.sortedBy { it.utc },
            radioBase = LiveTimingArchive.BASE + sessionPath,
            tyres = tyres,
            currentLap = currentLap,
            totalLaps = totalLaps,
            clock = runCatching { SessionClock(archive.parseClock(clockLines.toString(), start), parts) }.getOrDefault(SessionClock.NONE),
            live = true,
        ).also { timing ->
            timing.speeds = speeds.mapValues { it.value.build() }
            timing.positionsFromUtc = built.values.filter { it.endMs > 0 }.minOfOrNull { it.startMs }
        }
    }

    companion object {
        private const val TAG = "ApexviewLive"
        private const val REFRESH_MS = 2_000L
        /**
         * How far before connecting the stream counts from: more than the picture can be behind,
         * and room for the session so far from the archive.
         */
        internal const val HEAD_START_MS = 12 * 3_600_000L
        // F1 publishes the archive once the session is over: keep asking for a few hours.
        private const val BACKFILL_TRIES = 180
        private const val BACKFILL_RETRY_MS = 120_000L
        /** The archive is close enough to the live timing once it reaches this near. */
        private const val BACKFILL_GAP_MS = 30_000L
        // Lap states and timing cards are re-read every 10 s: the picture runs far enough behind
        // the timing that they're never late.
        private const val PARSE_MS = 10_000L
        private const val TRACE_MS = 20_000L
    }
}
