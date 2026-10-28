package app.apexview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MonthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
private val DayFormat = SimpleDateFormat("MMM d", Locale.getDefault())
private val SessionFormat = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())

/**
 * The season at a glance: every weekend by month. In the season under way, the weekends still
 * to come sit under an Upcoming heading, the one under way or next marked, with its sessions in
 * local time. Tap a weekend to open it.
 */
@Composable
fun CalendarScreen(app: AppState) {
    val now = System.currentTimeMillis()
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val scrolled by remember {
        derivedStateOf { if (list.firstVisibleItemIndex > 0) Float.MAX_VALUE else list.firstVisibleItemScrollOffset.toFloat() }
    }
    val span = with(density) { 50.dp.toPx() }
    var remindersOpen by remember { androidx.compose.runtime.mutableStateOf(app.takeRemindersRequest()) }
    val year = app.calendarSeason
    val current = year == app.season
    val classic = year < AppState.FIRST_WEEKEND_SEASON
    val weekends = (if (current) app.weekends.toList() else app.pastWeekends[year].orEmpty()).sortedBy { it.startMs }
    // The one under way, else the next to come: its sessions are listed under it (this season only).
    val focus = if (!current) null else weekends.firstOrNull { it.isCurrent(now) } ?: weekends.firstOrNull { it.startMs > now }
    LaunchedEffect(focus?.meetingKey) { focus?.let(app::loadWeekend) }
    // The list's rows: the finished part of the season, then (this season) the Upcoming heading
    // and the rest, each part grouped by month. Nothing is dimmed: finished weekends are the ones
    // there are replays of.
    val (done, ahead) = if (current) weekends.partition { it.isDone(now) } else weekends to emptyList()
    val rows = buildList<CalendarRow> {
        fun months(part: List<WeekendInfo>, tag: String) = part.groupBy { MonthFormat.format(Date(it.startMs)) }.forEach { (month, inMonth) ->
            add(CalendarRow.Month(month, "$tag$month"))
            inMonth.forEach { add(CalendarRow.Weekend(it)) }
        }
        months(done, "")
        if (ahead.isNotEmpty()) add(CalendarRow.Upcoming)
        months(ahead, "next-")
    }
    // Opens on what's coming up rather than round 1.
    LaunchedEffect(weekends.size, year) {
        val index = rows.indexOf(CalendarRow.Upcoming)
        // +1 for the page's title item; a little of the last finished weekend stays in view.
        if (index > 2 && !classic) list.scrollToItem(index + 1, -with(density) { 90.dp.roundToPx() }) else list.scrollToItem(0)
    }

    Box(Modifier.fillMaxSize().background(Ink.background)) {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            item {
                Spacer(Modifier.statusBarsPadding().height(60.dp))
                Text(if (classic) "$year season" else "$year calendar", style = Type.largeTitle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                SeasonPicker(app)
                val empty = if (classic) app.classicVideos[year] == null else weekends.isEmpty()
                if (empty) Text("Loading…", style = Type.subhead, modifier = Modifier.padding(20.dp))
            }
            if (classic) {
                // Before 2018: the season's full race replays, then its highlights and shows.
                val videos = app.classicVideos[year].orEmpty()
                val (replays, others) = videos.partition { it.subtitle == "Replay" }
                listOf("Full races" to replays, "Highlights, reviews and documentaries" to others).forEach { (heading, group) ->
                    if (group.isEmpty()) return@forEach
                    item(key = "h-$heading") {
                        Text(heading, style = Type.title2, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 6.dp))
                    }
                    group.forEach { v -> item(key = v.contentId) { VideoRow(app, v) } }
                }
            }
            if (!classic) rows.forEach { row ->
                when (row) {
                    is CalendarRow.Month -> item(key = "month-${row.key}") {
                        Text(row.name, style = Type.title2, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 6.dp))
                    }
                    CalendarRow.Upcoming -> item(key = "upcoming") {
                        Text("Upcoming", style = Type.largeTitle, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 44.dp))
                    }
                    is CalendarRow.Weekend -> item(key = row.w.meetingKey) {
                        WeekendRow(app, row.w, now, focus = row.w == focus, thisSeason = current)
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
        TopBar(
            title = "Calendar",
            fill = { (scrolled / span).coerceIn(0f, 1f) },
            titleIn = { ((scrolled - span * 0.6f) / (span * 0.4f)).coerceIn(0f, 1f) },
            leading = { backdrop -> GlyphButton(Icons.Rounded.ArrowBackIosNew, "Back", backdrop = backdrop) { app.back() } },
            // Reminders sit in the bar, always in reach however far the season is scrolled.
            trailing = { backdrop ->
                GlyphButton(
                    if (app.remindersOn) Icons.Rounded.NotificationsActive
                    else Icons.Rounded.NotificationsNone,
                    "Session reminders",
                    backdrop = backdrop,
                ) { remindersOpen = true }
            },
        )
    }
    if (remindersOpen) RemindersSheet(app) { remindersOpen = false }
}

/** The reminder switches, in a sheet from the bell. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RemindersSheet(app: AppState, close: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = close,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.background,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text("Session reminders", style = Type.title2, modifier = Modifier.padding(horizontal = 20.dp))
            Reminders(app)
        }
    }
}

@Composable
private fun WeekendRow(app: AppState, w: WeekendInfo, now: Long, focus: Boolean, thisSeason: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.99f) { app.open(Screen.Weekend(w)) }
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row {
            Text(
                w.round?.let { "$it" } ?: "T",
                style = Type.title2.copy(color = Ink.secondary),
                textAlign = TextAlign.End,
                modifier = Modifier.width(40.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(w.name, style = Type.headline.copy(color = Ink.primary))
                Text(
                    "${w.place} · ${DayFormat.format(Date(w.startMs))} – ${DayFormat.format(Date(w.endMs))}",
                    style = Type.subhead.copy(color = Ink.secondary),
                )
            }
            when {
                !thisSeason -> Unit
                w.isCurrent(now) -> Text("This weekend", style = Type.footnote.copy(color = LIVE_RED))
                focus -> Text(countdown(w.startMs - now), style = Type.footnote.copy(color = Ink.primary))
            }
        }
        // The weekend that matters now lists its sessions, local time.
        if (focus) {
            val sessions = app.weekendContent[w.meetingKey]?.sessions.orEmpty().filter { it.startMs > 0 }
            Column(Modifier.padding(start = 56.dp, top = 8.dp)) {
                sessions.forEach { s ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(s.title, style = Type.subhead.copy(color = if (s.startMs < now) Ink.secondary else Ink.primary), modifier = Modifier.weight(1f))
                        Text(SessionFormat.format(Date(s.startMs)), style = Type.subhead)
                    }
                }
            }
        }
    }
}

private val LIVE_RED = Color(0xFFFF453A)

/** "In 4 days", "In 6 hours", "Soon". */
private fun countdown(ms: Long): String {
    val hours = ms / 3_600_000
    return when {
        hours >= 48 -> "In ${hours / 24} days"
        hours >= 1 -> "In $hours hour${if (hours == 1L) "" else "s"}"
        else -> "Soon"
    }
}

/**
 * The reminders switch: 15 minutes before each session of the coming weekend. Turning it on asks
 * for Android's notification permission first.
 */
@Composable
private fun Reminders(app: AppState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) app.chooseReminders(true) }
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(Ink.surface)
            .padding(vertical = 4.dp)
    ) {
        SwitchRow("Session reminders", app.remindersOn) { on ->
            val needsPermission = on && android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (needsPermission) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) else app.chooseReminders(on)
        }
        if (app.remindersOn) {
            SwitchRow("Only qualifying and races", app.remindersMainOnly, app::chooseRemindersMainOnly)
        }
    }
    Text(
        "A notification 15 minutes before each session of the coming weekend.",
        style = Type.footnote,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 8.dp),
    )
}

/**
 * The seasons, newest first, as a row of years: the one shown in white, the rest grey. Seasons
 * from 2018 have race weekends; earlier ones are F1 TV's archive of replays and highlights.
 */
@Composable
private fun SeasonPicker(app: AppState) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        items(app.archiveSeasons.size) { i ->
            val year = app.archiveSeasons[i]
            val chosen = year == app.calendarSeason
            Text(
                "$year",
                style = Type.headline.copy(color = if (chosen) Ink.primary else Ink.secondary),
                modifier = Modifier.pressable { app.chooseCalendarSeason(year) }.padding(vertical = 8.dp),
            )
        }
    }
}

/** A classic-season video: its title and length; tap to watch. */
@Composable
private fun VideoRow(app: AppState, v: CatalogItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.99f) { app.openPicker(v.contentId, v.title, v.image) }
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(v.title, style = Type.headline)
            v.subtitle?.takeIf { it != "Replay" }?.let { Text(it, style = Type.subhead) }
        }
        val minutes = v.durationSec / 60
        if (minutes > 0) Text(if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m", style = Type.subhead)
    }
}

/** One row of the calendar list. */
private sealed interface CalendarRow {
    data class Month(val name: String, val key: String) : CalendarRow
    data object Upcoming : CalendarRow
    data class Weekend(val w: WeekendInfo) : CalendarRow
}
