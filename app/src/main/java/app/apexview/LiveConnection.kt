package app.apexview

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.Writer
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The one connection to F1's live timing (SignalR over a WebSocket), shared by the viewer and
 * the background recorder, and written to disk as it arrives. Live timing only sends what
 * happens from the moment it's joined, and F1 publishes the session's archive only once it's
 * over, so this recording is the only way to have the cars from before a viewer opened it:
 * with the recorder running from the session's start, all of it.
 *
 * One file, for the session live now. Each line is a topic, its UTC time, whether it's the state
 * on subscribing, and the data. Car speeds aren't kept: they're most of what F1 sends.
 */
object LiveConnection {
    /** One topic's change, or its whole state on subscribing ([opening]). */
    class Message(val topic: String, val data: Any, val utc: Long, val opening: Boolean)

    interface Listener {
        fun onMessage(message: Message)
        fun onStatus(status: String?)
        /** Live timing moved on to another session: what was sent before belongs to the last one. */
        fun onSessionChanged()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // Guarded by `this`.
    private var job: Job? = null
    private var users = 0
    private val listeners = mutableListOf<Listener>()
    private var writer: Writer? = null
    /** The session the file holds (its folder in F1's archive). */
    private var recordedPath: String? = null
    /** Whether the file has its session's state on subscribing yet. */
    private var stateKept = false
    private var status: String? = null

    /** Subscribed, and the file is the session live now: what a listener is given first. */
    @Volatile private var ready = false

    /** A user of the connection; closing the last one disconnects. */
    class Handle internal constructor() {
        private var open = true
        fun close() {
            if (!open) return
            open = false
            release()
        }
    }

    /** Connects (or joins the connection already up). [token] is used until a newer one is saved. */
    @Synchronized
    fun open(context: Context, token: String, userAgent: String): Handle {
        users++
        if (job == null) {
            val app = context.applicationContext
            ready = false
            job = scope.launch { run(app, token, userAgent) }
        }
        return Handle()
    }

    @Synchronized
    private fun release() {
        users--
        if (users > 0) return
        job?.cancel()
        job = null
        ready = false
        runCatching { writer?.close() }
        writer = null
        recordedPath = null
        Log.i(TAG, "disconnected")
    }

    /** Whether something (the recorder) is keeping the connection up. */
    val connected: Boolean @Synchronized get() = job != null

    /**
     * Gives [listener] everything kept for the session live now, then each message as it comes.
     * Waits for the connection to say which session that is. Call off the main thread: a long
     * session's recording takes a moment to read.
     */
    suspend fun listen(listener: Listener) {
        while (!ready) {
            listener.onStatus(synchronized(this) { status } ?: "Connecting to live timing…")
            delay(250)
        }
        withContext(Dispatchers.IO) {
            synchronized(this@LiveConnection) {
                runCatching { writer?.flush() }
                var lines = 0
                runCatching {
                    recording?.takeIf { it.exists() }?.bufferedReader()?.useLines { all ->
                        all.forEach { line -> parse(line)?.let { listener.onMessage(it); lines++ } }
                    }
                }.onFailure { Log.w(TAG, "reading the recording: ${it.message}") }
                Log.i(TAG, "listener given $lines kept messages")
                listener.onStatus(status)
                listeners += listener
            }
        }
    }

    @Synchronized
    fun unlisten(listener: Listener) {
        listeners -= listener
    }

    private var recording: File? = null

    private suspend fun run(context: Context, firstToken: String, userAgent: String) = kotlinx.coroutines.coroutineScope {
        synchronized(this@LiveConnection) { recording = File(context.cacheDir, "live/recording.txt") }
        launch {
            while (true) {
                delay(FLUSH_MS)
                synchronized(this@LiveConnection) { runCatching { writer?.flush() } }
            }
        }
        while (true) {
            val token = TokenStore.load(context) ?: firstToken
            try {
                setStatus("Connecting to live timing…")
                connectOnce(token, userAgent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Live timing dropped (${e.message}); reconnecting…")
            }
            delay(RECONNECT_MS)
        }
    }

    private fun setStatus(text: String?) = synchronized(this) {
        status = text
        listeners.forEach { it.onStatus(text) }
    }

    /** One connection, until it closes. */
    private suspend fun connectOnce(token: String, userAgent: String) {
        val negotiated = withContext(Dispatchers.IO) { negotiate(token, userAgent) }
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
                        // Changes from before the first state are in it.
                        1 -> if (ready && message.optString("target") == "feed") {
                            val args = message.optJSONArray("arguments") ?: continue
                            val data = args.opt(1) ?: continue
                            deliver(Message(args.optString(0), data, parseUtc(args.optString(2)), false))
                        }
                        3 -> {
                            message.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
                            val state = message.optJSONObject("result") ?: continue
                            Log.i(TAG, "subscribed: " + state.keys().asSequence().joinToString())
                            subscribedTo(state)
                            setStatus(null)
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

    /**
     * The state on subscribing. Carries on the file if it's this session's (the app restarted,
     * or the connection dropped), else starts it afresh.
     */
    private fun subscribedTo(state: JSONObject) {
        val path = state.optJSONObject("SessionInfo")?.optString("Path").orEmpty()
        val now = System.currentTimeMillis()
        synchronized(this) {
            val file = recording ?: return
            if (recordedPath == null) {
                if (!file.exists()) adoptOldRecording(file, path)
                // First since connecting: what the file on disk holds.
                val kept = runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull()
                recordedPath = kept?.takeIf { it.startsWith(HEADER) }?.removePrefix(HEADER)
                stateKept = recordedPath != null
            }
            if (recordedPath != path || writer == null) {
                val fresh = recordedPath != path
                if (fresh && ready) listeners.forEach { it.onSessionChanged() }
                if (fresh) listeners.clear()
                runCatching { writer?.close() }
                file.parentFile?.mkdirs()
                writer = runCatching { java.io.BufferedWriter(java.io.FileWriter(file, !fresh)) }.getOrNull()
                if (fresh) {
                    runCatching { writer?.write(HEADER + path + '\n') }
                    stateKept = false
                    Log.i(TAG, "recording a new session: $path")
                } else {
                    Log.i(TAG, "recording on: $path (${file.length() / 1024} KB kept)")
                }
                recordedPath = path
            }
            // The first state counts from the start of the stream: the picture is behind the
            // timing, and would otherwise show nothing until it caught up. Later ones (after a
            // drop) are changes like any other.
            val opening = !stateKept
            stateKept = true
            state.keys().forEach { topic -> state.opt(topic)?.let { deliver(Message(topic, it, now, opening)) } }
            ready = true
        }
    }

    /**
     * 0.99.1 kept a recording per broadcast, while it was open, in the same lines: one of this
     * session's becomes the recording, so updating mid-session doesn't lose it.
     */
    private fun adoptOldRecording(file: File, path: String) {
        val old = file.parentFile?.listFiles()
            ?.filter { it != file && it.name.endsWith(".txt") && System.currentTimeMillis() - it.lastModified() < 6 * 3_600_000L }
            ?.maxByOrNull { it.lastModified() } ?: return
        runCatching {
            val itsPath = old.bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith("SessionInfo\t") }?.let { parse(it) }
                    ?.let { (it.data as? JSONObject)?.optString("Path") }
            }
            if (itsPath != path) return
            file.outputStream().buffered().use { out ->
                out.write((HEADER + path + '\n').toByteArray())
                old.inputStream().use { it.copyTo(out) }
            }
            Log.i(TAG, "kept 0.99.1's recording of $path (${old.length() / 1024} KB)")
        }.onFailure { Log.w(TAG, "0.99.1's recording: ${it.message}") }
        file.parentFile?.listFiles()?.filter { it != file }?.forEach { it.delete() }
    }

    private fun deliver(message: Message) = synchronized(this) {
        if (message.topic != "CarData.z" && message.topic != "Heartbeat") writer?.let { out ->
            runCatching {
                out.write(message.topic + '\t' + message.utc + '\t' + (if (message.opening) '1' else '0') + '\t' + message.data + '\n')
            }
        }
        listeners.forEach { it.onMessage(message) }
    }

    /** A kept line back into a message; null for the header or anything unreadable. */
    internal fun parse(line: String): Message? {
        if (line.startsWith(HEADER)) return null
        val parts = line.split('\t', limit = 4)
        if (parts.size < 4) return null
        val text = parts[3]
        val data: Any = runCatching {
            when {
                text.startsWith("{") -> JSONObject(text)
                text.startsWith("[") -> JSONArray(text)
                else -> text
            }
        }.getOrNull() ?: return null
        val utc = parts[1].toLongOrNull() ?: return null
        return Message(parts[0], data, utc, parts[2] == "1")
    }

    /** POST /negotiate: the connection token to open the socket with, and any sticky-session cookie. */
    private fun negotiate(token: String, userAgent: String): Pair<String, String?> {
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

    private const val TAG = "ApexviewLive"
    private const val HUB = "https://livetiming.formula1.com/signalrcore"
    private const val RS = "\u001e"
    private const val HEADER = "#session\t"
    private val TOPICS = listOf(
        "Heartbeat", "Position.z", "CarData.z", "TimingData", "DriverList", "SessionInfo", "SessionStatus", "RaceControlMessages",
        "TeamRadio", "TimingAppData", "TrackStatus", "LapCount", "ExtrapolatedClock",
    )
    private const val RECONNECT_MS = 5_000L
    private const val FLUSH_MS = 2_000L
}
