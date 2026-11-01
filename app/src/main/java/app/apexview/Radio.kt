package app.apexview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Short and wide, to sit in the strip of space under or between the feeds: two clips tall. */
private val PANEL_MAX_WIDTH = 560.dp
private val ROW_HEIGHT = 50.dp
private val HEADER_HEIGHT = 36.dp
private val PANEL_MAX_HEIGHT = HEADER_HEIGHT + ROW_HEIGHT * 2 + 4.dp
/** The smallest the panel can be made: its title and one clip, and narrow enough to tuck aside. */
private val PANEL_MIN_WIDTH = 220.dp
private val PANEL_MIN_HEIGHT = HEADER_HEIGHT + ROW_HEIGHT + 4.dp
/** Edges this close to another edge (the screen's, a feed's, the hinge) pull onto it, as feeds do. */
private val SNAP = 12.dp

/**
 * The edges the panel's edges pull toward: the screen's, the feeds' (where they are on screen,
 * shrunk under the controls by [feedScale]) and the hinge in laptop mode.
 */
private fun snapLines(app: AppState, width: Float, height: Float, feedScale: Float): Pair<List<Float>, List<Float>> {
    val feeds = app.tiles.map { t ->
        onScreen(androidx.compose.ui.geometry.Rect(t.x, t.y, t.x + t.w, t.y + t.h), width, height, feedScale)
    }.filter { it.left < width && it.right > 0f }
    val xs = listOf(0f, width) + feeds.flatMap { listOf(it.left, it.right) }
    val ys = listOf(0f, height) + feeds.flatMap { listOf(it.top, it.bottom) } +
        (app.hinge?.takeIf { app.laptopActive }?.toList() ?: emptyList())
    return xs to ys
}

/** How far to move so the nearest of [edges] lands on a line within [reach]; 0 if none is close. */
private fun pull(edges: List<Float>, lines: List<Float>, reach: Float): Float {
    var best = 0f
    var bestDistance = reach
    for (e in edges) for (l in lines) {
        val d = l - e
        if (kotlin.math.abs(d) <= bestDistance) {
            bestDistance = kotlin.math.abs(d)
            best = d
        }
    }
    return best
}
/** A clip this new (by the picture's clock) flashes once as it arrives. */
private const val FRESH_MS = 3_000L

/**
 * Team radio as it comes in, oldest at the top and the newest at the bottom, where the list
 * follows it; each clip is marked with the lap the driver was on. Follows
 * the picture like the map does, so nothing shows before it's happened on screen. Tap a clip to
 * hear it over the feeds; drag the title to move the panel and the corner to resize it. With
 * Snap on (Layout), its edges pull onto the screen's, the feeds' and the hinge, as the feeds do.
 */
@Composable
fun RadioPanel(app: AppState, timing: SessionTiming, width: Float, height: Float, feedScale: Float) {
    val density = LocalDensity.current
    // Twice a second is plenty for a list.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            now = app.mapTimeMs() ?: 0L
            delay(500)
        }
    }
    // Transcribe while the panel is up.
    LaunchedEffect(Unit) { app.transcribeRadio() }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) app.retryTranscripts() }
    val heard = timing.radio.count { it.utc <= now }
    val clips = remember(timing, heard) { timing.radio.take(heard) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // A new clip brings the list down to it.
    LaunchedEffect(clips.size) { if (clips.isNotEmpty()) list.animateScrollToItem(clips.lastIndex) }

    val panelWidth = with(density) { minOf(PANEL_MAX_WIDTH, (width.toDp() - 32.dp)) }
    if (app.radioX < 0f) {
        with(density) {
            val w = panelWidth.toPx()
            val h = PANEL_MAX_HEIGHT.toPx()
            val saved = app.savedRadioSpot()
            if (saved != null && saved.size >= 4 && saved[2] > 0f && saved[3] > 0f) {
                app.radioW = saved[2] * width
                app.radioH = saved[3] * height
            }
            // The biggest gap the feeds leave, if the panel fits in it; else above the controls.
            val gap = Layouts.largestFree(app.tileSlots(), width, height)?.takeIf { it.h >= h }
            when {
                saved != null -> {
                    app.radioX = saved[0] * width
                    app.radioY = saved[1] * height
                }
                gap != null -> {
                    app.radioX = gap.x + (gap.w - w).coerceAtLeast(0f) / 2
                    app.radioY = gap.y + (gap.h - h) / 2
                }
                else -> {
                    app.radioX = (width - w) / 2
                    app.radioY = height - h - 120.dp.toPx()
                }
            }
            app.radioX = app.radioX.coerceIn(0f, (width - w).coerceAtLeast(0f))
            app.radioY = app.radioY.coerceIn(0f, (height - h).coerceAtLeast(0f))
        }
    }
    val shownWidth = if (app.radioW > 0f) with(density) { app.radioW.toDp() } else panelWidth
    // Where the finger has taken the panel (or its corner) since the gesture began, before
    // snapping: snapping works from this, so an edge can be pulled back off a line.
    var rawX by remember { mutableFloatStateOf(0f) }
    var rawY by remember { mutableFloatStateOf(0f) }
    var rawW by remember { mutableFloatStateOf(0f) }
    var rawH by remember { mutableFloatStateOf(0f) }
    var boxW by remember { mutableFloatStateOf(0f) }
    var boxH by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .offset { IntOffset(app.radioX.roundToInt(), app.radioY.roundToInt()) }
            .helpAnchor(app, "radio")
            .onSizeChanged { boxW = it.width.toFloat(); boxH = it.height.toFloat() }
    ) {
        Column(
            Modifier
                .width(shownWidth)
                .then(if (app.radioH > 0f) Modifier.height(with(density) { app.radioH.toDp() }) else Modifier.heightIn(max = PANEL_MAX_HEIGHT))
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.72f))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { rawX = app.radioX; rawY = app.radioY },
                            onDragEnd = { app.storeRadioSpot(width, height) },
                        ) { change, drag ->
                            change.consume()
                            rawX += drag.x
                            rawY += drag.y
                            var x = rawX
                            var y = rawY
                            if (app.snapMode) {
                                val (xs, ys) = snapLines(app, width, height, feedScale)
                                x += pull(listOf(x, x + boxW), xs, SNAP.toPx())
                                y += pull(listOf(y, y + boxH), ys, SNAP.toPx())
                            }
                            app.radioX = x.coerceIn(0f, (width - boxW).coerceAtLeast(0f))
                            app.radioY = y.coerceIn(0f, (height - HEADER_HEIGHT.toPx()).coerceAtLeast(0f))
                        }
                    }
                    .height(HEADER_HEIGHT)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Team radio", style = Type.headline, modifier = Modifier.weight(1f))
                Box(Modifier.size(36.dp).pressable(onClick = app::toggleRadio), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Hide team radio", tint = Ink.secondary, modifier = Modifier.size(18.dp))
                }
            }
            app.transcriptBlocked?.let { note ->
                Text(
                    "$note Tap to allow.",
                    style = Type.caption,
                    modifier = Modifier
                        .pressable { ask.launch(android.Manifest.permission.RECORD_AUDIO) }
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            if (clips.isEmpty()) {
                Text(
                    if (timing.radio.isEmpty()) "No radio in this session yet." else "Radio shows up here as it happens.",
                    style = Type.footnote,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                )
                return@Column
            }
            LazyColumn(Modifier.padding(bottom = 4.dp), state = list) {
                items(clips, key = { it.path }) { clip -> RadioRow(app, timing, clip, fresh = now - clip.utc < FRESH_MS) }
            }
        }
        // The corner grip: drag to resize, edges snapping like the feeds'.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(36.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { rawW = boxW; rawH = boxH },
                        onDragEnd = { app.storeRadioSpot(width, height) },
                    ) { change, drag ->
                        change.consume()
                        rawW += drag.x
                        rawH += drag.y
                        var w = rawW
                        var h = rawH
                        if (app.snapMode) {
                            val (xs, ys) = snapLines(app, width, height, feedScale)
                            w += pull(listOf(app.radioX + w), xs, SNAP.toPx())
                            h += pull(listOf(app.radioY + h), ys, SNAP.toPx())
                        }
                        app.radioW = w.coerceIn(PANEL_MIN_WIDTH.toPx(), (width - app.radioX).coerceAtLeast(PANEL_MIN_WIDTH.toPx()))
                        app.radioH = h.coerceIn(PANEL_MIN_HEIGHT.toPx(), (height - app.radioY).coerceAtLeast(PANEL_MIN_HEIGHT.toPx()))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                val stroke = 1.5.dp.toPx()
                val c = Color.White.copy(alpha = 0.45f)
                drawLine(c, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), stroke)
                drawLine(c, androidx.compose.ui.geometry.Offset(size.width, size.height / 2), androidx.compose.ui.geometry.Offset(size.width / 2, size.height), stroke)
            }
        }
    }
}

@Composable
private fun RadioRow(app: AppState, timing: SessionTiming, clip: RadioClip, fresh: Boolean) {
    val driver = timing.drivers[clip.number]
    val playing = app.radioPlaying?.path == clip.path
    val lap = timing.laps[clip.number]?.lapAt(clip.utc)
    val text = app.transcripts[clip.path]
    // Arriving clips flash once, softly.
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(clip.path) {
        if (fresh) {
            flash.animateTo(1f, androidx.compose.animation.core.tween(Motion.FADE_MS, easing = EaseOut))
            flash.animateTo(0f, androidx.compose.animation.core.tween(Motion.SCREEN_MS * 2, easing = EaseOut))
        }
    }
    // One line per clip: who and when on the left, what was said beside it.
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .pressable(scaleTo = 0.99f) { app.playRadio(clip) }
            .background(Color.White.copy(alpha = if (playing) 0.1f else 0.18f * flash.value))
            .padding(start = 16.dp, end = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(driver?.color?.let { Color(it) } ?: Color.White)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(54.dp)) {
            Text(driver?.tla ?: clip.number, style = Type.subhead.copy(color = Ink.primary))
            Text(lap?.let { "Lap $it" } ?: "", style = Type.caption)
        }
        Text(
            when {
                text != null && text != AppState.NO_WORDS -> "\u201C$text\u201D"
                app.transcribing == clip.path -> "Transcribing…"
                else -> ""
            },
            style = Type.footnote.copy(color = if (text != null) Ink.primary else Ink.secondary),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Icon(
            if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            if (playing) "Stop" else "Play",
            tint = Ink.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}
