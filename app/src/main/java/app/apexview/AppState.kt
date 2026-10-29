package app.apexview

import androidx.media3.common.C
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.AndroidUiDispatcher
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.lazy.LazyListState
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Calendar
import kotlin.math.abs

/** A feed's window on the canvas. Positions and sizes are in pixels. */
class Tile(val id: Int, val contentId: Long, channel: Channel, feed: FeedPlayer) {
    var channel by mutableStateOf(channel)
    var feed by mutableStateOf(feed)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var w by mutableFloatStateOf(640f)
    var h by mutableFloatStateOf(360f)
    var z by mutableFloatStateOf(0f)
    var audible by mutableStateOf(false)
    /** Pinned tiles are never replaced by a car picked on the map. */
    var pinned by mutableStateOf(false)
    /** Showing, but its picture paused while the phone is too hot (sound and place kept). */
    var coolingOff by mutableStateOf(false)
    /** When this tile last took a car picked on the map (or was added). Oldest goes first. */
    var mapPickedAt = System.currentTimeMillis()

    fun resize(width: Float) {
        w = width.coerceIn(MIN_WIDTH, 4000f)
        h = w * 9f / 16f
    }

    fun place(slot: Slot) {
        x = slot.x
        y = slot.y
        resize(slot.w)
    }

    companion object {
        const val MIN_WIDTH = 240f
    }
}

enum class MapMode(val label: String) {
    TRACK("Track"),
    EDGE("Edge"),
    OFF("Off"),
}

/** The track map's own window. Any shape; the track is fitted inside it. */
class MapPanel {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var w by mutableFloatStateOf(600f)
    var h by mutableFloatStateOf(600f)
    /** Turn added to the best-fitting angle, in degrees. */
    var rotation by mutableFloatStateOf(0f)
    /** False until placed, by default or from storage. */
    var placed = false

    fun toJson(width: Float, height: Float, opacity: Float): String = JSONObject()
        .put("x", (x / width).toDouble()).put("y", (y / height).toDouble())
        .put("w", (w / width).toDouble()).put("h", (h / height).toDouble())
        .put("rotation", rotation.toDouble()).put("opacity", opacity.toDouble())
        .toString()

    /** Puts back a stored panel on a canvas of this size; returns its opacity. */
    fun restore(text: String?, width: Float, height: Float): Float? = runCatching {
        val j = JSONObject(text ?: return null)
        x = j.getDouble("x").toFloat() * width
        y = j.getDouble("y").toFloat() * height
        w = j.getDouble("w").toFloat() * width
        h = j.getDouble("h").toFloat() * height
        rotation = j.getDouble("rotation").toFloat()
        placed = true
        j.getDouble("opacity").toFloat()
    }.getOrNull()

    fun place(area: Area) {
        x = area.x
        y = area.y
        w = area.w
        h = area.h
    }

    fun scale(factor: Float) {
        val cx = x + w / 2
        val cy = y + h / 2
        w = (w * factor).coerceIn(MIN_SIZE, 5000f)
        h = (h * factor).coerceIn(MIN_SIZE, 5000f)
        x = cx - w / 2
        y = cy - h / 2
    }

    fun stretch(dx: Float, dy: Float) {
        w = (w + dx).coerceAtLeast(MIN_SIZE)
        h = (h + dy).coerceAtLeast(MIN_SIZE)
    }

    companion object {
        const val MIN_SIZE = 200f
    }
}

/** Where the browse side of the app is. The viewer sits on top whenever feeds are playing. */
sealed interface Screen {
    data object Home : Screen
    /** [artKey] names the card or hero it was opened from, whose artwork grows into the page's. */
    data class Weekend(val weekend: WeekendInfo, val artKey: String? = null) : Screen
    data object Settings : Screen
    data object Calendar : Screen
    /** A show's or documentary's episodes. */
    data class Collection(val pageId: Int, val title: String) : Screen
    /** Popular races from every season. */
    data object Archive : Screen
}

/**
 * One slide of home's carousel: something to watch next and why, a session to pick back up, or
 * a weekend whose schedule is the thing to look at.
 */
data class Pick(val reason: String, val item: CatalogItem? = null, val weekend: WeekendInfo? = null, val resume: SavedWatch? = null)

/** What's been watched of a video: when last, for how long, and whether through to the end. */
data class Seen(val at: Long, val watchedMs: Long, val finished: Boolean)

enum class AuthState { CHECKING, SIGNED_OUT, SIGNED_IN }

/**
 * Enough to put a viewing session back as it was: the session, its feeds (by channel), which
 * one was big, where the audio was, and how far in. Saved to storage, so it survives a crash or
 * a force-close.
 */
data class SavedWatch(
    val contentId: Long,
    val title: String,
    val image: String?,
    val positionMs: Long,
    val durationMs: Long,
    val live: Boolean,
    /** Channel IDs of the feeds, -1 for a single-feed video's only feed. */
    val channels: List<Int>,
    val bigChannel: Int,
    val audioChannel: Int,
    val layoutMode: String,
    val mapMode: String,
    /** Where each feed was, in the order of [channels]; null for sessions saved before 0.16. */
    val layout: SavedLayout? = null,
) {
    fun toJson(): String = toJsonObject().toString()

    fun toJsonObject(): JSONObject = JSONObject()
        .put("contentId", contentId).put("title", title).put("image", image ?: "")
        .put("positionMs", positionMs).put("durationMs", durationMs).put("live", live)
        .put("channels", JSONArray(channels)).put("bigChannel", bigChannel).put("audioChannel", audioChannel)
        .put("layoutMode", layoutMode).put("mapMode", mapMode)
        .put("layout", layout?.toJson() ?: "")

    companion object {
        /** The recently watched list, newest first. */
        fun listFromJson(text: String?): List<SavedWatch> = runCatching {
            val list = JSONArray(text ?: return emptyList())
            (0 until list.length()).mapNotNull { fromJson(list.getJSONObject(it).toString()) }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<SavedWatch>): String = JSONArray(list.map { it.toJsonObject() }).toString()

        fun fromJson(text: String?): SavedWatch? = runCatching {
            val j = JSONObject(text ?: return null)
            val list = j.getJSONArray("channels")
            SavedWatch(
                contentId = j.getLong("contentId"),
                title = j.getString("title"),
                image = j.optString("image").ifEmpty { null },
                positionMs = j.getLong("positionMs"),
                durationMs = j.getLong("durationMs"),
                live = j.getBoolean("live"),
                channels = (0 until list.length()).map { list.getInt(it) },
                bigChannel = j.getInt("bigChannel"),
                audioChannel = j.getInt("audioChannel"),
                layoutMode = j.getString("layoutMode"),
                mapMode = j.getString("mapMode"),
                layout = SavedLayout.fromJson(j.optString("layout").ifEmpty { null }),
            )
        }.getOrNull()
    }
}

private fun Channel.key() = channelId ?: -1

/** The sheet for choosing which of a session's feeds to watch. */
class FeedPicker(val contentId: Long, val title: String) {
    var channels by mutableStateOf<List<Channel>?>(null)
    var error by mutableStateOf<String?>(null)
    /** New feeds to start. */
    val selected = mutableStateListOf<Channel>()
    /** Feeds on screen that were unpicked, to make room. */
    val removing = mutableStateListOf<Channel>()
    /** Shown under the title when a pick doesn't fit. */
    var note by mutableStateOf<String?>(null)
}

class AppState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val userAgent: String,
    private val chromeMajor: String,
    private val openLogin: () -> Unit,
) {
    private var api = F1TvApi(userAgent, chromeMajor)
    private val prefs = context.getSharedPreferences("f1mv", Context.MODE_PRIVATE)

    // Video budget: only feeds you can see decode video, each at its tile's size, less as the phone heats up.

    private val power = context.getSystemService(android.os.PowerManager::class.java)
    /** The phone's thermal status (PowerManager.THERMAL_STATUS_*). */
    var thermal by mutableIntStateOf(power?.currentThermalStatus ?: android.os.PowerManager.THERMAL_STATUS_NONE)
        private set
    private val thermalListener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
        val before = thermal
        thermal = status
        when {
            status >= android.os.PowerManager.THERMAL_STATUS_CRITICAL && before < android.os.PowerManager.THERMAL_STATUS_CRITICAL ->
                message = "The phone is very hot. Only the main feed shows video until it cools down; the others keep their sound and place."
            status >= android.os.PowerManager.THERMAL_STATUS_SEVERE && before < android.os.PowerManager.THERMAL_STATUS_SEVERE ->
                message = "The phone is running hot, so the feeds are at lower quality until it cools down."
        }
        applyVideoBudget()
    }

    init {
        power?.addThermalStatusListener(context.mainExecutor, thermalListener)
        // Feeds come and go, move, hide behind each other and off screen; twice a second is soon enough.
        // Waits first: this runs while AppState is still being built, before the tiles exist.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                applyVideoBudget()
            }
        }
    }

    fun close() {
        power?.removeThermalStatusListener(thermalListener)
    }

    /**
     * Video only for feeds that can be seen: not while browsing, not behind a full-screen feed,
     * not tucked away in laptop mode or hidden in the pop-out, not off the canvas or entirely
     * under another feed. Hidden ones keep playing their sound and stay in sync, so they come
     * back at the right moment (after a moment's buffering). Each visible feed asks for the size
     * of its tile; warm phones get less (3/4 size when hot, half when hotter), and a phone near
     * its limit shows video in the main feed only.
     */
    fun applyVideoBudget() {
        if (tiles.isEmpty()) return
        val shown = if (inPip) pipTile() else null
        val main = tiles.firstOrNull { it.id == bigTileId } ?: tiles.firstOrNull { it.audible } ?: tiles.maxByOrNull { it.w }
        val scale = when {
            thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> 0.5f
            thermal >= android.os.PowerManager.THERMAL_STATUS_MODERATE -> 0.75f
            else -> 1f
        }
        tiles.forEach { tile ->
            val visible = when {
                browsing -> false
                inPip -> tile === shown
                laptopActive -> tile.id == bigTileId || tiles.size == 1
                focusTileId != null -> tile.id == focusTileId
                else -> onCanvas(tile) && tiles.none { it !== tile && it.z > tile.z && covers(it, tile) }
            }
            // Laptop mode: the feed that was up top last keeps its picture going out of sight, so
            // going back to it is instant rather than a fresh ~4 MB chunk. Not once the phone is warm.
            val warm = !visible && !browsing && !inPip && laptopActive && tile.id == previousTopId &&
                thermal < android.os.PowerManager.THERMAL_STATUS_MODERATE
            if (warm) {
                tile.coolingOff = false
                tile.feed.keepVideo()
                return@forEach
            }
            val cooling = thermal >= android.os.PowerManager.THERMAL_STATUS_CRITICAL && tile !== main
            tile.coolingOff = visible && cooling
            val (w, h) = when {
                // The pop-out is small whatever the tile's size on the canvas.
                inPip -> 640f to 360f
                else -> tile.w to tile.h
            }
            tile.feed.setVideo(visible && !cooling, (w * scale).toInt(), (h * scale).toInt())
        }
    }

    /** In laptop mode, the feed that was up top before the one there now. */
    private var previousTopId: Int? = null

    private fun onCanvas(t: Tile) =
        canvasW <= 0f || (t.x < canvasW && t.x + t.w > 0f && t.y < canvasH && t.y + t.h > 0f)

    private fun covers(a: Tile, b: Tile) =
        a.x <= b.x && a.y <= b.y && a.x + a.w >= b.x + b.w && a.y + a.h >= b.y + b.h
    val settings = Settings(context)
    val updater = Updater(context, scope)

    var auth by mutableStateOf(AuthState.CHECKING)
        private set
    var tier by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var pastedToken by mutableStateOf("")

    // Browse.

    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen get() = backStack.last()
    val season = Calendar.getInstance().get(Calendar.YEAR)
    val weekends = mutableStateListOf<WeekendInfo>()
    val liveNow = mutableStateListOf<CatalogItem>()
    val weekendContent = mutableStateMapOf<String, WeekendContent>()
    var homeError by mutableStateOf<String?>(null)
        private set
    var picker by mutableStateOf<FeedPicker?>(null)
        private set
    /** Home's scroll position and each row's, kept while pages come and go. */
    val homeList = LazyListState()
    private val rowStates = mutableMapOf<String, LazyListState>()

    fun rowState(key: String) = rowStates.getOrPut(key) { LazyListState() }

    /** True while browsing on top of feeds that keep playing. */
    var browsing by mutableStateOf(false)

    /** Every content's channel list, for switching a tile's feed later. */
    private val channelsByContent = mutableMapOf<Long, List<Channel>>()
    private val sessionRefs = mutableMapOf<Long, SessionRef>()
    /** Session titles by content ID, for the viewer's header and its feed sheet. */
    private val titles = mutableMapOf<Long, String>()
    /** Session artwork by content ID, for the Continue watching card. */
    private val images = mutableMapOf<Long, String?>()

    /**
     * The last few sessions watched, newest first, each with where it left off. From storage, so
     * they survive a crash or a force-close. Shown on home as Continue watching. Before 0.16 only
     * one was kept; that one seeds the list.
     */
    val recents = mutableStateListOf<SavedWatch>().apply {
        addAll(
            prefs.getString(PREF_RECENTS, null)?.let(SavedWatch::listFromJson)
                ?: listOfNotNull(SavedWatch.fromJson(prefs.getString(PREF_SAVED_WATCH, null)))
        )
    }
    /** A resume in progress: applied once its feeds are on screen. */
    private var pendingResume: SavedWatch? = null
    private var saveTicks = 0

    /** The title of what the leading tile is playing. */
    val playingTitle get() = leaderTile()?.let { titles[it.contentId] }.orEmpty()

    // Viewer.

    val tiles = mutableStateListOf<Tile>()
    var layoutMode by mutableStateOf(LayoutMode.FEATURED)
        private set
    /** Bumped to ask the canvas to lay the tiles out again. */
    var layoutRequest by mutableIntStateOf(0)
        private set
    private var layoutDone = 0
    private var topZ = 0f
    private var nextTileId = 0
    /** The tile whose menu is open, if any. */
    var tileMenu by mutableStateOf<Tile?>(null)
        private set
    /** The feed in the big slot of the main + strip layout; double-tap changes it. */
    // State, so what depends on which feed is up top in laptop mode (the way back) redraws.
    private var bigTileId by mutableStateOf<Int?>(null)
    /** After a swap, the tile offering its audio for a few seconds. */
    var audioHint by mutableStateOf<Tile?>(null)
        private set
    /** Bumped on each new offer, to restart its fade timer. */
    var audioHintCount by mutableIntStateOf(0)
        private set
    var mapSheetOpen by mutableStateOf(false)
    var layoutSheetOpen by mutableStateOf(false)
    /**
     * Moving and resizing tiles (and the map) only while unlocked from the top bar, so a stray
     * touch while watching can't knock the layout around.
     */
    var arranging by mutableStateOf(false)
        private set
    /** While arranging: magnetic edges and no overlaps unless pushed hard (Snap), or Free. */
    var snapMode by mutableStateOf(prefs.getBoolean(PREF_SNAP, true))
        private set
    /** True once feeds have been moved by hand (or put back from a saved session). */
    private var handPlaced = false
    /** What a fresh stream starts in; Save layout makes it the custom one. */
    private var defaultLayoutMode =
        prefs.getString(PREF_DEFAULT_LAYOUT, null)?.let { runCatching { LayoutMode.valueOf(it) }.getOrNull() } ?: LayoutMode.FEATURED
    /** A resumed session's feed boxes by channel, applied at its first layout. */
    private var resumeLayout: Pair<Map<Int, FloatArray>, SavedLayout>? = null
    /** Every feed silenced at once; the audio choice is kept for when it's turned back on. */
    var mutedAll by mutableStateOf(false)
        private set
    /** The feed the next car tapped on the map replaces, after it was tapped. */
    var targetTile by mutableStateOf<Tile?>(null)
        private set
    /** Bumped on each new target, to restart its glow timer. */
    var targetCount by mutableIntStateOf(0)
        private set

    fun toggleArranging() {
        arranging = !arranging
        message = if (arranging) {
            if (snapMode) "Drag to move; edges snap. Push hard to overlap. Pinch or use the corner to resize. Tap the lock when done."
            else "Drag to move, pinch or use the corner to resize. Tap the lock when done."
        } else null
        if (!arranging) storeMapPanel()
        showChrome()
    }

    // Shared playback controls, driven by the leading tile.
    var chromeVisible by mutableStateOf(true)
    /** Bumped on every interaction with the viewer; the chrome hides a few seconds after. */
    var chromeTouch by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(true)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var isLive by mutableStateOf(false)
        private set

    // Track map, from F1's timing archive.
    var timing by mutableStateOf<SessionTiming?>(null)
        private set
    var mapStatus by mutableStateOf<String?>(null)
        private set
    /** Edge by default; the last style picked is remembered. */
    var mapMode by mutableStateOf(savedMapMode())
        private set

    private fun savedMapMode() =
        prefs.getString(PREF_MAP_MODE, null)?.let { runCatching { MapMode.valueOf(it) }.getOrNull() } ?: MapMode.EDGE

    /** Videos that aren't sessions (press conferences, shows, highlights): they start with the map off. */
    private val notSessions = mutableSetOf<Long>()
    val mapPanel = MapPanel()
    /** The track map's window in laptop mode, below the hinge; placed and remembered on its own. */
    val laptopMapPanel = MapPanel()
    /** The map window on screen now. */
    val activeMapPanel get() = if (laptopActive) laptopMapPanel else mapPanel
    var mapOpacity by mutableFloatStateOf(0.9f)
        private set
    /** Keeps the map still while the feeds under it are arranged. */
    var mapLocked by mutableStateOf(prefs.getBoolean(PREF_MAP_LOCKED, false))
        private set
    /** Feeds make room for the track map instead of lying under it. */
    var mapReserve by mutableStateOf(prefs.getBoolean(PREF_MAP_RESERVE, false))
        private set
    /**
     * Correction between the picture and the map, in ms. F1 stamps video a few seconds later
     * than timing (about -7 s on the 2026 Japanese GP replay). The last value set is remembered.
     */
    var mapOffsetMs by mutableLongStateOf(prefs.getLong(PREF_MAP_OFFSET, DEFAULT_MAP_OFFSET_MS))
        private set
    /** True when the stream has no clock and the map's time is a guess the user lines up. */
    var mapClockEstimated by mutableStateOf(false)
        private set
    private var timingContentId: Long? = null

    /** Play sessions by content ID. Every tile of a content shares one. */
    private val sessions = mutableMapOf<Long, MultiPlay>()
    /** Contents whose multiview session F1 refused; their feeds use one session each. */
    private val singleSessionOnly = mutableSetOf<Long>()

    // Sign-in.

    fun restore() {
        val token = TokenStore.load(context)
        if (token == null) auth = AuthState.SIGNED_OUT else signIn(token)
        loadHome()
    }

    fun startLogin() = openLogin()

    fun onLoginFinished() {
        TokenStore.load(context)?.let(::signIn)
    }

    fun usePastedToken() {
        val token = pastedToken.trim()
        if (token.isEmpty()) return
        TokenStore.save(context, token)
        signIn(token)
    }

    private fun signIn(token: String) {
        scope.launch {
            busy = true
            try {
                api.platform = settings.platform
                api.login(token)
                tier = api.tier
                planPromptOpen = !hasVideoPlan
                auth = AuthState.SIGNED_IN
                message = null
                loadHome()
            } catch (e: Exception) {
                auth = AuthState.SIGNED_OUT
                message = "Couldn't sign in: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    /**
     * Whether the signed-in account's F1 TV plan includes video. A free account (REG) signs in
     * fine but gets nothing to play. In the US, Premium comes with Apple TV and reaches the F1 TV
     * account once the Apple account is linked to it; until then it's REG.
     */
    val hasVideoPlan get() = tier.isNotEmpty() && tier !in NO_VIDEO_TIERS
    /** The last crash's report, waiting for the person to send it or not. */
    var crashReport by mutableStateOf(CrashReports.pending(context))
        private set

    fun closeCrashReport() {
        crashReport = null
        CrashReports.clear(context)
    }

    /** Explains an account without video, once at sign-in and again if a stream is refused. */
    var planPromptOpen by mutableStateOf(false)

    fun closePlanPrompt(signOutToo: Boolean) {
        planPromptOpen = false
        if (signOutToo) signOut()
    }

    fun signOut() {
        stopAll()
        TokenStore.clear(context)
        LoginCookie.clear()
        api = F1TvApi(userAgent, chromeMajor)
        auth = AuthState.SIGNED_OUT
        tier = ""
        backStack.clear()
        backStack.add(Screen.Home)
    }

    fun choosePlatform(value: String) {
        if (value == settings.platform) return
        settings.updatePlatform(value)
        // Tokens and location are fetched per platform, so sign in again under the new one.
        TokenStore.load(context)?.let(::signIn)
    }

    // Browse.

    fun open(screen: Screen) {
        backStack.add(screen)
        when (screen) {
            is Screen.Weekend -> loadWeekend(screen.weekend)
            is Screen.Collection -> loadCollection(screen.pageId)
            Screen.Archive -> loadArchive()
            else -> Unit
        }
    }

    /** Returns false when there's nowhere further back to go. */
    fun back(): Boolean = when {
        picker != null -> { picker = null; true }
        backStack.size > 1 -> { backStack.removeAt(backStack.lastIndex); true }
        browsing && tiles.isNotEmpty() -> { browsing = false; true }
        else -> false
    }

    /** The weekend for the hero: the one in progress, else the next, else the last. */
    fun featuredWeekend(now: Long = System.currentTimeMillis()): WeekendInfo? =
        weekends.firstOrNull { it.isCurrent(now) }
            ?: weekends.firstOrNull { it.startMs > now }
            ?: weekends.lastOrNull()

    /** Finished and running weekends, newest first. */
    fun pastWeekends(now: Long = System.currentTimeMillis()) =
        weekends.filter { it.startMs <= now }.sortedByDescending { it.startMs }

    /** The latest weekend that has sessions to watch: this one if it's under way, else the last. */
    fun latestWeekend(now: Long = System.currentTimeMillis()) = pastWeekends(now).firstOrNull()

    fun loadHome() {
        scope.launch {
            try {
                api.prepare()
                val calendar = async { api.calendar(season) }
                val live = async { runCatching { api.liveNow() }.getOrDefault(emptyList()) }
                // Each list is swapped in one go, after both answers: home loads twice at start-up
                // (once on opening, again after sign-in), and clearing before the wait let both
                // loads fill the list, every weekend twice.
                val (newWeekends, newLive) = calendar.await() to live.await()
                weekends.clear()
                weekends.addAll(newWeekends)
                liveNow.clear()
                liveNow.addAll(newLive)
                homeError = null
                latestWeekend()?.let(::loadWeekend)
                featuredWeekend()?.takeIf { it != latestWeekend() }?.let(::loadWeekend)
                launch { runCatching { api.home() }.onSuccess { homePage = it } }
                if (lastYearRaces.isEmpty()) launch { runCatching { api.raceReplays(season - 1) }.onSuccess { lastYearRaces.clear(); lastYearRaces.addAll(it) } }
            } catch (e: Exception) {
                homeError = "Couldn't load F1 TV: ${e.message}"
            }
        }
    }

    /** Fetched fresh each time, so sessions flip from upcoming to live to replay as the weekend runs. */
    fun loadWeekend(weekend: WeekendInfo) {
        scope.launch {
            runCatching { api.weekend(weekend) }
                .onSuccess {
                    weekendContent[weekend.meetingKey] = it
                    syncReminders()
                }
        }
    }

    // F1 TV's catalog beyond the weekends.

    /** F1 TV's home page: what it features now, and its rows of shows, documentaries and archive races. */
    var homePage by mutableStateOf<HomePage?>(null)
        private set
    /** Show and documentary pages, by F1 TV page ID, fetched when opened. */
    val collections = mutableStateMapOf<Int, CollectionPage>()
    /** Popular races from every season, fetched when the archive page opens. */
    var archive by mutableStateOf<List<CatalogItem>?>(null)
        private set
    /** Last season's race replays, for "last year's race" before a weekend. */
    private val lastYearRaces = mutableStateListOf<CatalogItem>()

    fun loadCollection(pageId: Int) {
        scope.launch { runCatching { api.collection(pageId) }.onSuccess { collections[pageId] = it } }
    }

    fun loadArchive() {
        if (archive != null) return
        scope.launch { runCatching { api.archiveRaces() }.onSuccess { archive = it } }
    }

    /** Plays a catalog item; a show or documentary series opens its page of episodes instead. */
    fun watch(item: CatalogItem) {
        item.pageId?.let { open(Screen.Collection(it, item.title)); return }
        // Sessions are titled by their weekend in the viewer ("Mexico City Grand Prix · Race").
        val session = weekendContent.values.any { c -> c.sessions.any { it.contentId == item.contentId } }
        // Live events carry their weekend's name as their subtitle.
        val meeting = item.meeting.ifEmpty { if (item.live) item.subtitle.orEmpty() else "" }
        openPicker(item.contentId, if ((session || item.live) && meeting.isNotEmpty()) "$meeting · ${item.title}" else item.title, item.image)
    }

    /** This season's weekend a video belongs to, for its Weekend button. */
    fun weekendOf(item: CatalogItem): WeekendInfo? =
        weekends.firstOrNull { it.name == item.meeting && (item.year == 0 || item.year == season) }

    // Watch history, for the suggestions.

    /** What's been watched, by content ID. Kept to the most recent few hundred. */
    val history = mutableStateMapOf<Long, Seen>().apply { putAll(readHistory()) }

    private fun readHistory(): Map<Long, Seen> = runCatching {
        val json = JSONObject(prefs.getString(PREF_HISTORY, null) ?: return emptyMap())
        json.keys().asSequence().associate { key ->
            val a = json.getJSONArray(key)
            key.toLong() to Seen(a.getLong(0), a.getLong(1), a.getBoolean(2))
        }
    }.getOrDefault(emptyMap())

    /**
     * Notes a few more seconds of [contentId] watched. A session counts as watched once the
     * picture is past its chequered flag, live or replay: the stream runs on well after it (the
     * podium, interviews), and few sit through that. Anything without timing counts at 90%.
     */
    private fun noteSeen(contentId: Long, positionMs: Long, durationMs: Long, live: Boolean) {
        val now = System.currentTimeMillis()
        val before = history[contentId]
        // Saves come every few seconds while watching; a longer gap was time away.
        val gap = before?.let { now - it.at }?.takeIf { it in 0..15_000 } ?: 0
        val watched = (before?.watchedMs ?: 0) + gap
        val flag = timing?.finishedUtc?.takeIf { timingContentId == contentId }
        val pastFlag = flag != null && (mapTimeMs() ?: 0L) >= flag
        val finished = before?.finished == true || pastFlag ||
            (!live && durationMs > 0 && positionMs >= durationMs * 9 / 10)
        history[contentId] = Seen(now, watched, finished)
        if (history.size > MAX_HISTORY) history.entries.sortedBy { it.value.at }.take(history.size - MAX_HISTORY).forEach { history.remove(it.key) }
        val json = JSONObject()
        history.forEach { (id, seen) -> json.put(id.toString(), JSONArray(listOf(seen.at, seen.watchedMs, seen.finished))) }
        prefs.edit().putString(PREF_HISTORY, json.toString()).apply()
    }

    /**
     * Home's carousel: the best things to watch next, in order. What's live; a session left
     * part-way; after a session you watched, what follows it (the post-race show, the press
     * conference, highlights), or the session itself if you haven't; what F1 TV features now;
     * before the next weekend, its build-up and last year's race there, then its schedule; and
     * one race from the archive. Anything watched to the end is left out.
     */
    fun suggestions(now: Long = System.currentTimeMillis()): List<Pick> {
        val picks = mutableListOf<Pick>()
        val used = mutableSetOf<Long>()
        fun fresh(item: CatalogItem) = !item.upcoming && history[item.contentId]?.finished != true && item.pageId == null
        fun add(reason: String, item: CatalogItem) {
            if (item.contentId > 0 && fresh(item) && used.add(item.contentId)) picks += Pick(reason, item = item)
        }

        liveNow.forEach { add("Live now", it) }

        recents.firstOrNull { r -> !r.live && history[r.contentId]?.let { now - it.at < 3 * DAY_MS } == true }?.let {
            if (used.add(it.contentId)) picks += Pick("Pick up where you left off", resume = it)
        }

        val latest = latestWeekend(now)
        val content = latest?.let { weekendContent[it.meetingKey] }
        val last = content?.sessions?.filter { !it.upcoming && !it.live }?.maxByOrNull { it.startMs }
        if (latest != null && content != null && last != null) {
            val stage = last.title.lowercase()
            if (history[last.contentId] == null) {
                add(if (now - last.startMs < 2 * DAY_MS) "In case you missed it" else "Catch up", last)
            } else {
                // What follows the session you watched: its show, its press conference, its highlights.
                fun rank(v: CatalogItem): Int {
                    val t = "${v.title} ${v.genre}".lowercase()
                    return when {
                        "post-$stage" in t && "show" in t -> 0
                        "post-$stage" in t -> 1
                        "$stage in 30" in t || "$stage highlights" in t -> 2
                        else -> -1
                    }
                }
                val after = if ("qualifying" in stage) "After $stage" else "After the $stage"
                content.videos.filter { rank(it) >= 0 }.sortedBy(::rank).forEach { add(after, it) }
            }
        }

        homePage?.featured?.forEach { add("Featured on F1 TV", it) }

        // The next weekend: during one, its next session; before one, its build-up.
        val next = weekends.firstOrNull { it.isCurrent(now) && nextSession(it) != null }
            ?: weekends.firstOrNull { it.startMs > now }
        if (next != null) {
            if (next.startMs - now < 7 * DAY_MS) {
                weekendContent[next.meetingKey]?.videos?.forEach { add("Before the ${next.name}", it) }
                lastYearRaces.firstOrNull { it.meeting == next.name }?.let { add("Last year in ${next.place}", it) }
            }
            val session = nextSession(next)
            picks += Pick(
                when {
                    session != null && next.isCurrent(now) -> "Next: ${session.title} · ${session.subtitle}"
                    else -> "Next race weekend · ${DAY.format(java.util.Date(next.startMs))} to ${DAY.format(java.util.Date(next.endMs))}"
                },
                weekend = next,
            )
        }

        // One race from the archive, a different one each day.
        homePage?.rows?.firstOrNull { it.tag == CatalogRow.ARCHIVE }?.items?.filter(::fresh)?.takeIf { it.isNotEmpty() }?.let { races ->
            add("From the archive", races[(now / DAY_MS % races.size).toInt()])
        }
        // The next weekend's schedule always makes it, even after a long build-up.
        val schedule = picks.firstOrNull { it.weekend != null }
        if (schedule != null && picks.indexOf(schedule) >= MAX_PICKS) return picks.take(MAX_PICKS - 1) + schedule
        return picks.take(MAX_PICKS)
    }

    // Past seasons, for the calendar.

    /** Seasons F1 TV has weekends for (2018 on) or archive videos for (1981 on), newest first. */
    val archiveSeasons = (season downTo FIRST_ARCHIVE_SEASON).toList()
    /** The season the calendar shows. */
    var calendarSeason by mutableIntStateOf(season)
        private set
    /** Past seasons' weekends (2018 on), fetched when first shown. */
    val pastWeekends = mutableStateMapOf<Int, List<WeekendInfo>>()
    /** Seasons before F1 TV's weekends: their replays and other videos. */
    val classicVideos = mutableStateMapOf<Int, List<CatalogItem>>()

    fun chooseCalendarSeason(year: Int) {
        calendarSeason = year
        when {
            year == season -> Unit
            year >= FIRST_WEEKEND_SEASON && year !in pastWeekends -> scope.launch {
                runCatching { api.calendar(year) }.onSuccess { pastWeekends[year] = it }
            }
            year < FIRST_WEEKEND_SEASON && year !in classicVideos -> scope.launch {
                runCatching { api.seasonVideos(year) }.onSuccess { classicVideos[year] = it }
            }
        }
    }

    // Session reminders.

    var remindersOn by mutableStateOf(Reminders.enabled(context))
        private set
    var remindersMainOnly by mutableStateOf(Reminders.mainOnly(context))
        private set

    fun chooseReminders(on: Boolean) {
        remindersOn = on
        Reminders.setEnabled(context, on)
        syncReminders()
    }

    fun chooseRemindersMainOnly(only: Boolean) {
        remindersMainOnly = only
        Reminders.setMainOnly(context, only)
        syncReminders()
    }

    /** Sets reminders for every upcoming session the app knows the time of (the weekends loaded). */
    private fun syncReminders() {
        val now = System.currentTimeMillis()
        val sessions = weekends.filter { !it.isDone(now) }.flatMap { w ->
            weekendContent[w.meetingKey]?.sessions.orEmpty()
                .filter { it.startMs > now }
                .map { Reminders.Session(it.contentId, w.name, it.title, it.startMs) }
        }
        Reminders.sync(context, sessions)
    }

    /** The next Formula 1 session of the featured weekend that hasn't started, if any. */
    fun nextSession(weekend: WeekendInfo): CatalogItem? =
        weekendContent[weekend.meetingKey]?.sessions?.firstOrNull { it.upcoming }

    // Choosing feeds.

    /** Opens the feed sheet for a session; feeds already on screen show as picked. */
    fun openPicker(contentId: Long, title: String, image: String? = null) {
        if (auth != AuthState.SIGNED_IN) {
            message = "Sign in to watch."
            return
        }
        val sheet = FeedPicker(contentId, title)
        titles[contentId] = title
        if (image != null) images[contentId] = image
        picker = sheet
        scope.launch {
            try {
                val list = channelsOf(contentId)
                sheet.channels = list
                val playingThis = tiles.any { it.contentId == contentId }
                if (list.size == 1 && !playingThis) {
                    // Highlights and shows have one feed: no choice to make, just play it.
                    sheet.selected.add(list.first())
                    watchPicked()
                } else if (!playingThis) {
                    // Nothing playing yet: start with the main broadcast ticked.
                    sheet.selected.add(list.firstOrNull { it.isDefault } ?: list.first())
                }
            } catch (e: Exception) {
                sheet.error = "Couldn't load this session's feeds: ${e.message}"
            }
        }
    }

    /** A session's feeds, fetched once. */
    private suspend fun channelsOf(contentId: Long): List<Channel> = channelsByContent[contentId] ?: run {
        val video = api.contentVideo(contentId)
        video.session?.let { sessionRefs[contentId] = it }
        if (!video.isSession) notSessions += contentId
        video.channels.ifEmpty { listOf(Channel("Main feed", null, isDefault = true)) }
            .also { channelsByContent[contentId] = it }
    }

    // Saving and resuming.

    /**
     * Records the leading session as it stands. Called every few seconds while watching, when
     * the app goes to the background and when the viewer closes. A replay watched to its last
     * minute is done, so the record is dropped.
     */
    fun saveWatch() {
        val leader = leaderTile() ?: return
        val player = leader.feed.player
        val live = player.isCurrentMediaItemLive
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val position = player.currentPosition
        noteSeen(leader.contentId, position, duration, live)
        if (!live && duration > 0 && position > duration - 60_000) {
            clearSavedWatch()
            return
        }
        val session = tiles.filter { it.contentId == leader.contentId }
        val big = session.firstOrNull { it.id == bigTileId } ?: session.first()
        val layout = if (canvasW > 0f) SavedLayout.of(session.map { Slot(it.x, it.y, it.w) }, canvasW, canvasH) else null
        val saved = SavedWatch(
            contentId = leader.contentId,
            title = titles[leader.contentId] ?: "",
            image = images[leader.contentId],
            positionMs = position,
            durationMs = duration,
            live = live,
            channels = session.map { it.channel.key() },
            bigChannel = big.channel.key(),
            audioChannel = (session.firstOrNull { it.audible } ?: big).channel.key(),
            layoutMode = layoutMode.name,
            mapMode = mapMode.name,
            layout = layout,
        )
        recents.removeAll { it.contentId == saved.contentId }
        recents.add(0, saved)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
        storeRecents()
    }

    private fun clearSavedWatch() {
        val leader = leaderTile() ?: return
        if (recents.removeAll { it.contentId == leader.contentId }) storeRecents()
    }

    private fun storeRecents() {
        prefs.edit().putString(PREF_RECENTS, SavedWatch.listToJson(recents)).remove(PREF_SAVED_WATCH).apply()
    }

    /** Puts a saved session back: same feeds, layout, big tile, audio and moment. */
    fun resumeWatching(saved: SavedWatch) {
        if (auth != AuthState.SIGNED_IN) return
        // Another session on screen gives way to the one being resumed.
        if (tiles.isNotEmpty()) stopAll()
        task("Picking up where you left off") {
            val list = channelsOf(saved.contentId)
            val feeds = saved.channels.mapNotNull { key -> list.firstOrNull { it.key() == key } }
            if (feeds.isEmpty()) throw IOException("those feeds aren't available any more")
            titles[saved.contentId] = saved.title
            images[saved.contentId] = saved.image
            runCatching { layoutMode = LayoutMode.valueOf(saved.layoutMode) }
            runCatching { mapMode = MapMode.valueOf(saved.mapMode) }
            // Feeds come back where they were, if the screen is the same shape.
            resumeLayout = saved.layout?.let { layout -> saved.channels.zip(layout.boxes).toMap() to layout }
            pendingResume = saved
            browsing = false
            addFeeds(saved.contentId, feeds)
        }
    }

    /** Opens the F1 TV account site in the browser. */
    fun openAccountPage() {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(ACCOUNT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens the feed sheet for whatever is playing, to add another of its feeds. */
    fun openPickerForPlaying() {
        val leader = leaderTile() ?: return
        openPicker(leader.contentId, titles[leader.contentId] ?: "Add a feed")
    }

    fun closePicker() {
        picker = null
    }

    fun isPlaying(contentId: Long, channel: Channel) = tiles.any { it.contentId == contentId && it.channel == channel }

    /** Feeds there would be on screen if the sheet's picks were applied. */
    fun pickedCount(sheet: FeedPicker) = tiles.size - sheet.removing.size + sheet.selected.size

    /**
     * Feeds already on screen start out picked; tapping one unpicks it to make room. New feeds
     * can be picked until the screen would hold four.
     */
    fun togglePick(channel: Channel) {
        val sheet = picker ?: return
        val full = pickedCount(sheet) >= MAX_FEEDS
        sheet.note = null
        when {
            isPlaying(sheet.contentId, channel) && channel in sheet.removing ->
                if (full) sheet.note = FULL_NOTE else sheet.removing.remove(channel)
            isPlaying(sheet.contentId, channel) -> sheet.removing.add(channel)
            channel in sheet.selected -> sheet.selected.remove(channel)
            full -> sheet.note = FULL_NOTE
            else -> sheet.selected.add(channel)
        }
    }

    /**
     * Applies the sheet. A feed unpicked and another picked in its place take over the same tile,
     * so the layout and the audio stay put; leftovers are removed or added.
     */
    fun watchPicked() {
        val sheet = picker ?: return
        if (sheet.selected.isEmpty() && sheet.removing.isEmpty()) return
        // Silent tiles are swapped first, so the audio stays on the main broadcast.
        val freed = tiles.filter { it.contentId == sheet.contentId && it.channel in sheet.removing }
            .sortedBy { it.audible }.toMutableList()
        val adds = sheet.selected.toMutableList()
        while (freed.isNotEmpty() && adds.isNotEmpty()) switchFeed(freed.removeAt(0), adds.removeAt(0))
        freed.forEach(::remove)
        if (adds.isNotEmpty()) addFeeds(sheet.contentId, adds)
        picker = null
        browsing = false
    }

    /** Room for more feeds; past four the phone's decoders start to freeze. */
    val roomForFeeds get() = MAX_FEEDS - tiles.size

    private fun addFeeds(contentId: Long, requested: List<Channel>) {
        if (roomForFeeds <= 0) {
            message = "4 feeds is the most this phone plays smoothly. Remove one or switch a feed instead."
            return
        }
        val picks = requested.take(roomForFeeds)
        val dropped = requested.size - picks.size
        task("Starting feeds") {
            val failures = mutableListOf<String>()
            val streams = picks.mapNotNull { channel ->
                try {
                    channel to streamFor(contentId, channel, failures)
                } catch (e: Exception) {
                    failures += "${channel.title}: ${e.message}"
                    null
                }
            }
            if (streams.isEmpty()) {
                if (!hasVideoPlan) {
                    planPromptOpen = true
                    message = null
                } else message = "No feed would start.\n" + failures.joinToString("\n")
                return@task
            }

            // New feeds join at the moment already playing, rather than starting at zero and
            // waiting for the sync loop to jump them forward.
            val joinAt = leaderTile()?.takeIf { it.contentId == contentId && !it.feed.player.isCurrentMediaItemLive }
                ?.feed?.player?.currentPosition
            val first = tiles.isEmpty()
            // A press conference or a show has no cars to follow: its map starts off, without
            // touching the style saved for sessions.
            if (first && pendingResume?.contentId != contentId) mapMode = if (contentId in notSessions) MapMode.OFF else savedMapMode()
            // A feed added while one fills the screen brings the whole layout back.
            focusTileId = null
            streams.forEach { (channel, stream) ->
                val feed = newFeed(stream)
                joinAt?.let(feed.player::seekTo)
                val tile = Tile(nextTileId++, contentId, channel, feed)
                tiles.add(tile)
                raise(tile)
            }
            // A fresh start uses the layout picked as default (Save layout makes it the custom one).
            if (first && pendingResume?.contentId != contentId) layoutMode = defaultLayoutMode
            // Keep the audio where it was; new feeds start muted. The default feed gets it first.
            setAudio(tiles.firstOrNull { it.audible } ?: tiles.firstOrNull { it.channel.isDefault } ?: tiles.first())
            pendingResume?.takeIf { it.contentId == contentId }?.let { saved ->
                pendingResume = null
                tiles.firstOrNull { it.channel.key() == saved.audioChannel }?.let(::setAudio)
                tiles.firstOrNull { it.channel.key() == saved.bigChannel }?.let { bigTileId = it.id }
                // A live session comes back at the live edge; a replay at the saved moment.
                if (!saved.live) tiles.forEach { it.feed.player.seekTo(saved.positionMs) }
            }
            // A feed more means a fresh fit of the layout in use; the map stays where it is.
            handPlaced = false
            layoutRequest++
            showChrome()
            if (dropped > 0) failures += "Left out $dropped feed(s): 4 is the most this phone plays smoothly"
            message = if (failures.isEmpty()) null else "Some feeds had problems:\n" + failures.joinToString("\n")
            if (timingContentId != leaderTile()?.contentId) loadTiming(leaderTile()!!.contentId)
        }
    }

    private fun newFeed(stream: PlayInfo, startMs: Long = C.TIME_UNSET) = FeedPlayer(
        context, stream,
        ascendon = api.ascendon.orEmpty(),
        entitlement = api.entitlement.orEmpty(),
        userAgent = userAgent,
        forceL3 = settings.softwareDrm,
        subtitles = settings.subtitles,
        commentary = settings.commentary,
        startMs = startMs,
    )

    /**
     * A second PLAY request can end the first session, so every feed of a content comes from one
     * multiview session, as in F1's web player and eXViewer. Falls back to one session per feed
     * if F1 refuses the multiview session.
     */
    private suspend fun streamFor(contentId: Long, channel: Channel, failures: MutableList<String>): PlayInfo {
        if (contentId !in singleSessionOnly && contentId !in sessions) {
            val list = channelsByContent[contentId].orEmpty()
            val default = list.firstOrNull { it.isDefault } ?: list.firstOrNull()
            try {
                sessions[contentId] = api.multiPlay(contentId, default?.channelId)
            } catch (e: Exception) {
                singleSessionOnly += contentId
                failures += "Multiview session refused, using one session per feed: ${e.message}"
            }
        }
        val session = sessions[contentId] ?: return api.contentPlay(contentId, channel.channelId)

        val url = if (channel.channelId == null) session.feeds.values.firstOrNull()
        else session.feeds[channel.channelId]
        return session.stream(url ?: throw IOException("F1 returned no stream for this channel"))
    }

    // Track map.

    private fun loadTiming(contentId: Long) {
        timingContentId = contentId
        timing = null
        val ref = sessionRefs[contentId]
        val token = api.ascendon
        if (ref == null || token == null) {
            mapStatus = "Map unavailable: F1 TV didn't say which session this is"
            return
        }
        mapStatus = "Loading the track map..."
        liveFeed?.cancel()
        liveFeed = scope.launch {
            // Live or replay is only known once the player has the stream.
            if (isLiveStream()) {
                followLive(contentId, ref, token)
                return@launch
            }
            try {
                // When the picture was broadcast: identifies the session if the title can't.
                val watched = leaderTile()?.feed?.wallClockMs()
                val loaded = LiveTimingArchive(token, userAgent).load(ref, watched)
                if (timingContentId != contentId) return@launch
                rememberOutline(loaded)
                showTiming(loaded)
                mapStatus = if (loaded.outline.isEmpty()) "Couldn't trace the track; showing cars only" else null
            } catch (e: Exception) {
                if (timingContentId == contentId) mapStatus = "Map unavailable: ${e.message}"
            }
        }
    }

    /** The map's job for the session on screen: an archive load, or a live feed that runs on. */
    private var liveFeed: kotlinx.coroutines.Job? = null

    private suspend fun isLiveStream(): Boolean {
        repeat(60) {
            val player = leaderTile()?.feed?.player ?: return false
            if (player.playbackState == Player.STATE_READY) return player.isCurrentMediaItemLive
            kotlinx.coroutines.delay(250)
        }
        return false
    }

    /** Follows a live session through F1's live timing until the viewer closes or moves on. */
    private suspend fun followLive(contentId: Long, ref: SessionRef, token: String) {
        val key = ref.circuitKey
        val known = key?.let { readOutline(it) }
        val feed = LiveTimingFeed(token, userAgent, ref, known)
        var kept = known != null
        try {
            feed.run(
                onTiming = { live ->
                    if (timingContentId == contentId) {
                        rememberStartLine(live)
                        showTiming(live)
                        offerVote(contentId, live)
                        // A track traced live is kept, for the replay and the next session there.
                        if (!kept && key != null) feed.newOutline?.let { writeOutline(key, it); kept = true }
                    }
                },
                onStatus = { if (timingContentId == contentId) mapStatus = it },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (timingContentId == contentId) mapStatus = "Live map unavailable: ${e.message}"
        }
    }

    /** Puts a session's timing on screen; the first time, the map gets its place. */
    private fun showTiming(loaded: SessionTiming) {
        val first = timing == null
        timing = loaded
        if (!first) return
        // The map lies over the feeds; they only move for it when asked to keep it clear (and
        // nobody has arranged them by hand).
        if (mapMode == MapMode.TRACK && mapReserve && !handPlaced) layoutRequest++
        else if (canvasW > 0f) placeMapIfNeeded(canvasW, canvasH)
    }

    // Circuits traced from replays, kept per circuit so a live session there has its map at once.

    private fun outlineFile(circuitKey: Int) = java.io.File(context.filesDir, "outlines/$circuitKey.json")

    private fun readOutline(circuitKey: Int): SavedOutline? =
        runCatching { SavedOutline.fromJson(outlineFile(circuitKey).readText()) }.getOrNull()

    private fun writeOutline(circuitKey: Int, outline: SavedOutline) {
        runCatching {
            outlineFile(circuitKey).apply { parentFile?.mkdirs() }.writeText(outline.toJson())
        }
    }

    private fun rememberOutline(loaded: SessionTiming) {
        val key = loaded.circuitKey ?: return
        if (loaded.hasStartFinish && loaded.outline.size >= 8) writeOutline(key, SavedOutline(loaded.outline, true))
    }

    /**
     * The session time (UTC ms) of the leading tile's picture. Uses the stream's own clock when it
     * has one; otherwise guesses from the timing feed's start and lets the user line it up.
     */
    fun mapTimeMs(): Long? {
        val leader = leaderTile()?.feed ?: return null
        val clock = leader.wallClockMs()
        mapClockEstimated = clock == null
        val base = clock ?: ((timing?.startUtcMs ?: return null) + leader.player.currentPosition)
        return base + mapOffsetMs
    }

    /**
     * Where the chequered flags go on the scrubber, as positions in the leading feed: lights out
     * (or the session going green) and the chequered flag. The reverse of [mapTimeMs].
     */
    fun flagPositions(): List<Long> {
        val t = timing ?: return emptyList()
        val leader = leaderTile()?.feed ?: return emptyList()
        if (leader.player.isCurrentMediaItemLive) return emptyList()
        val zero = leader.windowStartMs() ?: t.startUtcMs
        return listOfNotNull(t.startedUtc, t.finishedUtc)
            .map { it - mapOffsetMs - zero }
            .filter { it in 0..durationMs }
    }

    /**
     * A start line seen in a race is kept for its circuit, so qualifying and practice there show
     * it too. Fractions of a lap from the finish line, so it carries between sessions.
     */
    private fun rememberStartLine(loaded: SessionTiming) {
        val key = loaded.circuitKey ?: return
        if (!loaded.hasStartFinish) return
        val found = loaded.startLine
        if (found != null) prefs.edit().putFloat(PREF_START_LINE + key, found).apply()
        else if (prefs.contains(PREF_START_LINE + key)) loaded.startLine = prefs.getFloat(PREF_START_LINE + key, 0f)
    }

    /**
     * Tapping a feed while there's a map marks it as the one the next car tapped replaces. It
     * glows for a few seconds; tapping it again lets it go.
     */
    fun targetForMap(tile: Tile) {
        if (timing == null || mapMode == MapMode.OFF || isMainTile(tile)) return
        targetTile = if (targetTile === tile) null else tile
        targetCount++
    }

    /**
     * The big feed of main + strip (and the saved layout), or the only feed, which cars picked on
     * the map leave alone: double-tap a small one to bring it up instead. Grid has no main feed.
     */
    fun isMainTile(tile: Tile): Boolean {
        if (tiles.size == 1) return true
        if (layoutMode == LayoutMode.GRID) return false
        val big = tiles.firstOrNull { it.id == bigTileId } ?: tiles.maxByOrNull { it.w }
        return tile === big
    }

    fun releaseTarget(tile: Tile) {
        if (targetTile === tile) targetTile = null
    }

    /**
     * Puts car [number]'s onboard on screen, always in place of a feed already there, so the
     * layout never moves. The feed tapped just before (glowing) goes first. Without one, the
     * least interesting unpinned onboard: a car in the pits or knocked out first, then one on an
     * out or cool-down lap, then a car on a flying lap; ties go to the tile picked longest ago.
     * Non-onboard feeds (F1 Live, data) and the tile with the audio are kept.
     */
    fun showOnboard(number: String) {
        val leader = leaderTile() ?: return
        val channel = channelsByContent[leader.contentId]?.firstOrNull { it.racingNumber?.toString() == number }
        if (channel == null) {
            message = "No onboard for car $number in this session"
            return
        }
        if (laptopActive) rememberBroadcast()
        tiles.firstOrNull { it.channel == channel }?.let {
            if (laptopActive) putOnTop(it) else raise(it)
            return
        }
        val now = mapTimeMs() ?: 0L
        // Laptop mode shows one feed: the onboard takes a feed waiting off screen (never the one
        // with the commentary) and comes up top; with only one feed, that one switches.
        if (laptopActive) {
            val spare = tiles.filter { it.id != bigTileId && !it.audible && !it.pinned }
                .minWithOrNull(compareBy<Tile>({ interest(it, now) }, { it.mapPickedAt }))
            val swap = spare ?: tiles.firstOrNull { it.id == bigTileId } ?: return
            swap.mapPickedAt = System.currentTimeMillis()
            switchFeed(swap, channel)
            putOnTop(swap)
            return
        }
        val target = targetTile?.takeIf { it in tiles }
            ?: tiles
                .filter { !it.audible && !it.pinned && !isMainTile(it) }
                .minWithOrNull(compareBy<Tile>({ interest(it, now) }, { it.mapPickedAt }))
        targetTile = null
        if (target == null) {
            message = if (tiles.size == 1) "Add a feed with +, then tap a car to put its onboard there."
            else if (tiles.none { it.pinned }) "Tap a small feed, then a car, to swap it for the car's onboard."
            else "Every other feed is pinned. Unpin one from its menu (long-press it), or tap a feed and then the car."
            return
        }
        target.mapPickedAt = System.currentTimeMillis()
        switchFeed(target, channel)
    }

    /** Lower is less worth keeping on screen. */
    private fun interest(tile: Tile, now: Long): Int {
        val number = tile.channel.racingNumber ?: return 3
        val state = timing?.states?.get(number.toString())?.at(now) ?: LapState.UNKNOWN
        return when {
            state == LapState.PIT || state == LapState.KNOCKED_OUT -> 0
            timing?.showsPushLaps == true && state == LapState.PUSH -> 2
            else -> 1
        }
    }

    // Tiles.

    fun channelsFor(tile: Tile): List<Channel> = channelsByContent[tile.contentId].orEmpty()

    fun openTileMenu(tile: Tile) {
        tileMenu = tile
    }

    fun closeTileMenu() {
        tileMenu = null
    }

    /** Puts another channel in the tile, keeping its place, size and audio. */
    fun switchFeed(tile: Tile, channel: Channel) {
        tileMenu = null
        if (channel == tile.channel) return
        // The feed up top in laptop mode switched from its own menu: offer the way back too.
        if (laptopActive && tile.id == bigTileId) rememberBroadcast()
        task("Switching to ${channel.title}") {
            val failures = mutableListOf<String>()
            val stream = streamFor(tile.contentId, channel, failures)
            val old = tile.feed
            // Start where the tile was (switching the leader from the start once pulled every
            // other feed back with it), allowing for the time a feed takes to start while playing,
            // and from the beginning of that video chunk: starting mid-chunk means decoding every
            // frame from the chunk's start first, measured at 3 to 4 s against under 1 s from its
            // start. The sync loop then brings it level in one short skip.
            val start = if (old.player.isCurrentMediaItemLive) C.TIME_UNSET else {
                val target = old.player.currentPosition + if (playing) FeedPlayer.typicalStartMs else 0L
                val chunk = FeedPlayer.chunkMs
                if (chunk > 0) target / chunk * chunk else target
            }
            val feed = newFeed(stream, start)
            feed.setAudible(tile.audible && !mutedAll)
            feed.player.playWhenReady = playing
            tile.feed = feed
            tile.channel = channel
            old.release()
            if (failures.isNotEmpty()) message = failures.joinToString("\n")
        }
    }

    /** Lays the feeds out afresh in [mode]. */
    fun chooseLayout(mode: LayoutMode) {
        mapGrabbed = false
        layoutMode = mode
        handPlaced = false
        layoutRequest++
        showChrome()
    }

    /** The saved arrangement for [n] feeds, if there is one for a screen this shape. */
    fun savedLayout(n: Int): SavedLayout? =
        SavedLayout.fromJson(prefs.getString(layoutKey(n), null))?.takeIf { canvasW <= 0f || it.fits(canvasW, canvasH) }

    /** Saved layouts are kept per way up: sideways ones carry an L (upright ones predate this). */
    private fun layoutKey(n: Int) = PREF_LAYOUT + n + if (canvasW > canvasH) "L" else ""

    /** Upright or sideways, in words, for messages. */
    val wayUp get() = if (canvasW > canvasH) "sideways" else "upright"

    /**
     * Makes the arrangement on screen the one every stream with this many feeds starts in, map
     * included. The built-in layouts stay as they were.
     */
    fun saveLayoutAsDefault() {
        if (tiles.isEmpty() || canvasW <= 0f) return
        val ordered = bigFirst()
        prefs.edit()
            .putString(layoutKey(ordered.size), SavedLayout.of(ordered.map { Slot(it.x, it.y, it.w) }, canvasW, canvasH).toJson())
            .putString(PREF_DEFAULT_LAYOUT, LayoutMode.CUSTOM.name)
            .apply()
        storeMapPanel()
        defaultLayoutMode = LayoutMode.CUSTOM
        layoutMode = LayoutMode.CUSTOM
        message = "Saved. Streams with ${ordered.size} feed${if (ordered.size == 1) "" else "s"} start like this when the phone is $wayUp."
    }

    fun chooseSnap(on: Boolean) {
        snapMode = on
        prefs.edit().putBoolean(PREF_SNAP, on).apply()
    }

    /** The big feed (double-tapped in, else the one with the audio) first, then the rest. */
    private fun bigFirst(): List<Tile> {
        val big = tiles.firstOrNull { it.id == bigTileId } ?: tiles.firstOrNull { it.audible } ?: tiles.firstOrNull()
        return listOfNotNull(big) + tiles.filter { it !== big }
    }

    /**
     * Lays out only when asked: feeds added or removed, a layout picked, the phone folded or
     * unfolded. Switching a feed (from the menu or a car on the map) never comes through here, so
     * the feed keeps its spot. The track map is never moved by a layout. Both maps lie over the
     * feeds, unless the track map is set to keep clear, when the feeds make room for it.
     */
    fun layoutIfRequested(width: Float, height: Float) {
        if (layoutDone == layoutRequest) return
        layoutDone = layoutRequest
        try {
            layOut(width, height)
        } finally {
            glideNextLayout = false
        }
    }

    private fun layOut(width: Float, height: Float) {
        val ordered = bigFirst()
        bigTileId = ordered.firstOrNull()?.id

        // Laptop mode: the main feed fills the screen above the hinge; the rest wait off screen,
        // still playing. Timing and the map take the half below.
        hinge?.takeIf { laptopActive }?.let { (top, bottom) ->
            val main = ordered.first()
            placeLaptopMap(width, height, bottom)
            settle(main, Layouts.place(LayoutMode.GRID, 1, width, top).first())
            raise(main)
            // The others slide off to the side, keeping their size, and slide back later.
            tiles.filter { it !== main }.forEach { settle(it, Slot(width + it.w, it.y, it.w)) }
            return
        }

        // One feed filling the screen: it gets the whole canvas, the rest wait off screen.
        // Unfolding to the big screen ends it.
        tiles.firstOrNull { it.id == focusTileId }?.takeIf { compactCanvas }?.let { focus ->
            focus.place(Layouts.place(LayoutMode.GRID, 1, width, height).first())
            raise(focus)
            tiles.filter { it !== focus }.forEach { it.place(Slot(width * 2, 0f, it.w)) }
            return
        }
        focusTileId = null

        // Coming back to a session puts its feeds back where they were.
        resumeLayout?.let { (boxes, layout) ->
            resumeLayout = null
            if (layout.fits(width, height) && ordered.all { it.channel.key() in boxes }) {
                ordered.forEach { tile ->
                    val b = boxes.getValue(tile.channel.key())
                    tile.place(Slot(b[0] * width, b[1] * height, b[2] * width))
                }
                handPlaced = true
                placeMapIfNeeded(width, height)
                return
            }
        }

        val custom = if (layoutMode == LayoutMode.CUSTOM) savedLayout(ordered.size)?.takeIf { it.fits(width, height) } else null
        val slots = when {
            custom != null -> custom.slots(width, height)
            mapMode == MapMode.TRACK && mapReserve && timing != null -> {
                val arrangement = Layouts.withMap(layoutMode, ordered.size, width, height)
                val gap = arrangement.map
                if (gap != null && minOf(gap.w, gap.h) >= minOf(width, height) * 0.22f) {
                    mapPanel.place(gap)
                    mapPanel.placed = true
                }
                arrangement.slots
            }
            // The edge ribbon lies over the feeds' outer edges, like the track map, so sessions
            // without a map (F2, F1 Academy) and maps still loading never leave a bare border.
            else -> Layouts.place(layoutMode, ordered.size, width, height)
        }
        slots.zip(ordered).forEach { (slot, tile) -> settle(tile, slot) }
        placeMapIfNeeded(width, height)
    }

    /**
     * The track map's first spot: where it was last left (or saved), else a square in the
     * bottom-right corner, over the feeds.
     */
    private fun placeMapIfNeeded(width: Float, height: Float) {
        if (mapPanel.placed) return
        mapPanel.restore(prefs.getString(PREF_MAP_PANEL, null), width, height)?.let { mapOpacity = it }
        if (mapPanel.placed) {
            keepMapOnScreen()
            return
        }
        val side = minOf(width, height) * 0.42f
        mapPanel.place(Area(width - side, height - side, side, side))
        mapPanel.placed = true
    }

    /**
     * The laptop map's first spot: where it was last left, else the right part of the half below
     * the hinge, kept clear of the screen's rounded corners (the timing tower takes the left).
     */
    private fun placeLaptopMap(width: Float, height: Float, hingeBottom: Float) {
        if (laptopMapPanel.placed) return
        laptopMapPanel.restore(prefs.getString(PREF_LAPTOP_MAP, null), width, height)
        if (laptopMapPanel.placed) {
            keepMapOnScreen()
            return
        }
        val d = context.resources.displayMetrics.density
        val gap = 12 * d
        val corner = 40 * d
        val left = width * LAPTOP_TOWER_SHARE + gap
        laptopMapPanel.place(Area(left, hingeBottom + gap, width - left - corner, height - hingeBottom - gap - corner))
        laptopMapPanel.placed = true
    }

    /** The canvas's size in pixels, for keeping things on screen. */
    val canvasSize get() = canvasW to canvasH

    /**
     * Pulls the track map back onto the screen, no bigger than it. Turning the phone for laptop
     * mode swapped the screen's width and height and could leave the map, and its lock, off the
     * right edge with no way to reach it.
     */
    private fun keepMapOnScreen() {
        if (canvasW <= 0f) return
        for (panel in listOf(mapPanel, laptopMapPanel)) {
            if (!panel.placed) continue
            panel.w = panel.w.coerceAtMost(canvasW)
            panel.h = panel.h.coerceAtMost(canvasH)
            panel.x = panel.x.coerceIn(0f, canvasW - panel.w)
            panel.y = panel.y.coerceIn(0f, canvasH - panel.h)
        }
    }

    /** Keeps the map's place, size, turn and opacity for next time. */
    fun storeMapPanel() {
        val panel = activeMapPanel
        if (canvasW <= 0f || !panel.placed) return
        prefs.edit().putString(if (laptopActive) PREF_LAPTOP_MAP else PREF_MAP_PANEL, panel.toJson(canvasW, canvasH, mapOpacity)).apply()
    }

    fun chooseMapMode(mode: MapMode) {
        mapGrabbed = false
        if (mode == mapMode) return
        mapMode = mode
        prefs.edit().putString(PREF_MAP_MODE, mode.name).apply()
        // Only a map the feeds keep clear of changes their layout. Hand-placed feeds stay put.
        if (mapReserve && !handPlaced && layoutMode != LayoutMode.CUSTOM) layoutRequest++
        if (canvasW > 0f) placeMapIfNeeded(canvasW, canvasH)
    }

    fun chooseMapReserve(on: Boolean) {
        mapReserve = on
        prefs.edit().putBoolean(PREF_MAP_RESERVE, on).apply()
        if (on) {
            mapPanel.placed = false
            handPlaced = false
            layoutRequest++
        }
    }

    fun toggleMapLock() {
        mapLocked = !mapLocked
        prefs.edit().putBoolean(PREF_MAP_LOCKED, mapLocked).apply()
        message = if (mapLocked) "Map locked. Feeds underneath can be moved." else "Map unlocked. Drag it by the track line."
    }

    fun rotateMap(degrees: Float) {
        activeMapPanel.rotation = (activeMapPanel.rotation + degrees).mod(360f)
        storeMapPanel()
    }

    fun resetMapRotation() {
        activeMapPanel.rotation = 0f
        storeMapPanel()
    }

    /** Two fingers on the track line while arranging: move, resize and turn the map. */
    fun arrangeMap(pan: androidx.compose.ui.geometry.Offset, zoom: Float, rotation: Float) {
        val panel = activeMapPanel
        panel.x += pan.x
        panel.y += pan.y
        if (zoom != 1f) panel.scale(zoom)
        if (rotation != 0f) panel.rotation = (panel.rotation + rotation).mod(360f)
        showChrome()
    }

    fun updateMapOpacity(value: Float) {
        mapOpacity = value
    }

    // Moving and resizing tiles by hand.

    // The explanation overlay.

    var helpOpen by mutableStateOf(false)
        private set
    /** Where each labelled control is on screen, filled in as they're laid out. */
    val helpAnchors = mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>()
    /** True once the overlay has been seen; before that it opens by itself. */
    val helpSeen get() = prefs.getBoolean(PREF_HELP_SEEN, false)

    fun showHelp() {
        helpOpen = true
        chromeVisible = true
        prefs.edit().putBoolean(PREF_HELP_SEEN, true).apply()
    }

    fun closeHelp() {
        helpOpen = false
        showChrome()
    }

    // Team radio.

    /** The radio panel is shown; on to start with, then remembered. */
    var radioOpen by mutableStateOf(prefs.getBoolean(PREF_RADIO, true))
        private set
    /** The clip playing, if any. */
    var radioPlaying by mutableStateOf<RadioClip?>(null)
        private set
    /**
     * Where the radio panel sits, in canvas pixels. First shown in the biggest space the feeds
     * leave; once dragged, kept (as fractions of the screen) for next time.
     */
    var radioX by mutableFloatStateOf(-1f)
    var radioY by mutableFloatStateOf(-1f)
    /** The panel's size once resized by hand, in canvas pixels; until then its default. */
    var radioW by mutableFloatStateOf(-1f)
    var radioH by mutableFloatStateOf(-1f)

    /**
     * The panel's place, and size if it's been resized, as fractions of the screen: x, y and
     * optionally w, h.
     */
    fun savedRadioSpot(): List<Float>? =
        prefs.getString(PREF_RADIO_SPOT, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size >= 2 }

    fun storeRadioSpot(width: Float, height: Float) {
        val size = if (radioW > 0f && radioH > 0f) ",${radioW / width},${radioH / height}" else ""
        prefs.edit().putString(PREF_RADIO_SPOT, "${radioX / width},${radioY / height}$size").apply()
    }

    /** Feed rectangles, for finding open space. */
    fun tileSlots() = tiles.map { Slot(it.x, it.y, it.w) }
    private var radioPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    fun toggleRadio() {
        radioOpen = !radioOpen
        prefs.edit().putBoolean(PREF_RADIO, radioOpen).apply()
        if (!radioOpen) stopRadio()
        showChrome()
    }

    /**
     * Plays a clip over the feeds, which go silent until it ends. Tapping the clip that's
     * playing stops it. No audio focus: taking it would pause the feeds.
     */
    fun playRadio(clip: RadioClip) {
        if (radioPlaying?.path == clip.path) {
            stopRadio()
            return
        }
        val base = timing?.radioBase ?: return
        val player = radioPlayer ?: androidx.media3.exoplayer.ExoPlayer.Builder(context).build().also { p ->
            radioPlayer = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) stopRadio()
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    message = "Couldn't play that radio clip"
                    stopRadio()
                }
            })
        }
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(base + clip.path))
        player.prepare()
        player.play()
        radioPlaying = clip
        tiles.forEach { it.feed.player.volume = if (it.audible && !mutedAll) RADIO_DUCK else 0f }
    }

    // Radio transcripts, from the phone's own speech recogniser.

    private val transcriber by lazy { RadioTranscriber(context) }
    private val transcriptStore = context.getSharedPreferences("radioText", Context.MODE_PRIVATE)
    /** Finished transcripts by clip path; kept, so a replay's radio is only transcribed once. */
    val transcripts = mutableStateMapOf<String, String>().apply {
        transcriptStore.all.forEach { (k, v) -> if (v is String) put(k, v) }
    }
    /** The clip being transcribed now. */
    var transcribing by mutableStateOf<String?>(null)
        private set
    /** Why transcripts can't be made, when they can't; null while they can. */
    var transcriptBlocked by mutableStateOf<String?>(null)
        private set
    private val failedClips = mutableSetOf<String>()

    /**
     * Transcribes clips one at a time while the radio panel is open, nearest the picture first
     * (the timing feed runs ahead of the picture, so a clip is usually done before it shows).
     */
    suspend fun transcribeRadio() {
        if (!transcriber.available) {
            transcriptBlocked = NO_RECOGNISER_NOTE
            return
        }
        while (true) {
            val t = timing
            val now = mapTimeMs()
            val next = if (t == null || now == null || transcriptBlocked != null) null else t.radio
                .filter { it.path !in transcripts && it.path !in failedClips && it.utc <= now + TRANSCRIBE_AHEAD_MS }
                .minByOrNull { abs(it.utc - now) }
            if (next == null || t == null) {
                kotlinx.coroutines.delay(2_000)
                continue
            }
            transcribing = next.path
            try {
                val text = transcriber.transcribe(t.radioBase + next.path, radioHints(t)).ifBlank { NO_WORDS }
                transcripts[next.path] = text
                transcriptStore.edit().putString(next.path, text).apply()
                radioFailuresInRow = 0
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // A clip that runs too long is skipped. (This was caught as a cancellation
                // in 0.23, which ended the whole loop: later clips were never transcribed.)
                android.util.Log.w("ApexviewRadio", "transcribe ${next.path}: timed out")
                failedClips += next.path
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SpeechPermissionNeeded) {
                transcriptBlocked = PERMISSION_NOTE
            } catch (e: Exception) {
                android.util.Log.w("ApexviewRadio", "transcribe ${next.path}: ${e.message}")
                failedClips += next.path
                // Several in a row means the recogniser can't do it at all: say so, and stop trying.
                if (++radioFailuresInRow >= 3) {
                    transcriptBlocked = "This phone's speech recogniser couldn't transcribe the clips (${(e as? RecogniserError)?.let { RecogniserError.name(it.code) } ?: e.message}). They still play."
                }
            } finally {
                transcribing = null
            }
        }
    }

    /** Names and teams in this session, plus radio talk, for the recogniser to lean toward. */
    private fun radioHints(t: SessionTiming): List<String> =
        (t.drivers.values.flatMap { listOf(it.lastName, it.firstName) } +
            t.drivers.values.map { it.team }.distinct() + RadioTranscriber.RADIO_WORDS)
            .filter { it.isNotBlank() }

    private var radioFailuresInRow = 0

    /** After the user allows the permission the recogniser asked for. */
    fun retryTranscripts() {
        transcriptBlocked = null
        radioFailuresInRow = 0
    }

    fun stopRadio() {
        radioPlayer?.stop()
        radioPlaying = null
        tiles.forEach { it.feed.setAudible(it.audible && !mutedAll) }
    }

    /** True while a finger holds the track map; the feeds under it stay put. */
    var mapGrabbed = false

    private var arrangeRaw: Box16? = null
    private var arrangeOthers: List<Pair<Tile, Box16>> = emptyList()

    private fun arranger() = context.resources.displayMetrics.density.let { d ->
        Arranger(canvasW, canvasH, snap = SNAP_DP * d, breakThrough = BREAK_DP * d, minWidth = Tile.MIN_WIDTH)
    }

    /** A finger (or two) came down on [tile] to move or resize it. */
    fun beginArrange(tile: Tile) {
        raise(tile)
        arrangeRaw = Box16(tile.x, tile.y, tile.w)
        arrangeOthers = tiles.filter { it !== tile }.map { it to Box16(it.x, it.y, it.w) }
    }

    /**
     * Moves the tile by [dx], [dy] and resizes it by [dw] (from a pinch or the corner handle). Free
     * mode goes exactly where the finger says. Snap mode applies the [Arranger]'s rules.
     */
    fun arrangeBy(tile: Tile, dx: Float, dy: Float, dw: Float) {
        // A finger on the map moves the map only, whatever is under it.
        if (mapGrabbed) return
        val raw = (arrangeRaw ?: Box16(tile.x, tile.y, tile.w)).let { it.copy(x = it.x + dx, y = it.y + dy, w = it.w + dw) }
        arrangeRaw = raw
        if (!snapMode || canvasW <= 0f) {
            tile.x = raw.x
            tile.y = raw.y
            tile.resize(raw.w)
        } else {
            val arranger = arranger()
            if (dw != 0f) {
                val (grown, pushed) = arranger.resize(Box16(tile.x, tile.y, tile.w), raw.w, arrangeOthers.map { it.second })
                tile.resize(grown.w)
                arrangeOthers.zip(pushed).forEach { (pair, box) -> pair.first.place(Slot(box.x, box.y, box.w)) }
            }
            if (dx != 0f || dy != 0f) {
                val moved = arranger.move(raw.copy(w = tile.w), tiles.filter { it !== tile }.map { Box16(it.x, it.y, it.w) })
                tile.x = moved.x
                tile.y = moved.y
            }
        }
        showChrome()
    }

    fun endArrange() {
        arrangeRaw = null
        arrangeOthers = emptyList()
        handPlaced = true
        saveWatch()
    }

    fun nudgeMapOffset(stepMs: Long) {
        mapOffsetMs += stepMs
        prefs.edit().putLong(PREF_MAP_OFFSET, mapOffsetMs).apply()
    }

    fun togglePin(tile: Tile) {
        tile.pinned = !tile.pinned
    }

    private var canvasW = 0f
    private var canvasH = 0f

    /**
     * Each screen shape's hand-placed arrangement (by the way up and the screen's size), kept on
     * leaving it so that coming back puts the feeds where they were: tile id to x, y, w as
     * fractions of the screen.
     */
    private val arrangements = mutableMapOf<String, Map<Int, FloatArray>>()

    private fun screenKey(w: Float, h: Float) = (if (w > h) "L" else "P") + (minOf(w, h) / 200f).toInt()

    /**
     * Folding, unfolding or turning the phone: lay the feeds out again for the new screen, or put
     * back how they were last arranged on a screen this shape. The inner screen is nearly square,
     * so turning it changes its size by under 4%; it counts all the same (before 0.42 it only
     * nudged the feeds and they ended up overlapping). Smaller changes (system bars, a window
     * resize) only pull tiles back on screen.
     */
    fun onCanvasResized(width: Float, height: Float) {
        val turned = canvasW > 0f && (canvasW > canvasH) != (width > height)
        val refit = canvasW > 0f && (turned || abs(width - canvasW) > canvasW * 0.1f || abs(height - canvasH) > canvasH * 0.1f)
        if (refit && handPlaced && tiles.isNotEmpty() && focusTileId == null && !laptopActive) {
            arrangements[screenKey(canvasW, canvasH)] =
                tiles.associate { it.id to floatArrayOf(it.x / canvasW, it.y / canvasH, it.w / canvasW) }
        }
        if (refit && mapPanel.placed) {
            // The map keeps its place relative to the screen.
            mapPanel.x *= width / canvasW
            mapPanel.y *= height / canvasH
            mapPanel.w *= width / canvasW
            mapPanel.h *= height / canvasH
        }
        canvasW = width
        canvasH = height
        keepMapOnScreen()
        if (!refit) {
            keepOnScreen(width, height)
            return
        }
        // Back on a screen shape these same feeds were arranged on by hand: put them back.
        val back = arrangements[screenKey(width, height)]
            ?.takeIf { saved -> saved.size == tiles.size && tiles.all { it.id in saved } }
        if (back != null && focusTileId == null && !laptopActive) {
            tiles.forEach { t ->
                val b = back.getValue(t.id)
                t.place(Slot(b[0] * width, b[1] * height, b[2] * width))
            }
            handPlaced = true
        } else {
            handPlaced = false
            layoutRequest++
        }
    }

    /** Pulls tiles back so each one is at least partly on screen. */
    fun keepOnScreen(width: Float, height: Float) {
        // Feeds tucked away behind a full-screen one stay tucked away.
        tiles.filter { if (laptopActive) it.id == bigTileId else focusTileId == null || it.id == focusTileId }.forEach { tile ->
            if (tile.w > width) tile.resize(width)
            tile.x = tile.x.coerceIn(-tile.w / 2, width - tile.w / 2)
            tile.y = tile.y.coerceIn(0f, height - tile.h / 2)
        }
    }

    fun raise(tile: Tile) {
        topZ += 1f
        tile.z = topZ
    }

    /** Moves the audio to [tile]. Choosing a feed to listen to also ends Mute all. */
    fun setAudio(tile: Tile, byUser: Boolean = false) {
        if (byUser) mutedAll = false
        tiles.forEach {
            it.audible = it === tile
            it.feed.setAudible(it.audible && !mutedAll)
        }
    }

    /** Silences every feed, keeping track of which one had the audio for when it's turned back on. */
    fun toggleMuteAll() {
        mutedAll = !mutedAll
        tiles.forEach { it.feed.setAudible(it.audible && !mutedAll) }
        showChrome()
    }

    /**
     * Double-tap: the tile trades places with the biggest one. The audio stays where it was,
     * normally the main broadcast; the tile offers its own audio for a few seconds instead.
     */
    /**
     * On a narrow screen (the cover screen, where feeds stack), a double-tap fills the screen
     * with that feed and tucks the rest away (still playing); double-tap again to bring them
     * back. On the wide screen it swaps the feed into the big spot.
     */
    fun doubleTap(tile: Tile) {
        when {
            // Laptop mode shows one feed: a double-tap on an onboard goes back to the broadcast.
            // (Swapping would trade places with a feed waiting off screen and leave the top black.)
            laptopActive -> if (laptopReturnChannel != null) returnToBroadcast()
            focusTileId != null -> {
                focusTileId = null
                layoutRequest++
            }
            compactCanvas && tiles.size > 1 -> {
                focusTileId = tile.id
                layoutRequest++
            }
            else -> swapIntoBig(tile)
        }
    }

    // Laptop mode: the phone half-open with the hinge across, the stream above it and timing below.

    /** Where the hinge runs across the screen (top, bottom), while half-open like a laptop. */
    var hinge by mutableStateOf<Pair<Float, Float>?>(null)
        private set
    /** Laptop mode is wanted when the phone is in that position. Off until asked. */
    var laptopEnabled by mutableStateOf(prefs.getBoolean(PREF_LAPTOP, false))
        private set
    /** The one-time question, showing. */
    var laptopPromptOpen by mutableStateOf(false)
        private set

    /** Laptop mode is on screen now. */
    val laptopActive get() = laptopEnabled && hinge != null && tiles.isNotEmpty()

    /** From the activity: the fold's posture changed. */
    fun updatePosture(newHinge: Pair<Float, Float>?) {
        val was = laptopActive
        hinge = newHinge
        // The first time the phone is held like a laptop while watching, ask.
        if (newHinge != null && tiles.isNotEmpty() && !prefs.getBoolean(PREF_LAPTOP_ASKED, false)) laptopPromptOpen = true
        if (laptopActive != was) relayoutGliding()
    }

    fun answerLaptopPrompt(use: Boolean) {
        laptopPromptOpen = false
        prefs.edit().putBoolean(PREF_LAPTOP_ASKED, true).apply()
        chooseLaptop(use)
    }

    fun chooseLaptop(on: Boolean) {
        val was = laptopActive
        laptopEnabled = on
        prefs.edit().putBoolean(PREF_LAPTOP, on).apply()
        if (laptopActive != was) relayoutGliding()
    }

    /** The feed in the big spot (up top in laptop mode), for highlighting it elsewhere. */
    val bigTileIdForUi get() = bigTileId

    /** The next layout moves feeds along the app's curve instead of jumping them. */
    private var glideNextLayout = false

    private fun relayoutGliding() {
        glideNextLayout = true
        layoutRequest++
    }

    /** Puts a feed in its slot: straight there, or gliding when the layout asked to. */
    private fun settle(tile: Tile, slot: Slot) {
        if (glideNextLayout) glide(tile, slot) else tile.place(slot)
    }

    /** In laptop mode, puts [tile] in the one spot above the hinge. */
    private fun putOnTop(tile: Tile) {
        if (bigTileId != tile.id) previousTopId = bigTileId
        bigTileId = tile.id
        layoutRequest++
        // Its picture back now, not at the next half-second check.
        applyVideoBudget()
    }

    /** In laptop mode, the broadcast (F1 Live, International...) that was up top before an onboard. */
    var laptopReturn by mutableStateOf<Pair<Int, Channel>?>(null)
        private set

    /** Notes the broadcast on top, once, as an onboard is about to replace it. */
    private fun rememberBroadcast() {
        val top = tiles.firstOrNull { it.id == bigTileId } ?: return
        if (top.channel.racingNumber == null) laptopReturn = top.id to top.channel
    }

    /**
     * The broadcast to go back to while an onboard is up top: the one it replaced, else (a session
     * resumed on an onboard) a broadcast feed waiting below, else the session's main feed.
     */
    private fun broadcastBehind(): Pair<Int, Channel>? {
        val top = tiles.firstOrNull { it.id == bigTileId }?.takeIf { laptopActive && it.channel.racingNumber != null } ?: return null
        laptopReturn?.let { return it }
        tiles.filter { it.channel.racingNumber == null }.sortedByDescending { it.audible }.firstOrNull()?.let { return it.id to it.channel }
        val main = channelsByContent[top.contentId]?.let { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull { it.racingNumber == null } }
        return main?.let { top.id to it }
    }

    /** The broadcast to offer going back to: only while an onboard is up top. */
    val laptopReturnChannel: Channel?
        get() = broadcastBehind()?.second

    /**
     * Back to the broadcast that was up top: its feed if it's still playing below, else the top
     * feed switches back to it.
     */
    fun returnToBroadcast() {
        val (tileId, channel) = broadcastBehind() ?: return
        laptopReturn = null
        val waiting = tiles.firstOrNull { it.id == tileId && it.channel == channel } ?: tiles.firstOrNull { it.channel == channel }
        if (waiting != null) putOnTop(waiting)
        else tiles.firstOrNull { it.id == bigTileId }?.let { switchFeed(it, channel) }
    }

    // Reminders asked for from home's Notify me: the calendar opens with its reminder settings up.
    private var remindersAsked = false

    fun openReminders() {
        remindersAsked = true
        open(Screen.Calendar)
    }

    /** True once after [openReminders]. */
    fun takeRemindersRequest(): Boolean = remindersAsked.also { remindersAsked = false }

    /** The feed filling a narrow screen after a double-tap, if any. */
    var focusTileId by mutableStateOf<Int?>(null)
        private set

    /**
     * A phone-sized screen (shorter side under 600dp): the cover screen, upright or sideways. The
     * inner screen is far bigger either way.
     */
    private val compactCanvas get() =
        canvasW > 0f && minOf(canvasW, canvasH) / context.resources.displayMetrics.density < COMPACT_DP

    fun swapIntoBig(tile: Tile) {
        val big = tiles.maxByOrNull { it.w } ?: return
        if (big === tile) return
        val tileSlot = Slot(tile.x, tile.y, tile.w)
        val bigSlot = Slot(big.x, big.y, big.w)
        glide(tile, bigSlot)
        glide(big, tileSlot)
        bigTileId = tile.id
        handPlaced = true
        raise(tile)
        if (!tile.audible) {
            audioHint = tile
            audioHintCount++
        }
    }

    fun dismissAudioHint() {
        audioHint = null
    }

    // Driver of the Day: a note near the end of a live Grand Prix.

    /** The note, showing. Once per race, and never with the setting off. */
    var votePromptOpen by mutableStateOf(false)
        private set
    /** The first note ever also offers to turn them off, without a trip to Settings. */
    var votePromptFirst by mutableStateOf(false)
        private set

    /** On each live update: 6 laps to go, or the race just finished, in a Grand Prix watched live. */
    private fun offerVote(contentId: Long, live: SessionTiming) {
        if (!settings.votePrompt || votePromptOpen || !live.isGrandPrix) return
        if (prefs.getLong(PREF_VOTE_ASKED, 0L) == contentId) return
        val lap = live.currentLap ?: return
        val total = live.totalLaps ?: return
        val finished = live.finishedUtc
        // Laps to go as the lap board counts them: the lap under way is one of them.
        if (finished == null && (lap <= 0 || total - lap + 1 > VOTE_LAPS_LEFT)) return
        // Joined long after the flag: the vote has closed.
        if (finished != null && System.currentTimeMillis() - finished > VOTE_OPEN_AFTER_MS) return
        votePromptFirst = !prefs.getBoolean(PREF_VOTE_SEEN, false)
        prefs.edit().putLong(PREF_VOTE_ASKED, contentId).putBoolean(PREF_VOTE_SEEN, true).apply()
        votePromptOpen = true
    }

    /**
     * Pops the race out, then opens F1's vote beside it. Opening another app from inside doesn't
     * count as leaving, so the pop-out wouldn't happen by itself.
     */
    fun vote(activity: android.app.Activity) {
        votePromptOpen = false
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(VOTE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val popped = canPip && runCatching {
            activity.enterPictureInPictureMode(pipParams(activity, pipTile(), canPip, playing))
        }.getOrDefault(false)
        if (!popped) return activity.startActivity(open)
        scope.launch {
            kotlinx.coroutines.delay(VOTE_OPEN_DELAY_MS)
            activity.startActivity(open)
        }
    }

    fun dismissVotePrompt(forGood: Boolean = false) {
        votePromptOpen = false
        if (forGood) settings.updateVotePrompt(false)
    }

    // Pop-out (picture-in-picture) when leaving the app while watching.

    var inPip by mutableStateOf(false)
        private set

    /** Pop out only from the viewer itself, not from browsing or an open sheet. */
    val canPip get() = tiles.isNotEmpty() && !browsing && picker == null

    /** The feed the pop-out shows: the big tile in main + strip, else the top-left one. */
    fun pipTile(): Tile? =
        if (layoutMode == LayoutMode.FEATURED) tiles.firstOrNull { it.id == bigTileId } ?: tiles.maxByOrNull { it.w }
        else tiles.minWithOrNull(compareBy<Tile>({ it.y }, { it.x }))

    /**
     * In the pop-out only the shown feed and the one with the audio keep playing; the rest pause
     * to save battery, and the sync loop catches them up on return.
     */
    fun setPip(on: Boolean) {
        inPip = on
        val shown = pipTile()
        tiles.forEach { tile ->
            tile.feed.player.playWhenReady = playing && (!on || tile === shown || tile.audible)
        }
        if (!on) showChrome()
    }

    /** Leaving the app without a pop-out (or closing the pop-out) pauses everything. */
    fun pauseForBackground() {
        saveWatch()
        if (tiles.isNotEmpty() && playing) togglePlay()
    }

    /** Moves a tile to a new place and size along the app's one easing curve. */
    private fun glide(tile: Tile, to: Slot) {
        val from = Slot(tile.x, tile.y, tile.w)
        // animate() needs the display's frame clock, which only Compose's UI dispatcher carries;
        // the plain lifecycle scope has none and crashed the double-tap swap in 0.9 and 0.10.
        scope.launch(AndroidUiDispatcher.Main) {
            animate(0f, 1f, animationSpec = tween(Motion.SCREEN_MS, easing = EaseOut)) { f, _ ->
                tile.x = from.x + (to.x - from.x) * f
                tile.y = from.y + (to.y - from.y) * f
                tile.resize(from.w + (to.w - from.w) * f)
            }
        }
    }

    fun remove(tile: Tile) {
        if (audioHint === tile) audioHint = null
        if (targetTile === tile) targetTile = null
        tileMenu = null
        tile.feed.release()
        tiles.remove(tile)
        if (tiles.isEmpty()) {
            stopAll()
            return
        }
        if (focusTileId == tile.id) focusTileId = null
        if (tile.audible) setAudio(tiles.first())
        if (bigTileId == tile.id) bigTileId = null
        // The rest close up to fill the space.
        handPlaced = false
        layoutRequest++
    }

    fun stopAll() {
        // Closing the viewer keeps the spot, so Continue watching can bring it back.
        saveWatch()
        votePromptOpen = false
        tiles.forEach { it.feed.release() }
        tiles.clear()
        sessions.clear()
        singleSessionOnly.clear()
        browsing = false
        tileMenu = null
        targetTile = null
        helpOpen = false
        helpAnchors.clear()
        mapSheetOpen = false
        layoutSheetOpen = false
        arranging = false
        mutedAll = false
        playing = true
        timing = null
        timingContentId = null
        radioPlayer?.release()
        radioPlayer = null
        radioPlaying = null
        liveFeed?.cancel()
        liveFeed = null
        mapStatus = null
    }

    // Chrome: the controls fade away while watching and come back on touch.

    fun showChrome() {
        chromeVisible = true
        chromeTouch++
    }

    fun toggleChrome() {
        if (chromeVisible) chromeVisible = false else showChrome()
    }

    // Shared playback. The first tile leads; the others follow it.

    private fun leaderTile() = tiles.firstOrNull()

    fun togglePlay() {
        playing = !playing
        // In the pop-out, only the shown feed and the audio keep playing (see setPip).
        val shown = if (inPip) pipTile() else null
        tiles.forEach { it.feed.player.playWhenReady = playing && (!inPip || it === shown || it.audible) }
        showChrome()
    }

    /** From the player itself, not positionMs: in the pop-out the sync loop that updates it isn't running. */
    fun skip(deltaMs: Long) {
        val leader = leaderTile() ?: return
        seekTo(leader.feed.player.currentPosition + deltaMs)
    }

    fun seekTo(targetMs: Long) {
        val leader = leaderTile() ?: return
        // A live stream's length can be unknown; then only the start bounds the seek (the player
        // stops at the live edge itself). Clamping to an unknown length sent skips back to 0.
        val duration = leader.feed.player.duration.takeIf { it > 0 } ?: durationMs.takeIf { it > 0 }
        val target = if (duration != null) targetMs.coerceIn(0L, duration) else targetMs.coerceAtLeast(0L)
        val shift = target - leader.feed.player.currentPosition
        // Every tile moves by the same amount, so their offsets from each other survive the seek.
        tiles.forEach { it.feed.player.seekTo((it.feed.player.currentPosition + shift).coerceAtLeast(0L)) }
        positionMs = target
        showChrome()
    }

    fun goLive() {
        tiles.forEach { it.feed.player.seekToDefaultPosition() }
        showChrome()
    }

    /**
     * Runs twice a second. Followers of the same content line up with the leader by stream clock
     * when both streams carry one, else by position. Far off: jump. Slightly off: play a little
     * faster or slower until caught up, the way eXViewer does.
     */
    fun syncTick() {
        val leaderTile = leaderTile() ?: return
        val leader = leaderTile.feed
        leader.driftMs = null
        leader.setSpeed(1f)
        positionMs = leader.player.currentPosition
        durationMs = leader.player.duration.takeIf { it > 0 } ?: 0L
        isLive = leader.player.isCurrentMediaItemLive
        if (leader.player.playbackState != Player.STATE_READY) return
        // Twice a second, so every 10th tick is a save every 5 s.
        if (++saveTicks % 10 == 0) saveWatch()

        val leaderClock = leader.wallClockMs()
        tiles.drop(1).forEach { tile ->
            val feed = tile.feed
            if (tile.contentId != leaderTile.contentId || feed.player.playbackState != Player.STATE_READY) {
                feed.driftMs = null
                feed.setSpeed(1f)
                return@forEach
            }
            val clock = feed.wallClockMs()
            val drift = if (leaderClock != null && clock != null) clock - leaderClock
            else feed.player.currentPosition - leader.player.currentPosition
            feed.driftMs = drift

            when {
                abs(drift) > SEEK_DRIFT_MS -> {
                    feed.setSpeed(1f)
                    feed.player.seekTo((feed.player.currentPosition - drift).coerceAtLeast(0L))
                }
                abs(drift) > NUDGE_DRIFT_MS && playing -> feed.setSpeed(if (drift > 0) 1f - NUDGE else 1f + NUDGE)
                else -> feed.setSpeed(1f)
            }
        }
    }

    fun dismissMessage() {
        message = null
    }

    private fun task(what: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            val progress = "$what..."
            message = progress
            try {
                block()
                if (message == progress) message = null
            } catch (e: Exception) {
                message = "$what failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    companion object {
        private const val SEEK_DRIFT_MS = 2000L
        private const val NUDGE_DRIFT_MS = 150L
        private const val NUDGE = 0.1f
        const val MAX_FEEDS = 4
        private const val MAX_RECENTS = 4
        /** F1 TV's race weekends start with 2018; before that, a season is a set of videos. */
        const val FIRST_WEEKEND_SEASON = 2018
        private const val FIRST_ARCHIVE_SEASON = 1981
        private const val PREF_LAPTOP = "laptopMode"
        private const val PREF_LAPTOP_ASKED = "laptopAsked"
        private const val COMPACT_DP = 600f
        private const val PREF_RADIO = "radioOpen"
        private const val PREF_HELP_SEEN = "helpSeen"
        private const val PREF_RADIO_SPOT = "radioSpot"
        /** Clips this far ahead of the picture get transcribed early, so they're ready on time. */
        private const val TRANSCRIBE_AHEAD_MS = 120_000L
        const val NO_WORDS = "(no words recognised)"
        const val PERMISSION_NOTE =
            "Android's speech recogniser needs the microphone permission to transcribe. The app never records you."
        const val NO_RECOGNISER_NOTE =
            "This phone has no speech recogniser Apexview can use (it needs Android 13 or later), so clips play but aren't transcribed."
        /** The feeds' volume while a radio clip plays: silent, so the radio is clear. */
        private const val RADIO_DUCK = 0f
        private const val PREF_RECENTS = "recents"
        private const val PREF_HISTORY = "history"
        private val NO_VIDEO_TIERS = setOf("ANONYMOUS", "REG")
        private const val MAX_HISTORY = 300
        private const val MAX_PICKS = 7
        private const val DAY_MS = 86_400_000L
        private val DAY = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
        private const val PREF_LAYOUT = "layout"
        private const val PREF_DEFAULT_LAYOUT = "defaultLayout"
        private const val PREF_SNAP = "snap"
        private const val PREF_MAP_PANEL = "mapPanel"
        private const val PREF_LAPTOP_MAP = "laptopMapPanel"
        /** How much of the width below the hinge the timing tower takes in laptop mode. */
        const val LAPTOP_TOWER_SHARE = 0.52f
        private const val PREF_MAP_LOCKED = "mapLocked"
        private const val PREF_MAP_RESERVE = "mapReserve"
        private const val PREF_START_LINE = "startLine"
        // Snap mode: edges pull together inside 12dp; a push past 56dp goes through.
        private const val SNAP_DP = 12f
        private const val BREAK_DP = 56f
        private const val PREF_MAP_OFFSET = "mapOffsetMs"
        private const val DEFAULT_MAP_OFFSET_MS = -7_000L
        private const val PREF_SAVED_WATCH = "savedWatch"
        private const val PREF_MAP_MODE = "mapMode"
        private const val ACCOUNT_URL = "https://account.formula1.com/"
        private const val VOTE_URL = "https://www.formula1.com/en/page/vote"
        private const val PREF_VOTE_ASKED = "voteAskedFor"
        private const val PREF_VOTE_SEEN = "voteSeen"
        /**
         * F1's own "vote now" posts in 2026 came with 8 to 15 laps to go, so by 6 the vote is
         * surely open.
         */
        private const val VOTE_LAPS_LEFT = 6
        /** Lets the pop-out get going before the browser opens over the screen. */
        private const val VOTE_OPEN_DELAY_MS = 300L
        /** F1 names its Driver of the Day soon after the flag; past this the vote is long shut. */
        private const val VOTE_OPEN_AFTER_MS = 15 * 60_000L
        private const val FULL_NOTE = "4 feeds is the most this phone plays smoothly. Unpick one that's playing to make room."
    }
}
