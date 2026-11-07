package app.apexview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The pit circle in the map's window: the lap as a ring with the finish at the top and every car
 * at how far round it is. The chosen driver's ghost sits as far back as a stop costs, so the cars
 * beside it are the ones they'd come out among. Tapping a car chooses it.
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
    }
}

@Composable
private fun PitCircle(app: AppState, timing: SessionTiming, width: Float, height: Float) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val names = remember(timing) { timing.drivers.mapValues { measurer.label(it.value.tla, 10.sp) } }
    val pitLabel = remember { measurer.label("PIT", 8.sp) }

    // From this session's stops; before any, the last measured here, else a typical stop.
    val measured = remember(timing) { PitWindow.sessionLoss(timing) }
    LaunchedEffect(measured, timing.circuitKey) { measured?.let { app.rememberPitLoss(timing.circuitKey, it.first) } }
    val saved = remember(timing.circuitKey) { app.savedPitLoss(timing.circuitKey) }
    val lossMs = measured?.first ?: saved ?: PitWindow.TYPICAL_LOSS_MS
    val lossNote = when {
        measured != null -> "measured from ${measured.second} stop" + if (measured.second == 1) "" else "s"
        saved != null -> "as measured last time here"
        else -> "a typical stop until someone pits"
    }

    val now = rememberMapTime(app)
    val cars = carsAt(timing, now).map { it to timing.geometry.progress(it.x, it.y) }.sortedBy { it.second }
    fun onTrack(number: String?) = number?.takeIf { n -> cars.any { it.first.number == n } }
    val chosen = onTrack(app.pitDriver) ?: onTrack(app.mainOnboard) ?: leaderAt(timing, now)
    val me = cars.firstOrNull { it.first.number == chosen }
    val lapMs = chosen?.let { timing.laps[it]?.typicalLapMs(now) }
    val inPit = me?.first?.state == LapState.PIT || chosen?.let { timing.laps[it]?.at(now)?.inPit } == true
    val ghost = if (me != null && lapMs != null && !inPit) PitWindow.ghost(me.second, lossMs, lapMs) else null
    val others = cars.filter { it.first.number != chosen && it.first.state != LapState.PIT }.map { it.first.number to it.second }
    val near = if (ghost != null && lapMs != null) PitWindow.neighbours(ghost, others, lapMs) else null
    val neutralised = timing.flags.at(now).track.let { it.safetyCar || it == TrackCondition.VSC || it == TrackCondition.VSC_ENDING }
    val color = Color(chosen?.let { timing.drivers[it]?.color } ?: android.graphics.Color.WHITE)

    val centre = Offset(width / 2, height / 2)
    val radius = (min(width, height) / 2 - with(density) { LABEL_ROOM.toPx() }).coerceAtLeast(1f)
    fun degrees(f: Float) = -90f + f * 360f
    fun direction(f: Float) = Math.toRadians(degrees(f).toDouble()).let { Offset(cos(it).toFloat(), sin(it).toFloat()) }
    fun point(f: Float) = centre + direction(f) * radius

    Canvas(Modifier.fillMaxSize().graphicsLayer(alpha = app.mapOpacity)) {
        drawCircle(Color.Black.copy(alpha = 0.5f), radius, centre, style = Stroke(6.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.5f), radius, centre, style = Stroke(2.dp.toPx()))
        val finish = point(0f)
        drawLine(Color.White, finish - Offset(0f, 8.dp.toPx()), finish + Offset(0f, 8.dp.toPx()), 2.dp.toPx())

        val ring = Size(radius * 2, radius * 2)
        val corner = centre - Offset(radius, radius)
        if (ghost != null && lapMs != null) {
            // What the stop costs: from the ghost round to the car.
            drawArc(
                color.copy(alpha = 0.35f), degrees(ghost), lossMs * 360f / lapMs, false, corner, ring,
                style = Stroke(8.dp.toPx(), cap = StrokeCap.Round),
            )
            // A second either way, since a stop never costs exactly the same twice.
            val spread = WINDOW_MS * 360f / lapMs
            drawArc(Color.White.copy(alpha = 0.18f), degrees(ghost) - spread, spread * 2, false, corner, ring, style = Stroke(16.dp.toPx()))
        }

        // Names step further out when cars bunch up, so a pack stays readable.
        val lastAt = FloatArray(3) { -Float.MAX_VALUE }
        val circumference = (2 * Math.PI * radius).toFloat()
        cars.forEach { (car, f) ->
            val p = point(f)
            val mine = car.number == chosen
            val alpha = drawCar(timing, car, p, (if (mine) 6.5f else 5f).dp.toPx())
            if (mine) drawCircle(Color.White, 10.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            val label = names[car.number] ?: return@forEach
            val along = f * circumference
            val level = (0 until 3).firstOrNull { along - lastAt[it] > label.size.width + 6.dp.toPx() } ?: 2
            lastAt[level] = along
            val at = p + direction(f) * (16 + level * 12).dp.toPx()
            drawText(label, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f), alpha = alpha)
        }

        ghost?.let { g ->
            val p = point(g)
            drawCircle(Color.Black.copy(alpha = 0.6f), 8.dp.toPx(), p)
            drawCircle(color, 7.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
            val at = p - direction(g) * 18.dp.toPx()
            drawText(pitLabel, topLeft = at - Offset(pitLabel.size.width / 2f, pitLabel.size.height / 2f))
        }
    }
    CarTargets(app, cars.map { (car, f) -> car.number to point(f) }) { app.pitDriver = it }

    fun tla(number: String) = timing.drivers[number]?.tla ?: number
    fun seconds(ms: Long) = "%.1f s".format(ms / 1000f)
    val shadow = Shadow(Color.Black, blurRadius = 6f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(with(density) { (radius * 1.4f).toDp() }),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when {
                    chosen == null -> "Tap a car"
                    inPit -> "${tla(chosen)} is in the pits"
                    else -> "If ${tla(chosen)} stops now"
                },
                style = Type.headline.copy(shadow = shadow, textAlign = TextAlign.Center),
            )
            val line = Type.subhead.copy(color = Ink.primary, shadow = shadow, textAlign = TextAlign.Center)
            near?.ahead?.let { (number, ms) -> Text("out ${seconds(ms)} behind ${tla(number)}", style = line) }
            near?.behind?.let { (number, ms) -> Text("${seconds(ms)} ahead of ${tla(number)}", style = line) }
            Text(
                "A stop costs about ${seconds(lossMs)}, $lossNote." + if (neutralised) " Less under a safety car." else "",
                style = Type.caption.copy(shadow = shadow, textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Room outside the ring for the car names. */
private val LABEL_ROOM = 40.dp
/** How sure the window is, either way. */
private const val WINDOW_MS = 1_000L
