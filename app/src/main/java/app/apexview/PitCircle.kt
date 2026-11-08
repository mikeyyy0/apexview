package app.apexview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The circle of doom in the map's window, after MultiViewer's: the lap as a circle laid out in
 * time at the leader's pace, the finish at the top, the pit lane as an arc inside it. Each car's
 * label carries its gap, to the car ahead on track or to the leader. Tapping a car measures every
 * gap to it and shows its pit window: the stretch a stop now would drop it back.
 */
@Composable
fun PitCircleView(app: AppState, timing: SessionTiming) {
    val panel = app.activeMapPanel
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(panel.x.roundToInt(), panel.y.roundToInt()) }
            .requiredSize(with(density) { panel.w.toDp() }, with(density) { panel.h.toDp() })
    ) {
        PitCircle(app, timing, panel.w, panel.h)
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlyphButton(
                Icons.Rounded.SwapHoriz,
                if (app.pitGapsToLeader) "Show gaps to the car ahead on track" else "Show gaps to the leader",
            ) { app.pitGapsToLeader = !app.pitGapsToLeader }
            GlyphButton(Icons.Rounded.Sync, "Flip the direction of travel") { app.pitCircleFlipped = !app.pitCircleFlipped }
        }
    }
}

/**
 * What every map needs to show a pit stop: the reference lap and the lap laid out in time (see
 * [LapProfile]), and what a stop costs here.
 */
internal class PitBasis(
    val leader: String?,
    /** The leader's last few green laps, as (start, end) UTC ms. */
    val green: List<Pair<Long, Long>>,
    /** Null until the leader has finished a lap. */
    val referenceMs: Long?,
    val profile: LapProfile,
    val lane: PitWindow.PitLane?,
    /** What a stop cost last time it was measured at this circuit. */
    val saved: Long?,
    val lossMs: Long,
)

@Composable
internal fun rememberPitBasis(app: AppState, timing: SessionTiming, now: Long): PitBasis {
    val leader = leaderAt(timing, now)
    val leaderLaps = leader?.let { timing.laps[it] }

    // The reference lap: the average of the leader's last few green laps, as MultiViewer has it.
    val green = leaderLaps?.lapWindows(now).orEmpty().filter { (start, end) ->
        leaderLaps!!.pitStops.none { (inUtc, outUtc) -> inUtc < end && outUtc > start } &&
            timing.flags.at((start + end) / 2).track == TrackCondition.CLEAR
    }.takeLast(PitWindow.REFERENCE_LAPS)
    // Joined live part-way, no lap has been timed here yet: the leader's last lap, as F1 gives it.
    val referenceMs = green.takeIf { it.isNotEmpty() }?.map { (start, end) -> end - start }?.average()?.toLong()
        ?: leaderLaps?.typicalLapMs(now)
        ?: leaderLaps?.at(now)?.lastLap?.time?.let(PitWindow::lapTimeMs)
    // Laid out in time from the leader's latest green lap; distance stands in until there is one.
    val profileLap = green.lastOrNull()
    val profile = remember(timing.circuitKey, leader, profileLap) {
        val track = leader?.let { timing.tracks[it] }
        if (track == null || profileLap == null) null
        else LapProfile.from(
            (profileLap.first..profileLap.second step SAMPLE_MS).mapNotNull { t ->
                track.at(t)?.let { (x, y) -> (t - profileLap.first) to timing.geometry.progress(x, y) }
            },
            profileLap.second - profileLap.first,
        )
    } ?: LapProfile.EVEN

    // What a stop costs: this session's stops, else the last measured here, else a typical stop.
    val lane = remember(timing) { PitWindow.pitLane(timing) }
    LaunchedEffect(lane?.lossMs, timing.circuitKey) { lane?.let { app.rememberPitLoss(timing.circuitKey, it.lossMs) } }
    val saved = remember(timing.circuitKey) { app.savedPitLoss(timing.circuitKey) }
    val lossMs = lane?.lossMs ?: saved ?: PitWindow.TYPICAL_LOSS_MS
    return PitBasis(leader, green, referenceMs, profile, lane, saved, lossMs)
}

/** A stop now for [number]: where on the lap it'd come out (track progress), and who'd be just ahead. */
internal class PitStopNow(val number: String, val carProgress: Float, val ghostProgress: Float, val outBehind: Pair<String, Long>?)

/** [cars] are each car's progress round the track; null when [number] isn't among them or there's no reference lap yet. */
internal fun pitStopNow(basis: PitBasis, number: String?, cars: List<Pair<String, Float>>): PitStopNow? {
    val referenceMs = basis.referenceMs ?: return null
    val progress = cars.firstOrNull { it.first == number }?.second ?: return null
    val at = basis.profile.timeFraction(progress)
    val ghost = PitWindow.ghost(at, basis.lossMs, referenceMs)
    val others = cars.filter { it.first != number }.map { (n, p) -> n to basis.profile.timeFraction(p) }
    return PitStopNow(number!!, progress, basis.profile.progressAt(ghost), PitWindow.neighbours(ghost, others, referenceMs).ahead)
}

/** One car on the circle: how far into the lap (in time) and through the race (in laps). */
private class OnCircle(val car: Car, val at: Float, val distance: Float)

@Composable
private fun PitCircle(app: AppState, timing: SessionTiming, width: Float, height: Float) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val now = rememberMapTime(app)
    val flags = timing.flags.at(now)
    val basis = rememberPitBasis(app, timing, now)
    val leader = basis.leader
    val leaderLaps = leader?.let { timing.laps[it] }
    val green = basis.green
    val referenceMs = basis.referenceMs
    val profile = basis.profile
    val lane = basis.lane
    val saved = basis.saved
    val lossMs = basis.lossMs

    if (referenceMs == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("The circle starts once the leader has finished a lap.", style = Type.footnote, textAlign = TextAlign.Center)
        }
        return
    }

    // Cars in the pit lane ride the inner arc; one there far longer than a stop has stopped.
    val inLane = mutableMapOf<String, Float>()
    val ring = mutableListOf<OnCircle>()
    val placed = carsAt(timing, now)
    placed.forEach { car ->
        val laps = timing.laps[car.number]
        val since = laps?.inPitSince(now)
        if (since != null && lane != null) {
            if (now - since <= lane.laneMs * 2) inLane[car.number] = ((now - since).toFloat() / lane.laneMs).coerceIn(0f, 1f)
            return@forEach
        }
        val at = profile.timeFraction(timing.geometry.progress(car.x, car.y))
        val crossed = laps?.lastCrossing(now)?.let { (now - it).toFloat() / referenceMs }
        ring += OnCircle(car, at, PitWindow.raceDistance(laps?.lapAt(now) ?: 0, at, crossed))
    }
    // F1's positions can stop before the timing does (the 2026 Mexico race's ran out on lap 57).
    // Cars without one go where their gap puts them, the way MultiViewer lays out its circle:
    // the leader by the time since it crossed the line, everyone else that far behind it.
    if (placed.size < timing.laps.size) {
        val missing = timing.laps.filter { (number, laps) ->
            placed.none { it.number == number } && !laps.retiredAt(now) && laps.inPitSince(now) == null
        }
        val leaderAt = leader?.let { number ->
            ring.firstOrNull { it.car.number == number }?.at
                ?: timing.laps[number]?.lastCrossing(now)?.let { ((now - it).toFloat() / referenceMs).mod(1f) }
        }
        if (leaderAt != null) missing.forEach { (number, laps) ->
            val behind = if (number == leader) 0f else PitWindow.gapSeconds(laps.at(now)?.toLeader) ?: return@forEach
            val at = (leaderAt - behind * 1000f / referenceMs).mod(1f)
            val crossed = laps.lastCrossing(now)?.let { (now - it).toFloat() / referenceMs }
            ring += OnCircle(Car(number, 0f, 0f, LapState.UNKNOWN), at, PitWindow.raceDistance(laps.lapAt(now) ?: 0, at, crossed))
        }
    }
    val chosen = app.pitDriver?.let { n -> ring.firstOrNull { it.car.number == n } }
    val front = ring.maxByOrNull { it.distance }

    fun tla(number: String) = timing.drivers[number]?.tla ?: number
    fun signed(laps: Float) = "%+.1f".format(laps * referenceMs / 1000f)
    val gaps = ring.associate { c ->
        c.car.number to when {
            chosen != null -> if (c === chosen) "" else signed(chosen.distance - c.distance)
            app.pitGapsToLeader -> if (c === front) "Leader" else front?.let { signed(it.distance - c.distance) }.orEmpty()
            else -> ring.filter { it !== c }.minOfOrNull { (it.at - c.at).mod(1f) }?.let { signed(it) }.orEmpty()
        }
    }
    val ghost = chosen?.let { PitWindow.ghost(it.at, lossMs, referenceMs) }
    val near = ghost?.let { g -> PitWindow.neighbours(g, ring.filter { it !== chosen }.map { it.car.number to it.at }, referenceMs) }

    // The way round matches the circuit's, unless flipped.
    val turn = if (timing.geometry.counterClockwise != app.pitCircleFlipped) -1f else 1f
    val (centre, radius) = pitCircleFrame(app, with(density) { LABEL_ROOM.toPx() })
    val laneRadius = radius * LANE_RADIUS
    fun degrees(f: Float) = -90f + turn * f * 360f
    fun direction(f: Float) = Math.toRadians(degrees(f).toDouble()).let { Offset(cos(it).toFloat(), sin(it).toFloat()) }
    fun point(f: Float, r: Float = radius) = centre + direction(f) * r
    val laneStart = lane?.let { profile.timeFraction(it.entry) }
    val laneSweep = lane?.let { (profile.timeFraction(it.exit) - profile.timeFraction(it.entry)).mod(1f) }

    val names = remember(timing) {
        timing.drivers.mapValues { (_, d) ->
            val ink = if (Color(d.color).luminance() > 0.6f) Color.Black else Color.White
            measurer.measure(d.tla, TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
    }
    val turns = remember(timing, profile) {
        timing.corners.map { profile.timeFraction(timing.geometry.progress(it.x, it.y)) to measurer.label("${it.number}", 8.sp, bold = false) }
    }

    Canvas(Modifier.fillMaxSize().graphicsLayer(alpha = app.mapOpacity)) {
        // Dark inside the ring, so the centre reads over a bright picture.
        drawCircle(Color.Black.copy(alpha = 0.6f), radius, centre)
        val ringSize = Size(radius * 2, radius * 2)
        val ringCorner = centre - Offset(radius, radius)
        // The pit window: from where a stop now would drop the chosen car, round to the car.
        if (chosen != null && ghost != null) {
            drawArc(
                PIT_PURPLE.copy(alpha = 0.55f), degrees(ghost), turn * (chosen.at - ghost).mod(1f) * 360f, false,
                ringCorner, ringSize, style = Stroke(14.dp.toPx()),
            )
        }
        drawCircle(Color.White.copy(alpha = 0.35f), radius, centre, style = Stroke(1.5.dp.toPx()))

        turns.forEach { (f, label) ->
            drawLine(Color.White.copy(alpha = 0.5f), point(f, radius - 3.dp.toPx()), point(f, radius - 8.dp.toPx()), 1.dp.toPx())
            val at = point(f, radius - 16.dp.toPx())
            drawText(label, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f), alpha = 0.6f)
        }
        drawChequer(point(0f), 10.dp.toPx())

        // The pit lane, inside the ring from entry to exit, joined to it at both ends.
        if (laneStart != null && laneSweep != null) {
            val laneSize = Size(laneRadius * 2, laneRadius * 2)
            val laneCorner = centre - Offset(laneRadius, laneRadius)
            val grey = Color.White.copy(alpha = 0.3f)
            drawArc(grey, degrees(laneStart), turn * laneSweep * 360f, false, laneCorner, laneSize, style = Stroke(1.5.dp.toPx()))
            drawLine(grey, point(laneStart), point(laneStart, laneRadius), 1.dp.toPx())
            drawLine(grey, point(laneStart + laneSweep), point(laneStart + laneSweep, laneRadius), 1.dp.toPx())
            inLane.forEach { (number, along) ->
                val p = point(laneStart + laneSweep * along, laneRadius)
                drawCircle(Color(timing.drivers[number]?.color ?: android.graphics.Color.WHITE), 4.dp.toPx(), p)
                val label = measurer.label(tla(number), 8.sp)
                drawText(label, topLeft = p + Offset(-label.size.width / 2f, 6.dp.toPx()))
            }
        }

        // Labels sit outside the ring, stepping further out where they'd overlap, with a line back
        // to the dot when they do.
        val taken = mutableListOf<Rect>()
        val pad = 4.dp.toPx()
        ring.sortedBy { it.at }.forEach { c ->
            val p = point(c.at)
            val mine = c === chosen
            val color = Color(timing.drivers[c.car.number]?.color ?: android.graphics.Color.WHITE)
            drawCircle(Color.Black, 6.5.dp.toPx(), p)
            drawCircle(color, 5.dp.toPx(), p)
            if (mine) drawCircle(Color.White, 10.dp.toPx(), p, style = Stroke(2.dp.toPx()))

            val name = names[c.car.number] ?: return@forEach
            val gap = gaps[c.car.number].orEmpty().takeIf { it.isNotEmpty() }?.let {
                measurer.measure(it, TextStyle(color = name.layoutInput.style.color.copy(alpha = 0.85f), fontSize = 8.sp))
            }
            val w = maxOf(name.size.width, gap?.size?.width ?: 0) + pad * 2
            val h = name.size.height + (gap?.size?.height ?: 0) + pad
            val u = direction(c.at)
            val extent = abs(u.x) * w / 2 + abs(u.y) * h / 2
            var box = Rect.Zero
            for (level in 0..4) {
                val middle = p + u * (12.dp.toPx() + level * h * 0.8f + extent)
                box = Rect(middle - Offset(w / 2, h / 2), Size(w, h))
                if (taken.none { it.inflate(2.dp.toPx()).overlaps(box) }) break
            }
            taken += box
            if ((box.center - p).getDistance() > 12.dp.toPx() + extent + 1f) drawLine(color, p, box.center - u * extent, 1.dp.toPx())
            drawRoundRect(color, box.topLeft, box.size, CornerRadius(4.dp.toPx()))
            if (mine) drawRoundRect(Color.White, box.topLeft, box.size, CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            drawText(name, topLeft = Offset(box.center.x - name.size.width / 2f, box.top + pad / 2))
            gap?.let { drawText(it, topLeft = Offset(box.center.x - it.size.width / 2f, box.top + pad / 2 + name.size.height)) }
        }

        ghost?.let { g ->
            val p = point(g)
            drawCircle(Color.Black.copy(alpha = 0.6f), 8.dp.toPx(), p)
            drawCircle(Color.White, 7.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
    }
    // On the circle a tap chooses the car, as holding does on the other maps.
    CarTargets(app, ring.map { it.car.number to point(it.at) }, onTap = { number ->
        app.pitDriver = if (app.pitDriver == number) null else number
    })

    fun seconds(ms: Long) = "%.1f s".format(ms / 1000f)
    Box(
        Modifier
            .offset { IntOffset((centre.x - width / 2).roundToInt(), (centre.y - height / 2).roundToInt()) }
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(with(density) { (radius * 1.5f).toDp() }),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val lap = leaderLaps?.lapAt(now)
            Text(
                when {
                    lap == null -> ""
                    timing.totalLaps != null -> "$lap/${timing.totalLaps}"
                    else -> "Lap $lap"
                },
                style = Type.largeTitle,
            )
            val (status, statusColor) = when (flags.track) {
                TrackCondition.CLEAR -> "Green" to GREEN
                TrackCondition.RED -> "Red flag" to Ink.live
                else -> (flags.track.label ?: "") to YELLOW
            }
            Text(
                status,
                style = Type.caption.copy(color = if (statusColor == YELLOW) Color.Black else Color.White, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 2.dp).background(statusColor, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
            )
            Text(
                "Reference lap " + "%d:%06.3f".format(referenceMs / 60_000, referenceMs % 60_000 / 1000f),
                style = Type.footnote.copy(color = Ink.primary, textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                if (green.isEmpty()) "the leader's usual lap so far" else "avg. of last ${green.size} green lap" + if (green.size == 1) "" else "s",
                style = Type.caption.copy(textAlign = TextAlign.Center),
            )
            Text(
                when {
                    chosen != null -> "gap to ${tla(chosen.car.number)}"
                    app.pitGapsToLeader -> "gap to the leader"
                    else -> "gap to the car ahead"
                },
                style = Type.footnote.copy(color = Ink.primary, textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 6.dp),
            )
            if (chosen != null) {
                val out = near?.ahead?.let { (number, ms) -> "Stop now: out ${seconds(ms)} behind ${tla(number)}" } ?: "Stop now"
                Text(out, style = Type.footnote.copy(color = Ink.primary, textAlign = TextAlign.Center), modifier = Modifier.padding(top = 6.dp))
                Text(
                    "Pit loss ${seconds(lossMs)}, " + when {
                        lane != null -> "measured from ${lane.stops} stop" + if (lane.stops == 1) "" else "s"
                        saved != null -> "as measured last time here"
                        else -> "a typical stop until someone pits"
                    } + if (flags.track != TrackCondition.CLEAR) ". Less under a safety car." else "",
                    style = Type.caption.copy(textAlign = TextAlign.Center),
                )
            }
        }
    }
}

/** The finish: a small chequered square on the ring. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChequer(at: Offset, side: Float) {
    val cell = side / 3
    val corner = at - Offset(side / 2, side / 2)
    drawRect(Color.Black, corner - Offset(1f, 1f), Size(side + 2f, side + 2f))
    for (i in 0 until 3) for (j in 0 until 3) {
        drawRect(if ((i + j) % 2 == 0) Color.White else Color.Black, corner + Offset(i * cell, j * cell), Size(cell, cell))
    }
}

/**
 * The circle's centre (within its window) and radius. It sits in the part of the window that's on
 * screen: a window carried over from the track map can run off the edge, and the circle with it.
 */
internal fun pitCircleFrame(app: AppState, labelRoom: Float): Pair<Offset, Float> {
    val panel = app.activeMapPanel
    val (screenW, screenH) = app.canvasSize
    val left = (-panel.x).coerceIn(0f, panel.w)
    val top = (-panel.y).coerceIn(0f, panel.h)
    val right = if (screenW > 0f) (screenW - panel.x).coerceIn(left, panel.w) else panel.w
    val bottom = if (screenH > 0f) (screenH - panel.y).coerceIn(top, panel.h) else panel.h
    val radius = (min(right - left, bottom - top) / 2 - labelRoom).coerceAtLeast(1f)
    return Offset((left + right) / 2, (top + bottom) / 2) to radius
}

/** True when a touch at ([x], [y]) on the canvas lands on the circle or its labels: what moves it. */
internal fun pitCircleTouched(app: AppState, x: Float, y: Float, labelRoom: Float): Boolean {
    val (centre, radius) = pitCircleFrame(app, labelRoom)
    val panel = app.activeMapPanel
    return (Offset(x - panel.x, y - panel.y) - centre).getDistance() <= radius + labelRoom
}

/** Room outside the ring for the labels. */
internal val LABEL_ROOM = 56.dp
/** The pit lane's arc, as a share of the ring's radius. */
private const val LANE_RADIUS = 0.62f
/** How often the leader's lap is sampled to lay the circle out in time. */
private const val SAMPLE_MS = 250L
private val GREEN = Color(0xFF3DAA4F)
private val YELLOW = Color(0xFFF5C518)
