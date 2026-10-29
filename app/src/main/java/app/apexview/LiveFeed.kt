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
    private val token: String,
    private val userAgent: String,
    private val ref: SessionRef,
    private val saved: SavedOutline?,
) {
    private val archive = LiveTimingArchive(token, userAgent)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // Everything received, guarded by `this`.
    private val tracks = mutableMapOf<String, TrackBuilder>()
    private val driverList = JSONObject()
    private val timingLines = StringBuilder()
    private val tyreLines = StringBuilder()
    private val statusLines = StringBuilder()
    private val controlLines = StringBuilder()
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
    private val trackStatusLines = StringBuilder()
    /** The track's offset from GMT ("02:00:00"), for restart times given in local time. */
    private var gmtOffset: String? = null
    /** The published circuit shape, used until a lap with its finish line is traced. */
    private var circuitOutline: FloatArray? = null
    private val topicCounts = mutableMapOf<String, Int>()

    /** A track this session traced for itself, worth keeping for next time. */
    var newOutline: SavedOutline? = null
        private set

    /**
     * Connects and keeps going until cancelled, reconnecting after a drop. [onTiming] gets each
     * new picture of the session; [onStatus] a line for the map sheet (null when all is well).
     */
    suspend fun run(onTiming: (SessionTiming) -> Unit, onStatus: (String?) -> Unit) = coroutineScope {
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
        // The circuit's own outline and corners, so the track is up before any car has done a
        // lap since connecting. A traced lap from an earlier session (with its finish line) wins.
        if (corners == null) launch(Dispatchers.IO) {
            val circuit = ref.circuitKey?.let { runCatching { archive.fetchCircuit(it, ref.year) }.getOrNull() }
            corners = circuit?.corners.orEmpty()
            sectors = circuit?.sectors.orEmpty()
            circuit?.outline?.takeIf { it.size >= 8 }?.let { synchronized(this@LiveTimingFeed) { circuitOutline = it } }
            Log.i(TAG, "circuit ${ref.circuitKey}: ${circuit?.outline?.size?.div(2)} outline points, ${corners?.size} corners")
        }
        while (true) {
            try {
                onStatus("Connecting to live timing…")
                // Decoding positions several times a second stays off the main thread.
                withContext(Dispatchers.Default) { connectOnce(onStatus) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onStatus("Live timing dropped (${e.message}); reconnecting…")
            }
            delay(RECONNECT_MS)
        }
    }

    /** One connection, until it closes. */
    private suspend fun connectOnce(onStatus: (String?) -> Unit) {
        val negotiated = withContext(Dispatchers.IO) { negotiate() }
        val inbox = Channel<String>(Channel.UNLIMITED)
        // The token goes in the URL too: browsers can't set headers on a WebSocket, so SignalR
        // servers commonly read it from access_token there.
        val request = Request.Builder()
            .url("$HUB?id=${negotiated.first}&access_token=$token")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .apply { negotiated.second?.let { header("Cookie", it) } }
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"protocol":"json","version":1}""" + RS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                inbox.trySend(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                inbox.close(IOException(response?.let { "HTTP ${it.code}" } ?: t.message ?: "connection failed"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbox.close(IOException("closed ($code)"))
            }
        })
        try {
            var subscribed = false
            for (frame in inbox) {
                for (raw in frame.split(RS)) {
                    if (raw.isBlank()) continue
                    val message = JSONObject(raw)
                    when (message.optInt("type", -1)) {
                        // The handshake's reply is an empty object: now ask for the topics.
                        -1 -> if (!subscribed) {
                            subscribed = true
                            val subscribe = JSONObject()
                                .put("type", 1)
                                .put("invocationId", "0")
                                .put("target", "Subscribe")
                                .put("arguments", JSONArray().put(JSONArray(TOPICS)))
                            socket.send(subscribe.toString() + RS)
                        }
                        1 -> if (message.optString("target") == "feed") {
                            val args = message.optJSONArray("arguments") ?: continue
                            receive(args.optString(0), args.opt(1), parseUtc(args.optString(2)))
                        }
                        3 -> {
                            message.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
                            val state = message.optJSONObject("result") ?: continue
                            Log.i(TAG, "subscribed: " + state.keys().asSequence().joinToString())
                            val now = System.currentTimeMillis()
                            state.keys().forEach { topic -> receive(topic, state.opt(topic), now) }
                            onStatus(null)
                        }
                        6 -> socket.send("""{"type":6}""" + RS)
                        7 -> throw IOException(message.optString("error").ifEmpty { "the server closed the connection" })
                    }
                }
            }
        } finally {
            socket.cancel()
        }
    }

    /** POST /negotiate: the connection token to open the socket with, and any sticky-session cookie. */
    private fun negotiate(): Pair<String, String?> {
        val request = Request.Builder()
            .url("$HUB/negotiate?negotiateVersion=1")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .post(ByteArray(0).toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw IOException("F1's live timing refused the login (HTTP ${response.code})")
            if (!response.isSuccessful) throw IOException("negotiate HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val id = json.optString("connectionToken").ifEmpty { json.optString("connectionId") }
            if (id.isEmpty()) throw IOException("no connection token")
            val cookies = response.headers("Set-Cookie").joinToString("; ") { it.substringBefore(';') }.ifEmpty { null }
            return id to cookies
        }
    }

    private fun parseUtc(text: String): Long =
        runCatching { Instant.parse(if (text.endsWith("Z")) text else text + "Z").toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    /** One topic's change (or its state on subscribing). */
    @Synchronized
    private fun receive(topic: String, data: Any?, utc: Long) {
        if (data == null) return
        val seen = (topicCounts[topic] ?: 0) + 1
        topicCounts[topic] = seen
        if (seen == 1 || seen % 500 == 0) Log.i(TAG, "$topic x$seen, ${tracks.size} cars tracked")
        val start = streamStart ?: (utc - 1_000).also { streamStart = it }
        when (topic) {
            "Position.z" -> runCatching { JSONObject(archive.inflate(data.toString())) }.getOrNull()
                ?.let { archive.addPositions(it, tracks) }
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
            "TimingData" -> append(timingLines, start, utc, data)
            "TimingAppData" -> append(tyreLines, start, utc, data)
            "SessionStatus" -> append(statusLines, start, utc, data)
            "RaceControlMessages" -> append(controlLines, start, utc, data)
            "TrackStatus" -> append(trackStatusLines, start, utc, data)
        }
    }

    /** Adds a change as a jsonStream line ("HH:MM:SS.mmm" + JSON), the form the archive parsers read. */
    private fun append(to: StringBuilder, start: Long, utc: Long, data: Any) {
        val offset = (utc - start).coerceAtLeast(0)
        to.append("%02d:%02d:%02d.%03d".format(offset / 3_600_000, offset / 60_000 % 60, offset / 1000 % 60, offset % 1000))
            .append(data.toString())
            .append('\n')
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
        }
        val built = tracks.mapValues { it.value.build() }
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
            startUtcMs = start,
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
        )
    }

    companion object {
        private const val TAG = "ApexviewLive"
        private const val HUB = "https://livetiming.formula1.com/signalrcore"
        private const val RS = "\u001e"
        private val TOPICS = listOf(
            "Heartbeat", "Position.z", "TimingData", "DriverList", "SessionInfo", "SessionStatus", "RaceControlMessages",
            "TeamRadio", "TimingAppData", "TrackStatus", "LapCount",
        )
        private const val REFRESH_MS = 2_000L
        // Lap states and timing cards are re-read every 10 s: the picture runs far enough behind
        // the timing that they're never late.
        private const val PARSE_MS = 10_000L
        private const val TRACE_MS = 20_000L
        private const val RECONNECT_MS = 5_000L
    }
}
