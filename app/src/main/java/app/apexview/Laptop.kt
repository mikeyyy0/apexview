package app.apexview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

private val ROW_HEIGHT = 28.dp
private val TYRE_COLORS = mapOf(
    "SOFT" to Color(0xFFFF3B30),
    "MEDIUM" to Color(0xFFFFD60A),
    "HARD" to Color(0xFFF2F2F2),
    "INTERMEDIATE" to Color(0xFF34C759),
    "WET" to Color(0xFF0A84FF),
)
private val cell = TextStyle(color = Color.White, fontSize = 13.sp, fontFeatureSettings = "tnum")

/**
 * The half of the screen below the hinge in laptop mode: the timing tower on the left. The track
 * map on the right is its own window, movable when the layout is unlocked. Both follow the
 * picture's clock, like the map and cards everywhere else.
 */
@Composable
fun LaptopPanel(app: AppState, timing: SessionTiming?, modifier: Modifier) {
    // The tower takes the left; the map is a window of its own over the right (see Viewer).
    Row(modifier.background(Ink.background)) {
        Column(Modifier.weight(AppState.LAPTOP_TOWER_SHARE).fillMaxHeight()) {
            // An onboard is up top: one tap back to the broadcast it replaced.
            app.laptopReturnChannel?.let { channel ->
                Row(
                    Modifier.pressable { app.returnToBroadcast() }.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Rounded.ArrowBackIosNew, null,
                        tint = Ink.primary, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Back to ${broadcastName(channel)}", style = Type.headline)
                }
            }
            if (timing != null && timing.laps.isNotEmpty()) TimingTower(app, timing)
            else Text(app.mapStatus ?: "No timing for this session.", style = Type.footnote, modifier = Modifier.padding(20.dp))
        }
        Spacer(Modifier.weight(1f - AppState.LAPTOP_TOWER_SHARE))
    }
}

/** Every driver in running order: last lap, gap to the leader, interval, tyre. Tap a row for their onboard. */
@Composable
private fun TimingTower(app: AppState, timing: SessionTiming) {
    // Twice a second is plenty for a table.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            now = app.mapTimeMs() ?: 0L
            delay(500)
        }
    }
    val rows = timing.laps.mapNotNull { (number, laps) -> laps.at(now)?.let { number to it } }
        .sortedBy { it.second.position ?: 99 }
    val onTop = app.tiles.firstOrNull { it.id == app.bigTileIdForUi }?.channel?.racingNumber?.toString()

    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp, top = 8.dp)) {
        TowerHeader(timing)
        LazyColumn {
            items(rows, key = { it.first }) { (number, state) ->
                TowerRow(timing, number, state, timing.tyres[number]?.at(now), highlight = number == onTop) { app.showOnboard(number) }
            }
        }
    }
}

/** The tower's column titles. */
@Composable
fun TowerHeader(timing: SessionTiming) {
    Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        Header("", 26.dp)
        Header("Driver", 58.dp, TextAlign.Start)
        Header("Last lap", 74.dp)
        Header(if (timing.isRace) "Leader" else "Fastest", 66.dp)
        Header("Interval", 66.dp)
        Header("Tyre", 52.dp)
    }
}

/** One driver in the tower: position, team colour, code, last lap, gaps and tyre. */
@Composable
fun TowerRow(timing: SessionTiming, number: String, state: CardState, tyre: Tyre?, highlight: Boolean, onClick: () -> Unit) {
    val driver = timing.drivers[number]
    val lap = state.lastLap
    Row(
        Modifier
            .height(ROW_HEIGHT)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlight) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .pressable(scaleTo = 0.99f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${state.position ?: ""}", style = cell.copy(color = Ink.secondary), textAlign = TextAlign.End, modifier = Modifier.width(20.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(driver?.color?.let { Color(it) } ?: Color.White))
        Spacer(Modifier.width(6.dp))
        Text(driver?.tla ?: number, style = cell.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(49.dp))
        Text(
            if (state.inPit) "PIT" else lap?.time.orEmpty(),
            style = cell.copy(color = lapColor(lap?.pace)),
            textAlign = TextAlign.End,
            modifier = Modifier.width(74.dp),
        )
        Text(state.toLeader.orEmpty(), style = cell.copy(color = Ink.secondary), textAlign = TextAlign.End, modifier = Modifier.width(66.dp))
        Text(state.ahead.orEmpty(), style = cell.copy(color = Ink.secondary), textAlign = TextAlign.End, modifier = Modifier.width(66.dp))
        Row(Modifier.width(52.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            tyre?.let {
                Text("${it.laps}", style = cell.copy(color = Ink.secondary, fontSize = 12.sp))
                Spacer(Modifier.width(5.dp))
                TyreMark(it.compound)
            }
        }
    }
}

@Composable
private fun Header(text: String, width: Dp, align: TextAlign = TextAlign.End) {
    Text(text, style = Type.caption, textAlign = align, maxLines = 1, modifier = Modifier.width(width))
}

/** The tyre as on the broadcast: a ring in the compound's colour with its letter. */
@Composable
private fun TyreMark(compound: String) {
    val color = TYRE_COLORS[compound] ?: Ink.secondary
    Box(
        Modifier.size(18.dp).border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(compound.take(1), style = cell.copy(color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold))
    }
}

private fun lapColor(pace: Pace?) = when (pace) {
    Pace.PURPLE -> Color(0xFFBF5AF2)
    Pace.GREEN -> Color(0xFF30D158)
    else -> Color.White
}

/** The one-time question when the phone is first held half-open like a laptop. */
@Composable
fun LaptopPrompt(app: AppState) {
    if (!app.laptopPromptOpen) return
    Dialog(onDismissRequest = { app.answerLaptopPrompt(false) }) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ink.surface)
                .padding(24.dp)
        ) {
            Text("Laptop mode?", style = Type.title2)
            Text(
                "With the phone half-open like this, the stream can sit on the top half and the timing tower and track map on the bottom half. You can turn this on or off any time in Layout.",
                style = Type.subhead,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                SecondaryButton("No thanks") { app.answerLaptopPrompt(false) }
                PrimaryButton("Use laptop mode") { app.answerLaptopPrompt(true) }
            }
        }
    }
}

/** "F1 LIVE" as "F1 Live", "INTERNATIONAL" as "International". */
private fun broadcastName(channel: Channel) = channel.title.split(" ").joinToString(" ") { word ->
    if (word.length <= 2) word.uppercase() else word.lowercase().replaceFirstChar { it.uppercase() }
}
