package app.apexview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SECTOR_YELLOW = Color(0xFFFFD60A)
private val SECTOR_GREEN = Color(0xFF30D158)
private val SECTOR_PURPLE = Color(0xFFBF5AF2)
private val SECTOR_EMPTY = Color.White.copy(alpha = 0.18f)

/** How long a finished lap's time stays up before the running clock takes over again. */
private const val LAP_SHOWN_MS = 6_000L

private fun Pace.color(lap: Boolean = false) = when (this) {
    Pace.PURPLE -> SECTOR_PURPLE
    Pace.GREEN -> SECTOR_GREEN
    // A lap that's neither a best nor a personal best is shown plain, as on the broadcast.
    Pace.YELLOW -> if (lap) Color.White else SECTOR_YELLOW
    Pace.NONE -> SECTOR_EMPTY
}

private val digits = TextStyle(
    color = Color.White,
    fontSize = 13.sp,
    fontWeight = FontWeight.SemiBold,
    fontFamily = FontFamily.Default,
    fontFeatureSettings = "tnum",
)

/** The car's speed for an onboard's corner, following the picture the way the card does. */
@Composable
fun SpeedReadout(app: AppState, timing: SessionTiming, number: String, modifier: Modifier = Modifier) {
    LaunchedEffect(timing) { app.loadSpeeds(timing) }
    val kmh = timing.speeds[number]?.kmhAt(rememberMapTime(app)) ?: return
    val unit = app.settings.speedUnit
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text("${speedIn(kmh, unit)}", style = digits.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(4.dp))
        Text(
            if (unit == Settings.KMH) "km/h" else "mph",
            style = digits.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f)),
            modifier = Modifier.padding(bottom = 1.dp),
        )
    }
}

private fun running(ms: Long): String {
    val tenths = ms / 100
    return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}

/**
 * The broadcast's driver card for an onboard: position, number and name in the team colour, the
 * three sector bars as they're set (yellow, green for a personal best, purple for the best of
 * all), the clock running through the lap, the lap time when it's done, and the gap. Follows
 * the picture the same way the map does. The caller sets its width.
 */
@Composable
fun DriverCard(app: AppState, timing: SessionTiming, number: String, name: String?, modifier: Modifier = Modifier) {
    val laps = timing.laps[number] ?: return
    val now = rememberMapTime(app)
    val state = laps.at(now) ?: return
    val driver = timing.drivers[number]
    val team = driver?.color?.let { Color(it) } ?: Color.White
    val surname = (name?.substringAfterLast(' ') ?: driver?.tla ?: number).uppercase()

    val lapJustDone = state.lastLap != null && state.runningMs != null && state.runningMs < LAP_SHOWN_MS
    val clock: Pair<String, Color>? = when {
        state.inPit -> "PIT" to Color.White
        lapJustDone -> state.lastLap!!.time to state.lastLap.pace.color(lap = true)
        state.runningMs != null -> running(state.runningMs) to Color.White
        else -> state.lastLap?.let { it.time to it.pace.color(lap = true) }
    }

    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.72f))
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(team))
        Column(Modifier.padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                state.position?.let {
                    Text(
                        "$it",
                        style = digits.copy(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color.White).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Text(number, style = digits.copy(color = team, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(6.dp))
                Text(
                    surname,
                    style = digits.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                clock?.let { (text, color) -> Text(text, style = digits.copy(color = color)) }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    // Just over the line, the bars show the lap that brought the lap time.
                    (if (lapJustDone) state.lapSectors else state.sectors).forEach { cell ->
                        Box(
                            Modifier
                                .size(width = 30.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(cell?.pace?.color() ?: SECTOR_EMPTY)
                        )
                    }
                }
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                state.gap?.let { Text(it, style = digits.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))) }
            }
        }
    }
}
