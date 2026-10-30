package app.apexview

import android.app.Activity
import android.os.Build
import android.view.LayoutInflater
import android.view.RoundedCorner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val CHROME_Z = 2e9f
private const val CHROME_HIDE_MS = 4_000L
/** How far the feeds move down (by shrinking) while the controls are up: the top bar's height. */
private val CONTROLS_BAND = 56.dp
private const val AUDIO_HINT_MS = 4_500L
/** How long a tapped feed stays the target for a car picked on the map. */
private const val TARGET_MS = 10_000L
private val GLOW = Color(0xFFFFE08A)
private const val GLOW_STOPS = 24
/** A camera within this fraction of the screen's width from a side counts as on that side. */
private const val SIDE_CUTOUT = 0.3f
/** The first time a stream plays, the explanation overlay waits this long for things to settle. */
private const val HELP_FIRST_DELAY_MS = 2_500L
/** On a live stream, this close to the newest picture still shows as Live. */
private const val LIVE_EDGE_MS = 30_000L
/** Onboards narrower than this skip the timing card; it would cover too much of the picture. */
private val CARD_MIN_TILE = 230.dp
private val CARD_MAX_WIDTH = 250.dp
private val CARD_MARGIN = 10.dp

/** Corner radius of the physical screen, so the edge ribbon can follow it. 0 if unknown. */
@Composable
private fun rememberScreenCornerRadius(): Float {
    val view = LocalView.current
    // The cover and inner screens have different corners, so read again after a fold or unfold.
    val screen = LocalConfiguration.current.let { it.screenWidthDp to it.screenHeightDp }
    var radius by remember { mutableStateOf(0f) }
    LaunchedEffect(view, screen) {
        if (Build.VERSION.SDK_INT >= 31) {
            // Insets aren't attached on the first frame.
            repeat(10) {
                view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let {
                    radius = it.radius.toFloat()
                    return@LaunchedEffect
                }
                delay(100)
            }
        }
    }
    return radius
}

/** The camera holes and notches, in screen pixels. Read again after a fold or unfold. */
@Composable
private fun rememberCutouts(): List<android.graphics.Rect> {
    val view = LocalView.current
    val config = LocalConfiguration.current
    val screen = Triple(config.screenWidthDp, config.screenHeightDp, config.orientation)
    var rects by remember { mutableStateOf(emptyList<android.graphics.Rect>()) }
    LaunchedEffect(view, screen) {
        // Insets aren't attached on the first frame.
        repeat(10) {
            view.rootWindowInsets?.let { insets ->
                rects = insets.displayCutout?.boundingRects.orEmpty().filter { !it.isEmpty }
                return@LaunchedEffect
            }
            delay(100)
        }
    }
    return rects
}

/** Room a row of controls between [top] and [bottom] leaves at its start and end for the camera. */
private class Clearance(val start: Float, val end: Float)

private fun clearOf(cutouts: List<android.graphics.Rect>, width: Float, top: Float, bottom: Float): Clearance {
    var start = 0f
    var end = 0f
    cutouts.filter { it.bottom > top && it.top < bottom }.forEach { r ->
        // Only a camera near a side pushes the buttons in. One near the middle (the cover
        // screen's) sits over the title; treating it as a right-side camera padded half the bar
        // away and pushed the map button off the screen.
        when {
            r.exactCenterX() < width * SIDE_CUTOUT -> start = maxOf(start, r.right.toFloat())
            r.exactCenterX() > width * (1 - SIDE_CUTOUT) -> end = maxOf(end, width - r.left)
        }
    }
    return Clearance(start, end)
}

@Composable
fun ViewerScreen(app: AppState) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val bars = WindowCompat.getInsetsController(window, view)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // The controls step back a few seconds after the last touch, unless paused.
    // helpOpen is a key so opening the overlay cancels a hide already counting down; it used
    // to fire anyway and take the top bar away from under its labels.
    LaunchedEffect(app.chromeTouch, app.chromeVisible, app.playing, app.helpOpen) {
        // Stays up while arranging, so the lock is there to tap when done.
        if (app.chromeVisible && app.playing && !app.arranging && !app.helpOpen) {
            delay(CHROME_HIDE_MS)
            app.chromeVisible = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            app.syncTick()
            delay(500)
        }
    }
    LaunchedEffect(app.audioHintCount) {
        delay(AUDIO_HINT_MS)
        app.dismissAudioHint()
    }

    val cornerRadius = rememberScreenCornerRadius()
    val cutouts = rememberCutouts()
    // The controls and the feeds move together: one curve, one duration. While arranging the
    // feeds stay full size, so what you place is where it ends up.
    val motion = tween<Float>(Motion.SCREEN_MS, easing = EaseOut)
    // Laptop mode keeps the stream still: the controls lie over its top edge instead.
    val lift by animateFloatAsState(if (app.chromeVisible && !app.arranging && !app.laptopActive) 1f else 0f, motion, label = "lift")

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Ink.background)
            .pointerInput(Unit) { detectTapGestures(onTap = { app.toggleChrome() }) }
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val edgeInset = with(density) { 30.dp.toPx() }
        val band = with(density) { CONTROLS_BAND.toPx() }
        val narrow = with(density) { width.toDp() } < VOTE_SIDE_SHEET
        val compactWidth = with(density) { width.toDp() } < 600.dp
        // The vote sheet rises over the bottom of a narrow screen: the feeds glide up to the top
        // edge, clear of it, and back when it closes.
        val sheetShift by animateFloatAsState(
            if (app.voteSheetOpen && narrow) app.tiles.minOfOrNull { it.y } ?: 0f else 0f,
            motion,
            label = "voteSheet",
        )
        // On a wide one, the feeds shrink into the space left of it.
        val sheetSide by animateFloatAsState(if (app.voteSheetOpen && !narrow) 1f else 0f, motion, label = "voteSide")
        LaunchedEffect(app.layoutRequest) { app.layoutIfRequested(width, height) }
        LaunchedEffect(width, height) { app.onCanvasResized(width, height) }

        // Feeds and map, shrinking toward the bottom while the controls are up so nothing sits
        // under the top bar. Touches inside are in the unshrunk canvas's pixels.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = 1f - sheetSide * AppState.VOTE_SHEET_WIDTH
                    scaleX = k
                    scaleY = k
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .graphicsLayer {
                    val s = 1f - lift * band / height
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    translationY = -sheetShift
                }
                .mapGestures(app, density)
        ) {
            app.tiles.forEach { tile ->
                key(tile.id) { TileView(app, tile) }
            }

            // Laptop mode: the half below the hinge is the timing tower and the map. It rises in
            // as the feeds glide into place and sinks away when the phone opens flat, while the
            // usual map fades the other way; one curve, one duration, as the feeds.
            val laptop = app.laptopActive && app.hinge != null
            // The last hinge seen, so the panel still knows its place while it leaves.
            var lastHinge by remember { mutableStateOf(app.hinge) }
            app.hinge?.let { lastHinge = it }
            val swap = tween<Float>(Motion.SCREEN_MS, easing = EaseOut)
            val slide = tween<IntOffset>(Motion.SCREEN_MS, easing = EaseOut)
            AnimatedVisibility(
                laptop,
                Modifier.zIndex(1e9f),
                enter = fadeIn(swap) + slideInVertically(slide) { it / 2 },
                exit = fadeOut(swap) + slideOutVertically(slide) { it / 2 },
            ) {
                val top = lastHinge?.second ?: height
                Box(Modifier.fillMaxSize()) {
                    LaptopPanel(
                        app, app.timing,
                        Modifier
                            .offset { IntOffset(0, top.roundToInt()) }
                            .requiredSize(with(density) { width.toDp() }, with(density) { (height - top).coerceAtLeast(0f).toDp() }),
                    )
                    // Laptop mode's own map window: moved, resized and turned like the regular one.
                    app.timing?.takeIf { it.outline.size >= 8 }?.let { MapPanelView(app, it) }
                }
            }
            // The feeds' headers, above the map so its cars never cover a speaker or a menu.
            Box(Modifier.fillMaxSize().zIndex(1.5e9f)) {
                app.tiles.forEach { tile -> key(tile.id) { TileHeader(app, tile) } }
            }
            // Above every tile (their z grows by one per touch), below the controls.
            app.timing?.let { timing ->
                AnimatedVisibility(!laptop, Modifier.fillMaxSize().zIndex(1e9f), enter = fadeIn(swap), exit = fadeOut(swap)) {
                    when (app.mapMode) {
                        MapMode.TRACK -> Box(Modifier.fillMaxSize()) { MapPanelView(app, timing) }
                        MapMode.EDGE -> Box(Modifier.fillMaxSize()) {
                            EdgeMap(app, timing, width, height, edgeInset, cornerRadius)
                        }
                        MapMode.OFF -> Unit
                    }
                }
            }
        }

        // Over the feeds and the map, under the controls; it doesn't shrink with the feeds.
        if (app.radioOpen) app.timing?.let { timing ->
            Box(Modifier.zIndex(CHROME_Z - 1)) { RadioPanel(app, timing, width, height, feedScale = 1f - lift * band / height) }
        }

        val fade = motion
        val topBand = with(density) { 64.dp.toPx() }
        val bottomBand = with(density) { 110.dp.toPx() }
        AnimatedVisibility(
            app.chromeVisible,
            Modifier.zIndex(CHROME_Z).align(Alignment.TopCenter),
            enter = fadeIn(fade), exit = fadeOut(fade),
        ) { TopChrome(app, clearOf(cutouts, width, 0f, topBand)) }
        // The playback controls step aside while arranging, so the bottom feeds can be seen.
        AnimatedVisibility(
            app.chromeVisible && !app.arranging,
            Modifier.zIndex(CHROME_Z).align(Alignment.BottomCenter),
            enter = fadeIn(fade), exit = fadeOut(fade),
        ) { BottomChrome(app, clearOf(cutouts, width, height - bottomBand, height)) }

        Messages(app, Modifier.zIndex(CHROME_Z + 1).align(Alignment.Center))
        // In the corner, or centred where the screen is too narrow for a corner to mean much.
        VotePrompt(app, Modifier.zIndex(CHROME_Z + 1).align(if (compactWidth) Alignment.BottomCenter else Alignment.BottomEnd))

        // The explanation overlay, above everything. Shown once by itself, the first time.
        Box(Modifier.fillMaxSize().zIndex(CHROME_Z + 2)) {
            HelpOverlay(app, width, height, feedScale = 1f - lift * band / height, compact = with(density) { minOf(width, height).toDp() } < 600.dp)
        }
        LaunchedEffect(Unit) {
            if (!app.helpSeen) {
                delay(HELP_FIRST_DELAY_MS)
                if (app.tiles.isNotEmpty()) app.showHelp()
            }
        }
    }

    app.tileMenu?.let { TileMenuSheet(app, it) }
    if (app.mapSheetOpen) MapSheet(app)
    if (app.layoutSheetOpen) LayoutSheet(app)
    LaptopPrompt(app)
}

/**
 * While arranging, a finger on the track map's line moves the map, a second finger resizes and
 * turns it. Seen before the feeds see it (the Initial pass), and only claimed once it's a drag,
 * so a touch anywhere else in the map's box reaches the feed underneath.
 */
private fun Modifier.mapGestures(app: AppState, density: androidx.compose.ui.unit.Density): Modifier {
    val timing = app.timing
    // The track map window: the regular one in Track style, or laptop mode's own.
    val window = app.laptopActive || app.mapMode == MapMode.TRACK
    if (!app.arranging || app.mapLocked || !window || timing == null) return this
    // Keyed on the circuit, not the timing: live timing is replaced every couple of seconds, and
    // restarting this mid-gesture used to leave the map "grabbed", so feeds wouldn't move again.
    return pointerInput(timing.circuitKey, timing.outline.size, app.arranging, app.mapLocked, app.laptopActive) {
        val margin = mapMarginPx(density)
        val reach = with(density) { 22.dp.toPx() }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val now = app.timing ?: return@awaitEachGesture
            if (!mapLineTouched(app, now, down.position.x, down.position.y, margin, reach)) return@awaitEachGesture
            // From the first touch on the line, the feeds underneath ignore this finger; however
            // the gesture ends (lifted, cancelled, or this handler replaced), they listen again.
            app.mapGrabbed = true
            var dragging = false
            try {
                var travel = Offset.Zero
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    val turn = event.calculateRotation()
                    if (!dragging) {
                        travel += pan
                        dragging = travel.getDistance() > viewConfiguration.touchSlop || zoom != 1f || turn != 0f
                    }
                    if (dragging) {
                        app.arrangeMap(pan, zoom, turn)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            } finally {
                app.mapGrabbed = false
            }
            if (dragging) app.storeMapPanel()
        }
    }
}

@Composable
private fun Glyph(icon: ImageVector, description: String, size: Int = 24, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.pressable(scaleTo = 0.88f, onClick = onClick).size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Ink.primary, modifier = Modifier.size(size.dp))
    }
}

/** Padding that keeps a row's buttons out from under the camera, at least [base]. */
@Composable
private fun Modifier.clear(of: Clearance, base: androidx.compose.ui.unit.Dp): Modifier {
    val density = LocalDensity.current
    val gap = 6.dp
    return padding(
        start = maxOf(base, with(density) { of.start.toDp() } + if (of.start > 0f) gap else 0.dp),
        end = maxOf(base, with(density) { of.end.toDp() } + if (of.end > 0f) gap else 0.dp),
    )
}

@Composable
private fun TopChrome(app: AppState, cutout: Clearance) {
    Row(
        Modifier
            .fillMaxWidth()
            // Taller and darker than the bar itself, so the buttons read over any picture; the
            // feeds have already moved down out of the way.
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.92f),
                    0.55f to Color.Black.copy(alpha = 0.6f),
                    1f to Color.Transparent,
                )
            )
            .clear(cutout, 8.dp)
            .padding(top = 6.dp, bottom = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Icons.Rounded.Close, "Stop watching", modifier = Modifier.helpAnchor(app, "close"), onClick = app::stopAll)
        Text(
            app.playingTitle,
            style = Type.headline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Glyph(if (app.arranging) Icons.Rounded.LockOpen else Icons.Rounded.Lock, if (app.arranging) "Lock layout" else "Unlock to move and resize", modifier = Modifier.helpAnchor(app, "lock"), onClick = app::toggleArranging)
        Glyph(Icons.Rounded.Add, "Add a feed", modifier = Modifier.helpAnchor(app, "add")) { app.openPickerForPlaying() }
        Glyph(Icons.Rounded.GridView, "Layout: ${app.layoutMode.label}", modifier = Modifier.helpAnchor(app, "layout")) { app.layoutSheetOpen = true; app.showChrome() }
        if (app.timing != null) Glyph(Icons.Rounded.Headset, if (app.radioOpen) "Hide team radio" else "Team radio", modifier = Modifier.helpAnchor(app, "radio-button"), onClick = app::toggleRadio)
        if (app.timing != null || app.mapStatus != null) Glyph(Icons.Rounded.Map, "Track map", modifier = Modifier.helpAnchor(app, "map")) { app.mapSheetOpen = true }
        Glyph(Icons.Rounded.HelpOutline, "What everything does", 22, onClick = app::showHelp)
    }
}

@Composable
private fun BottomChrome(app: AppState, cutout: Clearance) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = app.durationMs
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))
            .clear(cutout, 20.dp)
            .padding(top = 24.dp, bottom = 8.dp)
    ) {
        // Live streams scrub too, across however much of the broadcast F1 keeps (its rewind
        // window); the right end is the live edge.
        if (duration > 0) {
            // The flags sit on the line itself, under the thumb.
            Box(Modifier.helpAnchor(app, "scrubber"), contentAlignment = Alignment.CenterStart) {
                ScrubberFlags(app, duration)
                AppleSlider(
                    value = scrubbing ?: (app.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { scrubbing = it; app.showChrome() },
                    onValueChangeFinished = {
                        scrubbing?.let { app.seekTo((it * duration).toLong()) }
                        scrubbing = null
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shown = scrubbing?.let { (it * duration).toLong() } ?: app.positionMs
            val behind = (duration - shown).coerceAtLeast(0)
            when {
                // Within a few seconds of the edge counts as live: the player itself sits a little back.
                app.isLive && (duration <= 0 || behind < LIVE_EDGE_MS) -> LiveMark()
                app.isLive -> Text("−" + clock(behind) + " behind live", style = Type.footnote.copy(color = Ink.primary))
                else -> Text(clock(shown), style = Type.footnote.copy(color = Ink.primary))
            }
            Spacer(Modifier.weight(1f))
            Glyph(Icons.Rounded.Replay10, "Back 10 seconds", 28) { app.skip(-10_000) }
            Spacer(Modifier.width(12.dp))
            Glyph(if (app.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (app.playing) "Pause" else "Play", 36, onClick = app::togglePlay)
            Spacer(Modifier.width(12.dp))
            Glyph(Icons.Rounded.Forward10, "Forward 10 seconds", 28) { app.skip(10_000) }
            Spacer(Modifier.weight(1f))
            if (app.isLive) {
                Text("Go live", style = Type.footnote.copy(color = Ink.primary), modifier = Modifier.pressable(onClick = app::goLive).padding(8.dp))
            } else {
                Text("−" + clock((duration - shown).coerceAtLeast(0)), style = Type.footnote.copy(color = Ink.primary))
            }
            Spacer(Modifier.width(8.dp))
            Glyph(if (app.mutedAll) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (app.mutedAll) "Unmute" else "Mute all", modifier = Modifier.helpAnchor(app, "mute"), onClick = app::toggleMuteAll)
        }
    }
}

/**
 * Chequered marks on the scrubber's line at lights out (the session going green, outside races)
 * and at the chequered flag. Drawn under the slider, so dragging works over them as anywhere.
 */
@Composable
private fun ScrubberFlags(app: AppState, duration: Long) {
    val flags = app.flagPositions()
    if (flags.isEmpty()) return
    // The slider's track starts and ends half a thumb in from its edges.
    Canvas(Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 11.dp)) {
        val cell = size.height / 3
        flags.forEach { at ->
            val x = size.width * (at.toFloat() / duration)
            val left = x - cell
            drawRect(Color.Black, Offset(left - 1f, -1f), androidx.compose.ui.geometry.Size(cell * 2 + 2f, size.height + 2f))
            for (col in 0 until 2) for (row in 0 until 3) {
                drawRect(
                    if ((col + row) % 2 == 0) Color.White else Color.Black,
                    Offset(left + col * cell, row * cell),
                    androidx.compose.ui.geometry.Size(cell, cell),
                )
            }
        }
    }
}

/** One status line in the middle, for things in progress or gone wrong. */
@Composable
private fun Messages(app: AppState, modifier: Modifier) {
    val text = app.message ?: return
    LaunchedEffect(text) {
        if (!text.endsWith("...")) {
            delay(6_000)
            app.dismissMessage()
        }
    }
    Text(
        text.removeSuffix("...").let { if (text.endsWith("...")) "$it…" else it },
        style = Type.subhead.copy(color = Ink.primary),
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .pressable(onClick = app::dismissMessage)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private val overlayShadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)

/**
 * A feed. Tap shows or hides the controls (and, with a map, marks it as the feed the next car
 * picked replaces), double-tap brings it into the big slot, long-press opens its menu. While
 * arranging, one finger drags and two resize. With the controls up, the header shows the feed's
 * name (tap for its menu), a speaker that moves the audio here, and on the small feeds a close.
 */
@Composable
private fun TileView(app: AppState, tile: Tile) {
    val density = LocalDensity.current
    LaunchedEffect(tile.id) {
        while (true) {
            tile.feed.refresh()
            delay(1000)
        }
    }

    Box(
        Modifier
            .offset { IntOffset(tile.x.roundToInt(), tile.y.roundToInt()) }
            .requiredSize(with(density) { tile.w.toDp() }, with(density) { tile.h.toDp() })
            .zIndex(tile.z)
            .helpAnchor(app, "tile:${tile.id}")
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { context ->
                val layout = if (app.settings.softwareDrm) R.layout.player_texture else R.layout.player_surface
                LayoutInflater.from(context).inflate(layout, null) as PlayerView
            },
            // Runs again when the tile's feed is switched.
            update = { it.player = tile.feed.player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(tile.id) {
                    detectTapGestures(
                        onPress = { app.raise(tile) },
                        onTap = { app.toggleChrome(); app.targetForMap(tile) },
                        onDoubleTap = { app.doubleTap(tile) },
                        onLongPress = { app.openTileMenu(tile); app.showChrome() },
                    )
                }
                .then(if (app.arranging) Modifier.arrangeGestures(app, tile) else Modifier)
        )

        // Tapped as the feed the next car picked on the map replaces: a soft light runs round it.
        val targeted = app.targetTile === tile
        LaunchedEffect(app.targetCount, targeted) {
            if (targeted) {
                delay(TARGET_MS)
                app.releaseTarget(tile)
            }
        }
        TargetGlow(targeted)

        // The broadcast-style timing card on onboards.
        val number = tile.channel.racingNumber?.toString()
        val timing = app.timing
        val tileWidth = with(density) { tile.w.toDp() }
        if (number != null && timing != null && timing.laps.isNotEmpty() && tileWidth >= CARD_MIN_TILE) {
            DriverCard(
                app, timing, number, tile.channel.driverName,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = CARD_MARGIN, bottom = if (app.settings.showStats) 28.dp else CARD_MARGIN)
                    .width(minOf(CARD_MAX_WIDTH, tileWidth - CARD_MARGIN * 2)),
            )
        }

        val fade = tween<Float>(Motion.SCREEN_MS, easing = EaseOut)
        AnimatedVisibility(
            app.audioHint === tile,
            Modifier.align(Alignment.BottomEnd).padding(end = 52.dp, bottom = 10.dp),
            enter = fadeIn(fade), exit = fadeOut(tween(600, easing = EaseOut)),
        ) {
            Row(
                Modifier
                    .pressable { app.setAudio(tile, byUser = true); app.dismissAudioHint() }
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.VolumeUp, null, tint = Ink.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Listen to ${tile.channel.driverName ?: tile.channel.title}", style = Type.footnote.copy(color = Ink.primary))
            }
        }

        if (tile.coolingOff) {
            Text(
                "Video paused to let the phone cool down",
                style = Type.subhead.copy(color = Ink.primary, shadow = overlayShadow, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp).widthIn(max = 420.dp),
            )
        }

        if (tile.feed.problem == null && !tile.coolingOff) {
            BufferingRing(tile.feed, Modifier.align(Alignment.Center))
        }

        tile.feed.problem?.let { problem ->
            Text(
                problem,
                style = Type.subhead.copy(color = Ink.primary, shadow = overlayShadow, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp).widthIn(max = 420.dp),
            )
        }

        if (app.settings.showStats) {
            Text(
                tile.feed.status,
                style = Type.caption.copy(color = Ink.primary, shadow = overlayShadow),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 48.dp, bottom = 8.dp),
            )
        }

        AnimatedVisibility(app.chromeVisible && app.arranging, Modifier.align(Alignment.BottomEnd), enter = fadeIn(fade), exit = fadeOut(fade)) {
            Box(
                Modifier
                    .size(44.dp)
                    .pointerInput(tile.id) {
                        detectDragGestures(
                            onDragStart = { app.beginArrange(tile) },
                            onDragEnd = app::endArrange,
                            onDragCancel = app::endArrange,
                        ) { change, drag ->
                            change.consume()
                            // A diagonal drag counts both ways; the tile keeps 16:9.
                            app.arrangeBy(tile, 0f, 0f, (drag.x + drag.y * 16f / 9f) / 2f)
                        }
                    }
            ) {
                Canvas(Modifier.align(Alignment.Center).size(14.dp)) {
                    val stroke = 2.dp.toPx()
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), stroke)
                    drawLine(Color.White, Offset(size.width, size.height / 2), Offset(size.width / 2, size.height), stroke)
                }
            }
        }
    }
}

/**
 * While arranging: one finger moves the tile, two resize it. Starts past the touch slop so taps
 * and double-taps still work, and stops if the map claimed the touch first.
 */
private fun Modifier.arrangeGestures(app: AppState, tile: Tile) = pointerInput(tile.id) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var moving = false
        var travel = Offset.Zero
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            if (!moving) {
                travel += pan
                if (travel.getDistance() > viewConfiguration.touchSlop || zoom != 1f) {
                    moving = true
                    app.beginArrange(tile)
                }
            }
            if (moving) {
                app.arrangeBy(tile, pan.x, pan.y, if (zoom != 1f) tile.w * (zoom - 1f) else 0f)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (moving) app.endArrange()
    }
}

/** A light that runs round the tile's border, fading in, and out when [on] ends. */
@Composable
private fun TargetGlow(on: Boolean) {
    val alpha by animateFloatAsState(if (on) 1f else 0f, tween(Motion.SCREEN_MS * 2, easing = EaseOut), label = "glow")
    if (alpha == 0f) return
    val spin = rememberInfiniteTransition(label = "glow-spin")
    val angle by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2_400, easing = androidx.compose.animation.core.LinearEasing)),
        label = "glow-angle",
    )
    Canvas(Modifier.fillMaxSize()) {
        // A sweep gradient can't be turned, so the bright stretch is moved round by building the
        // gradient's stops from the angle: bright at the angle, fading to nothing opposite it.
        val sweep = Brush.sweepGradient(
            colors = List(GLOW_STOPS + 1) { i ->
                val f = (i / GLOW_STOPS.toFloat() - angle / 360f).mod(1f)
                val d = minOf(f, 1f - f)
                GLOW.copy(alpha = (1f - d * 3f).coerceIn(0f, 1f))
            },
            center = center,
        )
        val inset = 2.dp.toPx()
        val at = Offset(inset, inset)
        val frame = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
        drawRect(GLOW.copy(alpha = 0.3f), at, frame, alpha = alpha, style = Stroke(1.5.dp.toPx()))
        drawRect(sweep, at, frame, alpha = 0.35f * alpha, style = Stroke(9.dp.toPx()))
        drawRect(sweep, at, frame, alpha = alpha, style = Stroke(2.5.dp.toPx()))
    }
}

/**
 * A feed's header (its name for the menu, the speaker, and on small feeds a close), drawn from a
 * layer above the track map: the edge ribbon runs across the top of the screen, and its cars'
 * tap targets used to sit over the main feed's speaker.
 */
@Composable
private fun TileHeader(app: AppState, tile: Tile) {
    val density = LocalDensity.current
    val fade = tween<Float>(Motion.SCREEN_MS, easing = EaseOut)
    Box(
        Modifier
            .offset { IntOffset(tile.x.roundToInt(), tile.y.roundToInt()) }
            .width(with(density) { tile.w.toDp() })
            .zIndex(tile.z)
    ) {
        AnimatedVisibility(app.chromeVisible, Modifier.fillMaxWidth(), enter = fadeIn(fade), exit = fadeOut(fade)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent))
                    .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f, fill = false).pressable { app.openTileMenu(tile); app.showChrome() }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tile.channel.teamColor?.let {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(it)))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(tile.channel.title, style = Type.footnote.copy(color = Ink.primary, shadow = overlayShadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Ink.primary, modifier = Modifier.size(18.dp))
                    if (tile.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = Ink.primary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
                Spacer(Modifier.weight(1f))
                val hearing = tile.audible && !app.mutedAll
                Glyph(if (hearing) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, if (hearing) "Playing audio" else "Play audio from this feed", 20) {
                    app.setAudio(tile, byUser = true)
                    app.showChrome()
                }
                // The small feeds close from here; the rest close up to fill the space.
                if (app.tiles.size > 1 && !app.isMainTile(tile)) {
                    Glyph(Icons.Rounded.Close, "Close ${tile.channel.title}", 18) { app.remove(tile) }
                }
            }
        }
    }
}

/** One feed to switch to, by name; "Now" on the one playing. */
@Composable
private fun ChannelRow(app: AppState, tile: Tile, channel: Channel) {
    val current = channel == tile.channel
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.99f) { app.chooseFeed(tile, channel) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(channel.teamColor?.let { Color(it) } ?: Color.Transparent))
        Spacer(Modifier.width(12.dp))
        Text(
            channel.title,
            style = Type.body.copy(color = if (current) Ink.secondary else Ink.primary),
            modifier = Modifier.weight(1f),
        )
        if (current) Text("Now", style = Type.footnote)
    }
}

/** A tile's actions and its session's other feeds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileMenuSheet(app: AppState, tile: Tile) {
    ModalBottomSheet(
        onDismissRequest = app::closeTileMenu,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        KeepSystemBarsHidden()
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(tile.channel.title, style = Type.title2)
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!tile.audible) SecondaryButton("Play its audio") { app.setAudio(tile); app.closeTileMenu() }
                SecondaryButton(if (tile.pinned) "Unpin" else "Pin") { app.togglePin(tile); app.closeTileMenu() }
                SecondaryButton("Remove") { app.remove(tile) }
            }
            Text("Switch to", style = Type.headline, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            // Broadcast feeds as they come; onboards in running order with the tower's stats,
            // when there's timing for the session. Any without timing follow in F1's order.
            val timing = app.timing
            val now = remember { app.mapTimeMs() ?: 0L }
            val channels = app.channelsFor(tile)
            val onboards = channels.filter { it.racingNumber != null }
            val ranked = if (timing == null) emptyList() else onboards.mapNotNull { c ->
                val number = c.racingNumber.toString()
                timing.laps[number]?.at(now)?.let { Triple(c, number, it) }
            }.sortedBy { it.third.position ?: 99 }
            val plain = channels.filter { c -> ranked.none { it.first == c } }
            plain.filter { it.racingNumber == null }.forEach { channel ->
                ChannelRow(app, tile, channel)
            }
            if (timing != null && ranked.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                TowerHeader(timing)
                ranked.forEach { (channel, number, state) ->
                    TowerRow(timing, number, state, timing.tyres[number]?.at(now), highlight = channel == tile.channel) {
                        app.chooseFeed(tile, channel)
                    }
                }
            }
            plain.filter { it.racingNumber != null }.forEach { channel -> ChannelRow(app, tile, channel) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * A bottom sheet is its own window, which brings the status bar back over the viewer. Inside a
 * sheet opened over the viewer, this hides it again, the way the viewer itself does.
 */
@Composable
internal fun KeepSystemBarsHidden(enabled: Boolean = true) {
    val view = LocalView.current
    LaunchedEffect(view, enabled) {
        if (!enabled) return@LaunchedEffect
        var parent = view.parent
        while (parent != null && parent !is androidx.compose.ui.window.DialogWindowProvider) parent = parent.parent
        val window = (parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@LaunchedEffect
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** A row of choices, one picked, in the style of iOS's segmented control. */
@Composable
private fun <T> Segments(options: List<T>, chosen: T, label: (T) -> String, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(Ink.background).padding(3.dp)) {
        options.forEach { option ->
            val on = option == chosen
            Box(
                Modifier
                    .pressable { onPick(option) }
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Ink.surfaceHigh else Color.Transparent)
                    .padding(horizontal = 22.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = Type.subhead.copy(color = if (on) Ink.primary else Ink.secondary))
            }
        }
    }
}

/** Built-in layouts, the saved one, saving the current arrangement, and Snap or Free arranging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutSheet(app: AppState) {
    ModalBottomSheet(
        onDismissRequest = { app.layoutSheetOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        KeepSystemBarsHidden()
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Layout", style = Type.title2)
            val n = app.tiles.size
            val hasSaved = app.savedLayout(n) != null
            val modes = LayoutMode.entries.filter { it != LayoutMode.CUSTOM || hasSaved }
            Segments(modes, app.layoutMode, { it.label }, Modifier.padding(top = 20.dp)) { app.chooseLayout(it) }
            Text(
                if (hasSaved) "My layout is the one you saved for $n feed${if (n == 1) "" else "s"} with the phone ${app.wayUp}."
                else "Arrange the feeds the way you like, then save it. Upright and sideways each keep their own.",
                style = Type.footnote,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 16.dp)) {
                SecondaryButton("Save this layout") { app.saveLayoutAsDefault(); app.layoutSheetOpen = false }
            }
            Text(
                "Every stream with $n feed${if (n == 1) "" else "s"} will start like this, the map included. Main + strip and Grid stay as they are.",
                style = Type.footnote,
                modifier = Modifier.padding(top = 8.dp),
            )

            Column(Modifier.padding(top = 20.dp).clip(RoundedCornerShape(12.dp)).background(Ink.background).padding(vertical = 4.dp)) {
                SwitchRow("Laptop mode when half-folded", app.laptopEnabled, app::chooseLaptop)
            }
            Text(
                "Open the phone partway, like a laptop, and the stream takes the top half while the timing tower and track map fill the bottom.",
                style = Type.footnote,
                modifier = Modifier.padding(top = 8.dp),
            )

            Text("Arranging", style = Type.headline, modifier = Modifier.padding(top = 24.dp))
            Segments(listOf(true, false), app.snapMode, { if (it) "Snap" else "Free" }, Modifier.padding(top = 10.dp)) { app.chooseSnap(it) }
            Text(
                if (app.snapMode) "Edges pull together and feeds don't overlap; push hard to go over one. Growing a feed pushes the others into open space."
                else "Feeds go exactly where you put them, over each other if you like.",
                style = Type.footnote,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Map style, opacity and timing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapSheet(app: AppState) {
    ModalBottomSheet(
        onDismissRequest = { app.mapSheetOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        KeepSystemBarsHidden()
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Track map", style = Type.title2)
            app.mapStatus?.let { Text(it, style = Type.footnote, modifier = Modifier.padding(top = 4.dp)) }

            Row(
                Modifier.padding(top = 20.dp).clip(RoundedCornerShape(12.dp)).background(Ink.background).padding(3.dp)
            ) {
                MapMode.entries.forEach { mode ->
                    val chosen = app.mapMode == mode
                    Box(
                        Modifier
                            .pressable { app.chooseMapMode(mode) }
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (chosen) Ink.surfaceHigh else Color.Transparent)
                            .padding(horizontal = 22.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(mode.label, style = Type.subhead.copy(color = if (chosen) Ink.primary else Ink.secondary))
                    }
                }
            }
            Text(
                when (app.mapMode) {
                    MapMode.TRACK -> "The circuit over the feeds. Unlock the layout to drag it by the track line or its move grip, pinch or twist it. Tap a feed, then a car, to swap in that car's onboard."
                    MapMode.EDGE -> "The lap laid around the edge of the screen, the finish at the top. Tap a feed, then a car, to swap in that car's onboard."
                    MapMode.OFF -> "No map."
                },
                style = Type.footnote,
                modifier = Modifier.padding(top = 10.dp),
            )

            if (app.mapMode != MapMode.OFF) {
                Text("Opacity", style = Type.headline, modifier = Modifier.padding(top = 24.dp))
                AppleSlider(
                    value = app.mapOpacity,
                    onValueChange = app::updateMapOpacity,
                    valueRange = 0.2f..1f,
                    onValueChangeFinished = app::storeMapPanel,
                    trackColor = Ink.surfaceHigh,
                )
                if (app.mapMode == MapMode.TRACK) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Turn", style = Type.headline)
                            Text("%.0f°".format(app.activeMapPanel.rotation), style = Type.footnote)
                        }
                        listOf("↺" to -15f, "↻" to 15f).forEach { (label, step) ->
                            Text(
                                label,
                                style = Type.title2,
                                modifier = Modifier.pressable { app.rotateMap(step) }.padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                        Text(
                            "Reset",
                            style = Type.headline,
                            modifier = Modifier.pressable(onClick = app::resetMapRotation).padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Timing", style = Type.headline)
                        Text(
                            "%+.0f s".format(app.mapOffsetMs / 1000f) +
                                if (app.mapClockEstimated) " · estimated, nudge until the dots match" else " · remembered",
                            style = Type.footnote,
                        )
                    }
                    listOf(-1_000L, 1_000L).forEach { step ->
                        Text(
                            "%+d s".format(step / 1000),
                            style = Type.headline,
                            modifier = Modifier.pressable { app.nudgeMapOffset(step) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                if (app.mapMode == MapMode.TRACK) {
                    Column(Modifier.padding(top = 16.dp).clip(RoundedCornerShape(12.dp)).background(Ink.background).padding(vertical = 4.dp)) {
                        SwitchRow("Lock the map in place", app.mapLocked) { app.toggleMapLock() }
                        SwitchRow("Keep feeds clear of the map", app.mapReserve, app::chooseMapReserve)
                    }
                    Text(
                        "Locked, the map stays put while you arrange the feeds under it. Kept clear, the feeds make room for it instead of lying under it.",
                        style = Type.footnote,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** How long a feed waits on the stream before the ring shows: the quick skips that line feeds up stay unmarked. */
private const val BUFFERING_SHOW_MS = 600L

/** A thin spinning ring while [feed] waits on the stream for longer than a moment. */
@Composable
private fun BufferingRing(feed: FeedPlayer, modifier: Modifier) {
    var shown by remember(feed) { mutableStateOf(false) }
    LaunchedEffect(feed, feed.buffering) {
        if (feed.buffering) {
            delay(BUFFERING_SHOW_MS)
            shown = true
        } else {
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(Motion.SCREEN_MS, easing = EaseOut), label = "buffering")
    if (alpha > 0f) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = modifier.size(36.dp).graphicsLayer { this.alpha = alpha },
            color = Ink.primary,
            // A faint dark track, so the ring still reads over a bright sky.
            trackColor = Color.Black.copy(alpha = 0.3f),
            strokeWidth = 2.5.dp,
        )
    }
}

private const val VOTE_PROMPT_MS = 60_000L
/** Below this width Chrome's vote sheet comes up from the bottom; at or above, in from the side. */
private val VOTE_SIDE_SHEET = 840.dp

/**
 * Near the end of a live Grand Prix: a note in the corner offering F1's Driver of the Day vote.
 * Nothing stops for it, and it goes by itself after a minute if left alone.
 */
@Composable
private fun VotePrompt(app: AppState, modifier: Modifier) {
    val activity = LocalContext.current as Activity
    LaunchedEffect(app.votePromptOpen) {
        if (app.votePromptOpen) {
            delay(VOTE_PROMPT_MS)
            app.dismissVotePrompt()
        }
    }
    val fade = tween<Float>(Motion.SCREEN_MS, easing = EaseOut)
    val slide = tween<IntOffset>(Motion.SCREEN_MS, easing = EaseOut)
    // Clear of the playback controls while they're up.
    val lift by animateDpAsState(
        if (app.chromeVisible && !app.arranging) 110.dp else 0.dp,
        tween(Motion.SCREEN_MS, easing = EaseOut),
        label = "voteLift",
    )
    AnimatedVisibility(
        app.votePromptOpen && !app.inPip,
        modifier.padding(bottom = lift),
        enter = fadeIn(fade) + slideInVertically(slide) { it / 3 },
        exit = fadeOut(fade) + slideOutVertically(slide) { it / 3 },
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(20.dp)
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ink.surface)
                // A tap on the note itself shouldn't bring up the controls behind it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp)
        ) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Driver of the Day", style = Type.headline)
                    Text(
                        "Cast your vote on F1's site. The race keeps playing beside it while you do.",
                        style = Type.subhead,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // Into the card's corner: the glyph's touch area is wider than its mark.
                Glyph(Icons.Rounded.Close, "Not now", size = 20, modifier = Modifier.offset(12.dp, (-12).dp)) {
                    app.dismissVotePrompt()
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Vote") { app.vote(activity) }
                // Only the first time; after that it's in Settings.
                if (app.votePromptFirst) SecondaryButton("Disable this popup") { app.dismissVotePrompt(forGood = true) }
            }
        }
    }
}
