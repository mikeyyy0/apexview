package app.apexview

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Rounded = RoundedCornerShape(14.dp)

/** What each broadcast feed is, in plain words. */
private fun describe(channel: Channel): Pair<String, String?> = when (channel.title.uppercase()) {
    "F1 LIVE" -> "F1 Live" to "Main broadcast with timing and analysis"
    "INTERNATIONAL" -> "International" to "The world feed, as shown on TV"
    "TRACKER" -> "Driver tracker" to "Every car on a live map"
    "DATA" -> "Data" to "Timing tower and gaps"
    else -> channel.title.lowercase().replaceFirstChar { it.uppercase() } to null
}

/**
 * Picks a session's feeds. Broadcast feeds first, then every driver's onboard. Picked cards turn
 * white; feeds already playing say so.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedPickerSheet(app: AppState, picker: FeedPicker) {
    ModalBottomSheet(
        onDismissRequest = app::closePicker,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        // Over the viewer (adding a feed), the status bar stays hidden; while browsing it shows.
        KeepSystemBarsHidden(enabled = app.tiles.isNotEmpty() && !app.browsing)
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            ) {
                Text(picker.title, style = Type.title2)
                Text(
                    picker.note ?: if (app.tiles.isEmpty()) "Pick up to 4. You can switch any of them while watching."
                    else "Feeds on screen are already picked. Unpick one to make room for another.",
                    style = Type.footnote.copy(color = if (picker.note != null) Ink.primary else Ink.secondary),
                    modifier = Modifier.padding(top = 4.dp),
                )

                val channels = picker.channels
                when {
                    picker.error != null -> Text(picker.error!!, style = Type.subhead, modifier = Modifier.padding(vertical = 24.dp))
                    channels == null -> Text("Loading feeds…", style = Type.subhead, modifier = Modifier.padding(vertical = 24.dp))
                    else -> {
                        val broadcast = channels.filter { it.racingNumber == null }
                        val onboards = channels.filter { it.racingNumber != null }

                        if (broadcast.isNotEmpty()) {
                            Text("Broadcast", style = Type.headline, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                broadcast.forEach { channel ->
                                    val (name, about) = describe(channel)
                                    PickCard(app, picker, channel, Modifier.widthIn(min = 150.dp, max = 220.dp)) { onColor ->
                                        Text(name, style = Type.headline.copy(color = onColor))
                                        about?.let { Text(it, style = Type.footnote.copy(color = onColor.copy(alpha = 0.65f))) }
                                    }
                                }
                            }
                        }
                        if (onboards.isNotEmpty()) {
                            Text("Onboards", style = Type.headline, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                onboards.forEach { channel ->
                                    PickCard(app, picker, channel, Modifier.width(104.dp)) { onColor ->
                                        Text(
                                            "${channel.racingNumber}",
                                            style = Type.title1.copy(color = channel.teamColor?.let { Color(it) } ?: onColor),
                                        )
                                        Text(channel.tla ?: "", style = Type.headline.copy(color = onColor))
                                        Text(channel.driverName ?: "", style = Type.caption.copy(color = onColor.copy(alpha = 0.65f)), maxLines = 1)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            val adds = picker.selected.size
            val drops = picker.removing.size
            PrimaryButton(
                when {
                    adds == 0 && drops == 0 -> "Pick a feed"
                    app.tiles.isEmpty() -> if (adds == 1) "Watch 1 feed" else "Watch $adds feeds"
                    drops == 0 -> if (adds == 1) "Add 1 feed" else "Add $adds feeds"
                    adds == 0 -> if (drops == 1) "Remove 1 feed" else "Remove $drops feeds"
                    else -> "Update feeds"
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                enabled = adds > 0 || drops > 0,
                onClick = app::watchPicked,
            )
        }
    }
}

@Composable
private fun PickCard(
    app: AppState,
    picker: FeedPicker,
    channel: Channel,
    modifier: Modifier,
    content: @Composable (onColor: Color) -> Unit,
) {
    val playing = app.isPlaying(picker.contentId, channel)
    val dropping = channel in picker.removing
    // On screen counts as picked until unpicked.
    val picked = channel in picker.selected || (playing && !dropping)
    val background by animateColorAsState(if (picked) Ink.primary else Ink.surfaceHigh, tween(Motion.PRESS_MS * 2, easing = EaseOut), label = "pick")
    val onColor = if (picked) Color.Black else Ink.primary
    Box(
        modifier
            .pressable { app.togglePick(channel) }
            .clip(Rounded)
            .background(background)
            .padding(14.dp)
    ) {
        Column { content(onColor) }
        when {
            playing && !dropping -> Text("On", style = Type.caption.copy(color = Color.Black), modifier = Modifier.align(Alignment.TopEnd))
            dropping -> Text("Off", style = Type.caption, modifier = Modifier.align(Alignment.TopEnd))
            picked -> Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.align(Alignment.TopEnd).size(18.dp))
        }
    }
}

// Settings.

@Composable
private fun Group(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Text(title, style = Type.subhead, modifier = Modifier.padding(start = 36.dp, end = 20.dp, top = 28.dp, bottom = 8.dp))
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(Rounded).background(Ink.surface).padding(vertical = 6.dp)
    ) { content() }
    footer?.let { Text(it, style = Type.footnote, modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 8.dp)) }
}

@Composable
private fun SettingRow(label: String, value: String? = null, valueColor: Color = Ink.secondary, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(scaleTo = 0.99f, onClick = onClick) else Modifier)
            .heightIn(min = 50.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.body, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = Type.body.copy(color = valueColor)) }
        trailing?.invoke()
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(label, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF30D158),
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Ink.surfaceHigh,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun ChoiceRow(label: String, chosen: Boolean, onClick: () -> Unit) {
    SettingRow(label, onClick = onClick) {
        if (chosen) Icon(Icons.Rounded.Check, null, tint = Ink.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SettingsScreen(app: AppState) {
    val s = app.settings
    val list = rememberLazyListState()
    val density = LocalDensity.current
    // The large title is about 50dp tall: the bar fills over its first half and takes the title over.
    val scrolled by remember {
        derivedStateOf { if (list.firstVisibleItemIndex > 0) Float.MAX_VALUE else list.firstVisibleItemScrollOffset.toFloat() }
    }
    val span = with(density) { 50.dp.toPx() }
    Box(Modifier.fillMaxSize().background(Ink.background)) {
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item {
            Spacer(Modifier.statusBarsPadding().height(60.dp))
            Text("Settings", style = Type.largeTitle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        item {
            Group("Account") {
                SettingRow("F1 TV", app.tier.lowercase().replaceFirstChar { it.uppercase() })
                SettingRow("Manage F1 TV account", onClick = app::openAccountPage) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Opens in your browser", tint = Ink.secondary, modifier = Modifier.size(18.dp))
                }
                SettingRow("Sign out", onClick = app::signOut, trailing = null)
            }
        }
        item {
            Group("Playback", footer = "Applies to feeds you start from now on.") {
                Text("Commentary", style = Type.footnote, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                Settings.COMMENTARY.forEach { (code, name) ->
                    ChoiceRow(name, s.commentary == code) { s.updateCommentary(code) }
                }
                Spacer(Modifier.height(6.dp))
                SwitchRow("Subtitles", s.subtitles, s::updateSubtitles)
                SwitchRow("Stream stats on tiles", s.showStats, s::updateShowStats)
            }
        }
        item {
            Group(
                "Live races",
                footer = "With 6 laps to go in a live Grand Prix, a note offers to open F1's Driver of the Day vote.",
            ) {
                SwitchRow("Driver of the Day reminder", s.votePrompt, s::updateVotePrompt)
            }
        }
        item {
            Group(
                "Track map",
                footer = "How far the map runs behind the picture. F1 stamps its video about 7 seconds after its timing data.",
            ) {
                SettingRow("Timing offset", "%+.0f s".format(app.mapOffsetMs / 1000f))
                SettingRow("Reset to −7 s", onClick = { app.nudgeMapOffset(-7_000L - app.mapOffsetMs) })
            }
        }
        item {
            Group(
                "Advanced",
                footer = "Software DRM is what desktop browsers use, and it's what lets four feeds play at once. " +
                    "Change the stream type only if feeds won't play.",
            ) {
                SwitchRow("Software DRM", s.softwareDrm, s::updateSoftwareDrm)
                Text("Stream type", style = Type.footnote, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                F1TvApi.PLATFORMS.forEach { platform ->
                    val name = platform.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                        .replace("Dash", "DASH").replace("Hls", "HLS")
                    ChoiceRow(if (platform == "WEB_DASH") "$name (default)" else name, s.platform == platform) {
                        app.choosePlatform(platform)
                    }
                }
            }
        }
        item {
            Group("Updates", footer = "Apexview checks for a new version each time it opens.") {
                SettingRow("Check for updates", onClick = app.updater::checkNow)
            }
        }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            Group(
                "Help",
                footer = "Opens a GitHub issue with your app version and phone model filled in. Nothing is sent until you submit it.",
            ) {
                SettingRow("Report a problem", onClick = { CrashReports.reportProblem(context) })
            }
        }
        item {
            Text(
                "Apexview ${BuildConfig.VERSION_NAME} · built ${BuildConfig.BUILT_AT}",
                style = Type.caption,
                modifier = Modifier.padding(start = 36.dp, top = 28.dp, bottom = 40.dp),
            )
        }
    }
    TopBar(
        title = "Settings",
        fill = { (scrolled / span).coerceIn(0f, 1f) },
        titleIn = { ((scrolled - span * 0.6f) / (span * 0.4f)).coerceIn(0f, 1f) },
        leading = { backdrop -> GlyphButton(Icons.Rounded.ArrowBackIosNew, "Back", backdrop = backdrop) { app.back() } },
    )
    }
}

/**
 * Shown when the account signed in has no F1 TV plan with video, so nothing would play. Says
 * why, and how US viewers with Apple TV get Premium onto their F1 TV account: by linking their
 * Apple account to it on F1 TV. Offers F1 TV's site, or signing in with another account.
 */
@Composable
fun PlanPrompt(app: AppState) {
    if (!app.planPromptOpen) return
    androidx.compose.ui.window.Dialog(onDismissRequest = { app.closePlanPrompt(false) }) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(Ink.surface)
                .padding(24.dp)
        ) {
            val context = androidx.compose.ui.platform.LocalContext.current
            Text("This account can't play video yet", style = Type.title2)
            Text(
                "You're signed in, but this F1 TV account doesn't have a plan that includes video, so F1 won't stream anything to it.",
                style = Type.subhead,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Get F1 TV Premium with Apple TV in the US? Link your Apple account to this F1 TV account on F1 TV's website or app (look for Activate). Once your F1 account shows Premium, reopen Apexview.",
                style = Type.subhead,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                SecondaryButton("Open F1 TV") {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://f1tv.formula1.com"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                PrimaryButton("OK") { app.closePlanPrompt(false) }
            }
            Text(
                "Use another account",
                style = Type.subhead,
                modifier = Modifier.align(Alignment.End).pressable { app.closePlanPrompt(true) }.padding(top = 14.dp),
            )
        }
    }
}
