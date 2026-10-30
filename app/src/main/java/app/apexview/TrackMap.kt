package app.apexview

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Maps archive coordinates onto a box. The track is turned to whichever angle lets it fill the
 * box the most, and y is flipped (the archive's y points up).
 */
class MapFit private constructor(
    private val cos: Float,
    private val sin: Float,
    private val scale: Float,
    private val left: Float,
    private val top: Float,
    private val minX: Float,
    private val maxY: Float,
) {
    fun map(x: Float, y: Float): Offset {
        val rx = x * cos - y * sin
        val ry = x * sin + y * cos
        return Offset(left + (rx - minX) * scale, top + (maxY - ry) * scale)
    }

    companion object {
        /**
         * The fit at whichever angle fills the box best, then turned a further [turn] degrees (the
         * user's own rotation), still fitted to the box.
         */
        fun of(outline: FloatArray, width: Float, height: Float, margin: Float, turn: Float = 0f): MapFit? {
            if (outline.size < 4) return null
            var best: MapFit? = null
            var bestDegrees = 0
            for (degrees in 0 until 180 step 5) {
                val fit = at(outline, width, height, margin, degrees.toFloat())
                if (best == null || fit.scale > best.scale) {
                    best = fit
                    bestDegrees = degrees
                }
            }
            return if (turn == 0f) best else at(outline, width, height, margin, bestDegrees + turn)
        }

        private fun at(outline: FloatArray, width: Float, height: Float, margin: Float, degrees: Float): MapFit {
            val w = width - 2 * margin
            val h = height - 2 * margin
            val a = Math.toRadians(degrees.toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in outline.indices step 2) {
                val rx = outline[i] * c - outline[i + 1] * s
                val ry = outline[i] * s + outline[i + 1] * c
                minX = min(minX, rx); maxX = maxOf(maxX, rx)
                minY = min(minY, ry); maxY = maxOf(maxY, ry)
            }
            val scale = min(w / (maxX - minX), h / (maxY - minY))
            return MapFit(
                c, s, scale,
                left = margin + (w - (maxX - minX) * scale) / 2,
                top = margin + (h - (maxY - minY) * scale) / 2,
                minX = minX, maxY = maxY,
            )
        }
    }

    /** The drawn track's bounding box, in the box's pixels. */
    fun bounds(outline: FloatArray): androidx.compose.ui.geometry.Rect {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in outline.indices step 2) {
            val p = map(outline[i], outline[i + 1])
            l = min(l, p.x); r = maxOf(r, p.x)
            t = min(t, p.y); b = maxOf(b, p.y)
        }
        return androidx.compose.ui.geometry.Rect(l, t, r, b)
    }

    /** True when ([px], [py]), in the box's pixels, is within [reach] of the drawn track line. */
    fun nearLine(outline: FloatArray, px: Float, py: Float, reach: Float): Boolean {
        val n = outline.size / 2
        for (i in 0 until n) {
            val a = map(outline[i * 2], outline[i * 2 + 1])
            val j = (i + 1) % n
            val b = map(outline[j * 2], outline[j * 2 + 1])
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val k = if (len2 == 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
            if (hypot(a.x + dx * k - px, a.y + dy * k - py) <= reach) return true
        }
        return false
    }
}

private val PUSH_RING = Color(0xFFFFD400)

private class Car(val number: String, val x: Float, val y: Float, val state: LapState)

/** The session time the map shows, updated every frame from the leading tile's clock. */
@Composable
internal fun rememberMapTime(app: AppState): Long {
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { now = app.mapTimeMs() ?: 0L }
    }
    return now
}

private fun carsAt(timing: SessionTiming, now: Long) = timing.tracks.mapNotNull { (number, track) ->
    track.atOrFirst(now)?.let { (x, y) -> Car(number, x, y, timing.states[number]?.at(now) ?: LapState.UNKNOWN) }
}

private fun TextMeasurer.label(text: String, size: TextUnit, bold: Boolean = true) =
    measure(text, TextStyle(color = Color.White, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))

/** Team-colored dot: a yellow ring on a flying lap in qualifying, small and faded in the pits. */
private fun DrawScope.drawCar(timing: SessionTiming, car: Car, at: Offset, baseRadius: Float): Float {
    val color = Color(timing.drivers[car.number]?.color ?: android.graphics.Color.WHITE)
    val dim = car.state == LapState.PIT || car.state == LapState.KNOCKED_OUT
    val push = timing.showsPushLaps && car.state == LapState.PUSH
    val radius = when {
        push -> baseRadius * 1.3f
        dim -> baseRadius * 0.7f
        else -> baseRadius
    }
    val alpha = if (dim) 0.4f else 1f
    if (push) drawCircle(PUSH_RING, radius + 3.dp.toPx(), at)
    drawCircle(Color.Black.copy(alpha = alpha), radius + 1.5.dp.toPx(), at)
    drawCircle(color.copy(alpha = alpha), radius, at)
    return alpha
}

/** Invisible 44dp tap targets that follow the dots. Tapping one shows that car's onboard. */
@Composable
private fun CarTargets(app: AppState, positions: List<Pair<String, Offset>>) {
    val target = 44.dp
    val half = with(LocalDensity.current) { target.toPx() / 2 }
    positions.forEach { (number, p) ->
        Box(
            Modifier
                .offset { IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt()) }
                .size(target)
                .clickable { app.showOnboard(number) }
        )
    }
}

private val MAP_MARGIN = 22.dp
/** START and FINISH labels sit as far in from the ribbon as the first row of car names. */
private const val MARK_LABEL_DP = 15

/**
 * True when a touch at ([x], [y]) on the canvas lands on the track map's line (within [reach]),
 * the only part of the map that can be grabbed, so feeds under the rest of its box stay usable.
 */
fun mapLineTouched(app: AppState, timing: SessionTiming, x: Float, y: Float, margin: Float, reach: Float): Boolean {
    val panel = app.activeMapPanel
    if (x < panel.x - reach || y < panel.y - reach || x > panel.x + panel.w + reach || y > panel.y + panel.h + reach) return false
    val fit = MapFit.of(timing.outline, panel.w, panel.h, margin, panel.rotation) ?: return false
    return fit.nearLine(timing.outline, x - panel.x, y - panel.y, reach)
}

fun mapMarginPx(density: androidx.compose.ui.unit.Density) = with(density) { MAP_MARGIN.toPx() }

/**
 * The track map in its own window. While arranging it's moved, resized and turned by its track
 * line (see [mapLineTouched]) or by the move grip; the corner handle resizes it and the lock keeps
 * it still while the feeds under it are arranged. The controls sit at the corners of the track
 * itself, not of the window, which can be much bigger than the track and run off the screen.
 */
@Composable
fun MapPanelView(app: AppState, timing: SessionTiming) {
    val panel = app.activeMapPanel
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(panel.x.roundToInt(), panel.y.roundToInt()) }
            // requiredSize, not size: size() is capped at the screen width, while the track is
            // fitted to the full panel, so a panel wider than the screen lost its right side.
            .requiredSize(with(density) { panel.w.toDp() }, with(density) { panel.h.toDp() })
    ) {
        TrackDrawing(app, timing, panel.w, panel.h, panel.rotation)
        if (app.arranging) MapControls(app, timing)
    }
}

/** The track map filling whatever box it's given (laptop mode's lower half). */
@Composable
fun TrackMapFill(app: AppState, timing: SessionTiming) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        TrackDrawing(app, timing, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), 0f)
    }
}

/** Lock, move grip and resize handle, at the corners of the drawn track. */
@Composable
private fun MapControls(app: AppState, timing: SessionTiming) {
    val panel = app.activeMapPanel
    val density = LocalDensity.current
    val bounds = remember(timing, panel.w, panel.h, panel.rotation) {
        MapFit.of(timing.outline, panel.w, panel.h, mapMarginPx(density), panel.rotation)?.bounds(timing.outline)
    } ?: return
    Box(Modifier.fillMaxSize()) {
        val control = with(density) { 44.dp.toPx() }
        // Each control stays on screen even if that corner of the track is off it.
        val (screenW, screenH) = app.canvasSize
        fun at(x: Float, y: Float) = Modifier.offset {
            val cx = (panel.x + x).coerceIn(control / 2, (screenW - control / 2).coerceAtLeast(control / 2)) - panel.x
            val cy = (panel.y + y).coerceIn(control / 2, (screenH - control / 2).coerceAtLeast(control / 2)) - panel.y
            IntOffset((cx - control / 2).roundToInt(), (cy - control / 2).roundToInt())
        }

        // Top-right of the track: lock, and the move grip beside it.
        MapControl(at(bounds.right, bounds.top).clickable(onClick = app::toggleMapLock)) {
            Icon(
                if (app.mapLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                if (app.mapLocked) "Unlock the map" else "Lock the map",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        if (!app.mapLocked) MapControl(
            at(bounds.right - control, bounds.top).pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { app.mapGrabbed = true },
                    onDragEnd = { app.mapGrabbed = false; app.storeMapPanel() },
                    onDragCancel = { app.mapGrabbed = false },
                ) { change, drag ->
                    change.consume()
                    app.arrangeMap(drag, 1f, 0f)
                }
            }
        ) {
            Icon(Icons.Rounded.OpenWith, "Move the map", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        // Bottom-right of the track: resize, keeping the track's shape and centre.
        if (!app.mapLocked) MapControl(
            at(bounds.right, bounds.bottom).pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { app.mapGrabbed = true },
                    onDragEnd = { app.mapGrabbed = false; app.storeMapPanel() },
                    onDragCancel = { app.mapGrabbed = false },
                ) { change, drag ->
                    change.consume()
                    // Read live: the gesture outlives each size it passes through.
                    val span = (panel.w + panel.h).coerceAtLeast(1f)
                    app.arrangeMap(Offset.Zero, 1f + (drag.x + drag.y) / span, 0f)
                }
            }
        ) {
            Canvas(Modifier.size(14.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), stroke)
                drawLine(Color.White, Offset(size.width, size.height / 2), Offset(size.width / 2, size.height), stroke)
            }
        }
    }
}

/** A 44dp target with a small dark disc behind its icon, so it reads over any picture. */
@Composable
private fun MapControl(modifier: Modifier, icon: @Composable () -> Unit) {
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(30.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) { icon() }
    }
}

/**
 * A line across the track at [p]: [across] runs over the track, [along] with it. The finish is
 * chequered, the start plain white.
 */
private fun DrawScope.drawLineMark(p: Offset, across: Offset, along: Offset, half: Float, chequered: Boolean) {
    if (!chequered) {
        drawLine(Color.Black, p - across * (half + 1.5f), p + across * (half + 1.5f), 5.dp.toPx())
        drawLine(Color.White, p - across * half, p + across * half, 3.dp.toPx())
        return
    }
    val s = 3.dp.toPx()
    val count = (2 * half / s).roundToInt().coerceAtLeast(2)
    val start = p - across * (count * s / 2) - along * s
    for (i in 0 until count) for (row in 0..1) {
        val corner = start + across * (i * s) + along * (row * s)
        val square = Path().apply {
            moveTo(corner.x, corner.y)
            (corner + across * s).let { lineTo(it.x, it.y) }
            (corner + across * s + along * s).let { lineTo(it.x, it.y) }
            (corner + along * s).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(square, if ((i + row) % 2 == 0) Color.White else Color.Black)
    }
}

/** Start and finish as fractions of a lap, merged into one when they're within 15 m. */
private class LineMarks(timing: SessionTiming) {
    val start = timing.startLine?.takeIf { timing.hasStartFinish }
    val together = start != null && minOf(start, 1f - start) * timing.geometry.length < 150f
    val finishLabel = if (together) "START · FINISH" else "FINISH"
}

/** Circuit outline, start and finish, turn numbers and the cars, fitted into a w x h box. */
@Composable
private fun TrackDrawing(app: AppState, timing: SessionTiming, width: Float, height: Float, turn: Float) {
    val density = LocalDensity.current
    val fit = remember(timing, width, height, turn) {
        MapFit.of(timing.outline, width, height, margin = mapMarginPx(density), turn = turn)
    } ?: return
    val path = remember(fit) {
        Path().apply {
            val o = timing.outline
            for (i in o.indices step 2) {
                val p = fit.map(o[i], o[i + 1])
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
    }
    val measurer = rememberTextMeasurer()
    val names = remember(timing) { timing.drivers.mapValues { measurer.label(it.value.tla, 11.sp) } }
    val turns = remember(timing) { timing.corners.associate { it.number to measurer.label("${it.number}", 9.sp, bold = false) } }
    val marks = remember(timing, timing.startLine) { LineMarks(timing) }
    val finishLabel = remember(marks) { measurer.label(marks.finishLabel, 8.sp) }
    val startLabel = remember { measurer.label("START", 8.sp) }
    fun lapPoint(f: Float) = timing.geometry.pointAt(f).let { g -> fit.map(g[0], g[1]) }
    val sectors = remember(fit) { sectorPaths(timing, ::lapPoint) }
    val scLabel = remember { measurer.label("SC", 8.sp) }
    val pulse by rememberPulse()

    val now = rememberMapTime(app)
    val cars = carsAt(timing, now).map { it to fit.map(it.x, it.y) }
    val flags = timing.flags.at(now)
    val caption = rememberCaption(measurer, flags, now)
    val safetyCar = if (!flags.track.safetyCar) null else leaderAt(timing, now)
        ?.let { leader -> cars.firstOrNull { it.first.number == leader }?.first }
        ?.let { lapPoint(timing.geometry.progress(it.x, it.y) + SAFETY_CAR_AHEAD) }

    Canvas(Modifier.fillMaxSize().graphicsLayer(alpha = app.mapOpacity)) {
        drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(7.dp.toPx()))
        drawPath(path, Color.White.copy(alpha = 0.6f), style = Stroke(3.dp.toPx()))
        drawFlags(flags, path, sectors, 3.5.dp.toPx(), pulse)
        drawSectorGlyphs(flags, sectors, 14.dp.toPx()) { f -> lapPoint(f).let { p -> p + outward(p, Offset(width / 2, height / 2)) * 18.dp.toPx() } }
        drawCaption(flags, caption, width / 2, height - 4.dp.toPx(), pulse)
        val center = Offset(width / 2, height / 2)

        fun mark(f: Float, chequered: Boolean, label: TextLayoutResult) {
            val g = timing.geometry.pointAt(f)
            val p = fit.map(g[0], g[1])
            val ahead = fit.map(g[0] + g[2] * 10f, g[1] + g[3] * 10f)
            val len = hypot(ahead.x - p.x, ahead.y - p.y).coerceAtLeast(0.001f)
            val along = Offset((ahead.x - p.x) / len, (ahead.y - p.y) / len)
            val across = Offset(-along.y, along.x)
            drawLineMark(p, across, along, 9.dp.toPx(), chequered)
            val out = outward(p, center) * 20.dp.toPx()
            drawText(label, topLeft = p + out - Offset(label.size.width / 2f, label.size.height / 2f))
        }
        if (timing.hasStartFinish && timing.outline.size >= 4) {
            mark(0f, chequered = true, finishLabel)
            marks.start?.takeIf { !marks.together }?.let { mark(it, chequered = false, startLabel) }
        }

        timing.corners.forEach { corner ->
            val label = turns[corner.number] ?: return@forEach
            val p = fit.map(corner.x, corner.y)
            val at = p + outward(p, center) * 14.dp.toPx()
            drawText(label, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f), alpha = 0.75f)
        }

        cars.forEach { (car, p) ->
            val r = 7.dp.toPx()
            val alpha = drawCar(timing, car, p, r)
            names[car.number]?.let { drawText(it, topLeft = Offset(p.x + r + 3.dp.toPx(), p.y - it.size.height / 2f), alpha = alpha) }
        }
        safetyCar?.let { drawSafetyCar(it, 10.dp.toPx(), scLabel) }
    }
    CarTargets(app, cars.map { (car, p) -> car.number to p })
}

private val FlagYellow = Color(0xFFFFD60A)
private val FlagRed = Color(0xFFFF3B30)
/** How far ahead of the leader the safety car is drawn, as a fraction of the lap. */
private const val SAFETY_CAR_AHEAD = 0.015f

/** One marshal sector's stretch of the lap, and where it starts (as a lap fraction). */
private class SectorShape(val path: Path, val from: Float)

/**
 * Each marshal sector's stretch of the lap, keyed by sector number. [point] places a lap
 * fraction on screen; a sector runs from its own start to the next one's.
 */
private fun sectorPaths(timing: SessionTiming, point: (Float) -> Offset): Map<Int, SectorShape> {
    val starts = timing.marshalSectors.sortedBy { it.number }.map { it.number to timing.geometry.progress(it.x, it.y) }
    if (starts.size < 2) return emptyMap()
    return starts.mapIndexed { i, (number, from) ->
        var to = starts[(i + 1) % starts.size].second
        if (to <= from) to += 1f
        val steps = ((to - from) * 400).toInt().coerceAtLeast(6)
        number to SectorShape(
            Path().apply {
                for (k in 0..steps) {
                    val p = point(from + (to - from) * k / steps)
                    if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            },
            from,
        )
    }.toMap()
}

/**
 * Race control's flags over the drawn track: all of it red under a red flag, yellow under the
 * safety car or VSC (pulsing once it's coming in), otherwise just the yellow sectors (double
 * yellows thicker).
 */
private fun DrawScope.drawFlags(flags: FlagPicture, whole: Path, sectors: Map<Int, SectorShape>, width: Float, pulse: Float) {
    when (flags.track) {
        TrackCondition.RED -> drawPath(whole, FlagRed, style = Stroke(width))
        TrackCondition.CLEAR -> flags.yellow.forEach { (sector, level) ->
            sectors[sector]?.let {
                drawPath(it.path, FlagYellow, style = Stroke(if (level >= 2) width * 1.6f else width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
        else -> drawPath(whole, FlagYellow, style = Stroke(width), alpha = if (flags.track.ending) pulse else 1f)
    }
}

/** A small flag on a pole, [size] tall, centred on [at]. */
private fun DrawScope.drawFlagGlyph(at: Offset, size: Float, color: Color, alpha: Float = 1f) {
    val w = size * 0.62f
    val left = at.x - w / 2
    val top = at.y - size / 2
    val cloth = Path().apply {
        moveTo(left, top)
        cubicTo(left + w * 0.35f, top - size * 0.08f, left + w * 0.65f, top + size * 0.1f, left + w, top)
        lineTo(left + w, top + size * 0.45f)
        cubicTo(left + w * 0.65f, top + size * 0.55f, left + w * 0.35f, top + size * 0.37f, left, top + size * 0.45f)
        close()
    }
    val shade = Color.Black.copy(alpha = 0.7f * alpha)
    val pole = size * 0.11f
    drawLine(shade, Offset(left, top), Offset(left, top + size), strokeWidth = pole + 3f)
    drawPath(cloth, shade, style = Stroke(3f))
    drawLine(color, Offset(left, top), Offset(left, top + size), strokeWidth = pole, alpha = alpha)
    drawPath(cloth, color, alpha = alpha)
}

/** A yellow flag where each yellow sector starts; [place] puts a lap fraction's flag on screen. */
private fun DrawScope.drawSectorGlyphs(flags: FlagPicture, sectors: Map<Int, SectorShape>, size: Float, place: (Float) -> Offset) {
    if (flags.track != TrackCondition.CLEAR) return
    flags.yellow.keys.forEach { sector -> sectors[sector]?.let { drawFlagGlyph(place(it.from), size, FlagYellow) } }
}

/**
 * The line at the foot of the map ("Red flag · Q3 resumes in 4:12"), centred on [x] with its foot
 * at [bottom], after a flag in the condition's colour. Pulses with the yellow when it's ending.
 */
private fun DrawScope.drawCaption(flags: FlagPicture, caption: TextLayoutResult?, x: Float, bottom: Float, pulse: Float) {
    caption ?: return
    val color = when (flags.track) {
        TrackCondition.RED -> FlagRed
        TrackCondition.CLEAR -> Color.White
        else -> FlagYellow
    }
    val alpha = if (flags.track.ending) pulse else 1f
    val glyph = if (flags.track != TrackCondition.CLEAR) caption.size.height * 0.9f else 0f
    val gap = if (glyph > 0f) 7.dp.toPx() else 0f
    val left = x - (glyph * 0.62f + gap + caption.size.width) / 2
    if (glyph > 0f) drawFlagGlyph(Offset(left + glyph * 0.31f, bottom - caption.size.height / 2f), glyph, color, alpha)
    drawText(
        caption,
        color = color,
        topLeft = Offset(left + glyph * 0.62f + gap, bottom - caption.size.height),
        alpha = alpha,
        shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f),
    )
}

/** The safety car: a yellow dot marked SC. F1 doesn't send its position, so it rides just ahead of the leader. */
private fun DrawScope.drawSafetyCar(at: Offset, radius: Float, label: TextLayoutResult) {
    drawCircle(Color.Black, radius + 1.5.dp.toPx(), at)
    drawCircle(FlagYellow, radius, at)
    drawText(label, color = Color.Black, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f))
}

/** The race leader's car number at [now], from the timing cards. */
private fun leaderAt(timing: SessionTiming, now: Long): String? =
    timing.laps.entries.mapNotNull { (number, laps) -> laps.at(now)?.position?.let { number to it } }.minByOrNull { it.second }?.first

/** The caption for [flags] at [now], measured once per change (the countdown ticks each second). */
@Composable
private fun rememberCaption(measurer: TextMeasurer, flags: FlagPicture, now: Long): TextLayoutResult? {
    val text = flags.caption(now)
    return remember(text) { text?.let { measurer.label(it, 15.sp) } }
}

/** 0.3 to 1 and back, gently, for flags about to go green. */
@Composable
private fun rememberPulse() = androidx.compose.animation.core.rememberInfiniteTransition(label = "flags").animateFloat(
    0.3f, 1f,
    androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1100, easing = EaseOut),
        androidx.compose.animation.core.RepeatMode.Reverse,
    ),
    label = "pulse",
)

private fun outward(p: Offset, center: Offset): Offset {
    val d = p - center
    val len = hypot(d.x, d.y)
    return if (len < 1f) Offset(0f, -1f) else d / len
}

/**
 * The lap unrolled onto a loop just inside the screen's edge, clockwise from start/finish at the
 * top center. The loop's corners follow the screen's own rounded corners. Shows running order,
 * gaps and overtakes without covering the feeds.
 */
internal class Ribbon(
    private val l: Float,
    private val t: Float,
    private val r: Float,
    private val b: Float,
    radius: Float,
    /** Which way cars travel round the loop; set to match the track map's direction. */
    private val clockwise: Boolean = true,
) {
    private val rr = radius.coerceIn(0f, minOf(r - l, b - t) / 2)
    private val top = (r - l) - 2 * rr
    private val side = (b - t) - 2 * rr
    private val arc = (Math.PI * rr / 2).toFloat()
    val perimeter = 2 * top + 2 * side + 4 * arc
    /** Start/finish sits at the middle of the top edge. */
    private val shift = top / 2

    /** Walks the rounded rectangle clockwise from the top-left end of the top edge. */
    private fun at(f: Float): Pair<Offset, Offset> {
        var d = ((if (clockwise) f else -f) * perimeter + shift).mod(perimeter)
        fun corner(cx: Float, cy: Float, startDeg: Float): Pair<Offset, Offset> {
            val a = Math.toRadians((startDeg + 90f * d / arc).toDouble())
            val p = Offset(cx + rr * kotlin.math.cos(a).toFloat(), cy + rr * kotlin.math.sin(a).toFloat())
            val n = Offset(cx - p.x, cy - p.y).let { if (rr > 0f) it / rr else it }
            return p to n
        }
        if (d < top) return Offset(l + rr + d, t) to Offset(0f, 1f)
        d -= top
        if (d < arc) return corner(r - rr, t + rr, -90f)
        d -= arc
        if (d < side) return Offset(r, t + rr + d) to Offset(-1f, 0f)
        d -= side
        if (d < arc) return corner(r - rr, b - rr, 0f)
        d -= arc
        if (d < top) return Offset(r - rr - d, b) to Offset(0f, -1f)
        d -= top
        if (d < arc) return corner(l + rr, b - rr, 90f)
        d -= arc
        if (d < side) return Offset(l, b - rr - d) to Offset(1f, 0f)
        d -= side
        return corner(l + rr, t + rr, 180f)
    }

    fun point(f: Float) = at(f).first

    /** Unit vector pointing into the screen. */
    fun inward(f: Float) = at(f).second

    fun path() = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(l, t, r, b, androidx.compose.ui.geometry.CornerRadius(rr, rr)))
    }
}

@Composable
fun EdgeMap(app: AppState, timing: SessionTiming, width: Float, height: Float, inset: Float, screenCornerRadius: Float) {
    val fallback = with(LocalDensity.current) { 24.dp.toPx() }
    // The screen's own corner radius. Up to 0.15 it was concentric (half the inset smaller),
    // which read as a tighter curve than the glass.
    val ribbon = remember(width, height, inset, screenCornerRadius, timing) {
        Ribbon(
            inset / 2, inset / 2, width - inset / 2, height - inset / 2,
            if (screenCornerRadius > 0f) screenCornerRadius else fallback,
            // Same direction the cars travel on the track map, whichever way the circuit runs.
            clockwise = !timing.geometry.counterClockwise,
        )
    }
    val measurer = rememberTextMeasurer()
    val names = remember(timing) { timing.drivers.mapValues { measurer.label(it.value.tla, 10.sp) } }
    val turns = remember(timing) {
        timing.corners.map { timing.geometry.progress(it.x, it.y) to measurer.label("T${it.number}", 8.sp, bold = false) }
    }
    val marks = remember(timing, timing.startLine) { LineMarks(timing) }
    val finishLabel = remember(marks) { measurer.label(marks.finishLabel, 8.sp) }
    val startLabel = remember { measurer.label("START", 8.sp) }
    val sectors = remember(ribbon, timing) { sectorPaths(timing, ribbon::point) }
    val scLabel = remember { measurer.label("SC", 7.sp) }
    val pulse by rememberPulse()

    val now = rememberMapTime(app)
    val cars = carsAt(timing, now)
        .map { car -> car to timing.geometry.progress(car.x, car.y) }
        .sortedBy { it.second }
    val flags = timing.flags.at(now)
    val caption = rememberCaption(measurer, flags, now)
    val safetyCar = if (!flags.track.safetyCar) null else leaderAt(timing, now)
        ?.let { leader -> cars.firstOrNull { it.first.number == leader }?.second }
        ?.let { it + SAFETY_CAR_AHEAD }

    Canvas(Modifier.fillMaxSize().graphicsLayer(alpha = app.mapOpacity)) {
        val line = ribbon.path()
        drawPath(line, Color.Black.copy(alpha = 0.5f), style = Stroke(6.dp.toPx()))
        drawPath(line, Color.White.copy(alpha = 0.5f), style = Stroke(2.dp.toPx()))
        drawFlags(flags, line, sectors, 2.5.dp.toPx(), pulse)
        drawSectorGlyphs(flags, sectors, 12.dp.toPx()) { f -> ribbon.point(f) + ribbon.inward(f) * 12.dp.toPx() }
        // Above the bottom of the loop, clear of the car names that sit inside it.
        drawCaption(flags, caption, width / 2, height - inset / 2 - 56.dp.toPx(), pulse)

        fun tick(f: Float, length: Float, stroke: Float) {
            val p = ribbon.point(f)
            val n = ribbon.inward(f)
            drawLine(Color.White, p - n * length, p + n * length, stroke)
        }

        fun mark(f: Float, chequered: Boolean, label: TextLayoutResult) {
            val p = ribbon.point(f)
            val n = ribbon.inward(f)
            drawLineMark(p, n, Offset(-n.y, n.x), 8.dp.toPx(), chequered)
            val at = p + n * MARK_LABEL_DP.dp.toPx()
            drawText(label, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f))
        }
        // Where the START and FINISH labels sit along the loop, and how wide they are: car names
        // keep out of their way.
        val taken = mutableListOf<Pair<Float, Float>>()
        if (timing.hasStartFinish) {
            mark(0f, chequered = true, finishLabel)
            taken += 0f to finishLabel.size.width.toFloat()
            marks.start?.takeIf { !marks.together }?.let {
                mark(it, chequered = false, startLabel)
                taken += it * ribbon.perimeter to startLabel.size.width.toFloat()
            }
        }
        turns.forEach { (f, label) ->
            tick(f, 4.dp.toPx(), 1.dp.toPx())
            val p = ribbon.point(f) + ribbon.inward(f) * 13.dp.toPx()
            drawText(label, topLeft = p - Offset(label.size.width / 2f, label.size.height / 2f), alpha = 0.7f)
        }

        // Labels step further inward when cars bunch up, so a pack stays readable.
        val lastAt = FloatArray(3) { -Float.MAX_VALUE }
        cars.forEach { (car, f) ->
            val p = ribbon.point(f)
            val alpha = drawCar(timing, car, p, 5.dp.toPx())
            val label = names[car.number] ?: return@forEach
            val along = f * ribbon.perimeter
            val gap = label.size.width + 6.dp.toPx()
            // The first level a mark label sits on is out where one is close by.
            val nearMark = taken.any { (at, w) ->
                val d = abs(along - at).let { minOf(it, ribbon.perimeter - it) }
                d < (w + label.size.width) / 2 + 4.dp.toPx()
            }
            val level = (0 until 3).firstOrNull { (it > 0 || !nearMark) && along - lastAt[it] > gap } ?: 2
            lastAt[level] = along
            val at = p + ribbon.inward(f) * (14 + level * 13).dp.toPx()
            drawText(label, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f), alpha = alpha)
        }
        safetyCar?.let { drawSafetyCar(ribbon.point(it), 7.5.dp.toPx(), scLabel) }
    }
    CarTargets(app, cars.map { (car, f) -> car.number to ribbon.point(f) })
}
