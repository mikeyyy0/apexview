package app.apexview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.request.ImageRequest
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clipToBounds
import coil.imageLoader
import kotlinx.coroutines.delay
import kotlin.math.floor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(12.dp)
private val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private val ButtonShape = RoundedCornerShape(14.dp)
private val SidePadding = 20.dp

// Pieces.

/** White on black, Apple TV's primary action. */
@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier
            .pressable(enabled = enabled, onClick = onClick)
            .clip(ButtonShape)
            .background(if (enabled) Ink.primary else Ink.surfaceHigh)
            .heightIn(min = 50.dp)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (enabled) Color.Black else Ink.tertiary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.headline.copy(color = color))
    }
}

/** Translucent dark, for actions over artwork. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .pressable(onClick = onClick)
            .clip(ButtonShape)
            .background(Color.White.copy(alpha = 0.16f))
            .heightIn(min = 50.dp)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.headline)
    }
}

/** A round glyph button that floats over artwork. */
@Composable
fun GlyphButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, backdrop: Float = 1f, onClick: () -> Unit) {
    Box(
        modifier
            .pressable(scaleTo = 0.9f, onClick = onClick)
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f * backdrop)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Ink.primary, modifier = Modifier.size(20.dp))
    }
}

/** Red dot and the word, no badge box. */
@Composable
fun LiveMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.live))
        Spacer(Modifier.width(6.dp))
        Text("LIVE", style = Type.caption.copy(color = Ink.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
    }
}

/** 16:9 artwork with its title and one line under it. */
@Composable
private fun ArtCard(
    image: String?,
    title: String,
    subtitle: String?,
    width: Dp,
    live: Boolean = false,
    dimmed: Boolean = false,
    /** Not started yet: gray artwork, faded, and nothing to tap. */
    upcoming: Boolean = false,
    /** How much has been watched, 0..1, drawn as a bar along the artwork's foot. */
    progress: Float? = null,
    /** Set when tapping the card opens a page whose artwork this image grows into. */
    artKey: String? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(width)
            .pressable(enabled = !upcoming, onClick = onClick)
            .graphicsLayer { alpha = if (dimmed || upcoming) 0.55f else 1f }
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).sharedArt(artKey, cornerRadius = 12).clip(CardShape).background(Ink.surface)) {
            AsyncImage(
                model = F1TvApi.picture(image, 640, 360),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (upcoming) Grayscale else null,
                modifier = Modifier.fillMaxSize(),
            )
            if (live) LiveMark(Modifier.align(Alignment.TopStart).padding(10.dp))
            progress?.let { done ->
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(Modifier.fillMaxWidth(done.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, style = Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A titled horizontal row. Card width leaves the next card peeking, so the row reads as
 * scrollable. Its scroll position is kept on the app state, so coming back to the page finds it
 * where it was (and a card's artwork can shrink back into it).
 */
@Composable
private fun <T> Shelf(
    app: AppState,
    title: String,
    items: List<T>,
    /** Names the row's kept scroll position; rows on different pages can share a title. */
    key: String = title,
    /** Portrait artwork, for shows and documentaries: more to a row. */
    posters: Boolean = false,
    /** A link by the title, such as View all. */
    action: Pair<String, () -> Unit>? = null,
    card: @Composable (T, Dp) -> Unit,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        val perRow = if (posters) (if (wide) 5.3f else 2.6f) else if (wide) 3.3f else 1.7f
        val cardWidth = ((maxWidth - SidePadding * 2 - 12.dp * perRow.toInt()) / perRow).coerceIn(if (posters) 120.dp else 200.dp, 360.dp)
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = SidePadding, end = SidePadding, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(title, style = Type.title2, modifier = Modifier.weight(1f))
                action?.let { (label, onClick) ->
                    Text(label, style = Type.subhead, modifier = Modifier.pressable(onClick = onClick).padding(start = 12.dp, top = 4.dp))
                }
            }
            LazyRow(
                state = app.rowState(key),
                contentPadding = PaddingValues(horizontal = SidePadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items) { card(it, cardWidth) }
            }
        }
    }
}

/**
 * The bar across the top of a page. Clear at rest, so its buttons float over the artwork; it
 * fills in dark and translucent as the page scrolls ([fill], 0..1), and the page [title] fades in
 * once the page's own big title has gone under it ([titleIn]). Content scrolling beneath stays
 * faintly visible through it. Modeled on Apple's TV app.
 */
@Composable
internal fun TopBar(
    title: String,
    fill: () -> Float,
    titleIn: () -> Float,
    leading: (@Composable (backdrop: Float) -> Unit)? = null,
    trailing: (@Composable (backdrop: Float) -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.82f * fill())) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(60.dp)
            .padding(horizontal = 12.dp)
    ) {
        leading?.let { Box(Modifier.align(Alignment.CenterStart)) { it(1f - fill()) } }
        Text(
            title,
            style = Type.headline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp)
                .graphicsLayer { alpha = titleIn() },
        )
        trailing?.let { Box(Modifier.align(Alignment.CenterEnd)) { it(1f - fill()) } }
    }
}

/**
 * A page led by artwork, after Apple's TV app: the artwork stays put while the page scrolls up
 * over it, darkening as it goes, and the top bar forms out of nothing as you scroll. [artKey]
 * links the artwork to the card it was opened from, so one grows into the other.
 */
@Composable
private fun ArtPage(
    image: String?,
    artKey: String?,
    title: String,
    listState: LazyListState,
    leading: (@Composable (backdrop: Float) -> Unit)? = null,
    trailing: (@Composable (backdrop: Float) -> Unit)? = null,
    /** In place of the one image: artwork that changes, as on home's carousel. */
    backdrop: (@Composable () -> Unit)? = null,
    /** In place of [hero]'s column: the whole hero area, for content that fills it. */
    heroBox: (@Composable BoxScope.() -> Unit)? = null,
    hero: @Composable ColumnScope.() -> Unit = {},
    rows: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.background)) {
        val heroHeight = minOf(maxWidth * 9f / 16f, screenHeight * 0.62f).coerceAtLeast(320.dp)
        val heroPx = with(density) { heroHeight.toPx() }
        // How far the page has scrolled, capped once the artwork is fully covered.
        val scrolled by remember(heroPx) {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) heroPx
                else listState.firstVisibleItemScrollOffset.toFloat().coerceAtMost(heroPx)
            }
        }

        Box(Modifier.fillMaxWidth().height(heroHeight)) {
            if (backdrop != null) backdrop()
            else AsyncImage(
                // The card's smaller image, already in memory, stands in until this size loads.
                model = remember(image) {
                    ImageRequest.Builder(context)
                        .data(F1TvApi.picture(image, 1920, 1080))
                        .placeholderMemoryCacheKey(F1TvApi.picture(image, 640, 360))
                        .crossfade(Motion.FADE_MS)
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().sharedArt(artKey),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.25f to Color.Transparent,
                        0.5f to Color.Transparent,
                        1f to Ink.background,
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.6f to Color.Transparent)
                )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.75f * (scrolled / heroPx) }
                    .background(Color.Black)
            )
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    if (heroBox != null) heroBox()
                    else Column(
                        Modifier.align(Alignment.BottomStart).padding(horizontal = SidePadding, vertical = 8.dp).widthIn(max = 560.dp),
                        content = hero,
                    )
                }
            }
            rows()
        }

        TopBar(
            title = title,
            fill = { ((scrolled - heroPx * 0.35f) / (heroPx * 0.35f)).coerceIn(0f, 1f) },
            titleIn = { ((scrolled - heroPx * 0.62f) / (heroPx * 0.2f)).coerceIn(0f, 1f) },
            leading = leading,
            trailing = trailing,
        )
    }
}

private val DayFormat = SimpleDateFormat("MMM d", Locale.getDefault())

private fun dates(w: WeekendInfo) = "${DayFormat.format(Date(w.startMs))} – ${DayFormat.format(Date(w.endMs))}"

private fun roundLabel(w: WeekendInfo) = w.round?.let { "Round $it" } ?: "Testing"

/** When it runs and how long, or just when for one that hasn't started. */
private fun sessionLine(item: CatalogItem) =
    if (item.upcoming) item.subtitle.orEmpty()
    else listOfNotNull(item.subtitle, duration(item.durationSec)).joinToString(" · ")

private fun duration(seconds: Int): String? {
    if (seconds <= 0) return null
    val m = seconds / 60
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

// Screens.

@Composable
fun SignInScreen(app: AppState) {
    var useToken by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Ink.background)) {
        AsyncImage(
            model = F1TvApi.picture(app.featuredWeekend()?.image, 1920, 1080),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.7f },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 0.85f to Ink.background)))
        Column(
            Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(SidePadding).widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Apexview", style = Type.largeTitle)
            Text("Every feed of every session, arranged the way you want. Sign in with your F1 TV account.", style = Type.subhead)
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Sign in", modifier = Modifier.fillMaxWidth(), enabled = !app.busy, onClick = app::startLogin)
            if (useToken) {
                OutlinedTextField(
                    value = app.pastedToken,
                    onValueChange = { app.pastedToken = it },
                    placeholder = { Text("Subscription token", style = Type.body.copy(color = Ink.tertiary)) },
                    singleLine = true,
                    textStyle = Type.body,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink.secondary,
                        unfocusedBorderColor = Ink.surfaceHigh,
                        cursorColor = Ink.primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton("Use token", Modifier.fillMaxWidth(), onClick = app::usePastedToken)
            } else {
                Text(
                    "Use a token instead",
                    style = Type.subhead,
                    modifier = Modifier.pressable { useToken = true }.padding(vertical = 8.dp),
                )
            }
            app.message?.let { Text(it, style = Type.footnote.copy(color = Ink.live)) }
        }
    }
}

@Composable
fun HomeScreen(app: AppState) {
    val now = System.currentTimeMillis()
    val latest = app.latestWeekend(now)
    val latestContent = latest?.let { app.weekendContent[it.meetingKey] }
    val upcoming = app.weekends.filter { it.startMs > now }
    val picks = app.suggestions(now).ifEmpty {
        listOfNotNull(app.featuredWeekend(now)?.let { Pick("${roundLabel(it)} · ${dates(it)}", weekend = it) })
    }
    val count = picks.size
    // Endless both ways: the pager starts among many copies of the picks.
    val pager = key(count) { rememberPagerState(initialPage = if (count > 1) count * 500 else 0) { if (count > 1) count * 1000 else count } }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    // Turns on its own, and waits a full turn again after you swipe.
    LaunchedEffect(pager, pager.settledPage, dragged) {
        if (count < 2 || dragged) return@LaunchedEffect
        delay(CAROUSEL_MS)
        pager.animateScrollToPage(pager.currentPage + 1, animationSpec = tween(Motion.SCREEN_MS * 2, easing = EaseOut))
    }
    // Every slide's artwork loads ahead, so turning never waits on it.
    val context = LocalContext.current
    LaunchedEffect(picks) {
        picks.forEach { pick ->
            F1TvApi.picture(pickImage(pick), 1920, 1080)?.let { context.imageLoader.enqueue(ImageRequest.Builder(context).data(it).build()) }
        }
    }

    ArtPage(
        image = null,
        artKey = null,
        title = "Apexview",
        listState = app.homeList,
        trailing = { backdrop ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlyphButton(Icons.Rounded.CalendarMonth, "Calendar and past seasons", backdrop = backdrop) { app.open(Screen.Calendar) }
                GlyphButton(Icons.Rounded.Settings, "Settings", backdrop = backdrop) { app.open(Screen.Settings) }
            }
        },
        backdrop = { CarouselArt(picks, pager) },
        heroBox = {
            if (count > 0) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = SidePadding, end = SidePadding, top = 8.dp, bottom = if (count > 1) 30.dp else 8.dp)
                                .widthIn(max = 560.dp)
                        ) { PickHero(app, picks[page.mod(count)]) }
                    }
                }
                if (count > 1) PageDots(count, pager, Modifier.align(Alignment.BottomStart).padding(start = SidePadding, bottom = 8.dp))
            }
        },
    ) {
        app.homeError?.let { error ->
            item {
                Column(Modifier.padding(SidePadding)) {
                    Text(error, style = Type.subhead)
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton("Try again", onClick = app::loadHome)
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }

        app.recents.toList().takeIf { it.isNotEmpty() && app.tiles.isEmpty() }?.let { recents ->
            item {
                Shelf(app, "Continue watching", recents) { it, width ->
                    val left = (it.durationMs - it.positionMs).coerceAtLeast(0) / 1000
                    val weekend = it.title.substringBefore(" · ")
                    ArtCard(
                        it.image,
                        it.title.substringAfter(" · "),
                        if (it.live) "$weekend · Live"
                        else "$weekend · " + (if (left >= 3600) "${left / 3600}h ${left / 60 % 60}m left" else "${left / 60}m left"),
                        width,
                        live = it.live,
                        progress = if (it.live || it.durationMs <= 0) null else it.positionMs.toFloat() / it.durationMs,
                        onClick = { app.resumeWatching(it) },
                    )
                }
                Spacer(Modifier.height(28.dp))
            }
        }

        // The carousel leads with what's live; a row only when there's more than one.
        if (app.liveNow.size > 1) {
            item {
                Shelf(app, "Live now", app.liveNow.toList()) { item, width ->
                    ArtCard(item.image, item.title, item.subtitle, width, live = true) {
                        app.openPicker(item.contentId, listOfNotNull(item.subtitle, item.title).joinToString(" · "), item.image)
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }

        if (latest != null && latestContent != null && latestContent.sessions.isNotEmpty()) {
            item {
                Shelf(app, latest.name, latestContent.sessions.reversed()) { item, width ->
                    ArtCard(item.image, item.title, sessionLine(item), width, live = item.live, upcoming = item.upcoming) {
                        app.openPicker(item.contentId, "${latest.name} · ${item.title}", item.image)
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }

        // F1 TV's own rows, each video once. The weekend's rows come first (its shows, press
        // conferences, F1 Academy), then F1 TV's catch-all row for the weekend.
        val f1Rows = app.homePage?.rows.orEmpty()
        val shown = latestContent?.sessions.orEmpty().map { it.contentId }.toMutableSet()
        fun unique(row: CatalogRow) = row.items.filter { it.pageId != null || shown.add(it.contentId) }
        val weekendRows = f1Rows.filter { r ->
            r.tag == CatalogRow.WEEKEND || (r.tag != CatalogRow.ARCHIVE && !r.posters && r.items.any { it.meeting.isNotEmpty() && it.meeting == latest?.name })
        }.sortedBy { it.tag == CatalogRow.WEEKEND }
        weekendRows.forEach { row ->
            val items = unique(row)
            val title = if (row.tag == CatalogRow.WEEKEND) "More from the weekend" else rowTitle(row.title)
            item { VideoShelf(app, title, items) }
        }

        item {
            Shelf(app, "${app.season} season", app.pastWeekends(now)) { w, width ->
                val key = "card-${w.meetingKey}"
                ArtCard(w.image, w.name, "${roundLabel(w)} · ${w.place}", width, artKey = key) {
                    app.open(Screen.Weekend(w, key))
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        item {
            Shelf(app, "Coming up", upcoming) { w, width ->
                val key = "card-${w.meetingKey}"
                ArtCard(w.image, w.name, dates(w), width, dimmed = true, artKey = key) {
                    app.open(Screen.Weekend(w, key))
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        f1Rows.firstOrNull { it.tag == CatalogRow.ARCHIVE }?.let { row ->
            val items = unique(row)
            item { VideoShelf(app, "Popular races from the archive", items, action = "View all" to { app.open(Screen.Archive) }) }
        }

        item {
            SeasonsShelf(app)
            Spacer(Modifier.height(28.dp))
        }

        (f1Rows.filter { it.posters } + f1Rows.filter { !it.posters && it.tag != CatalogRow.ARCHIVE && it !in weekendRows }).forEach { row ->
            val items = unique(row)
            item { VideoShelf(app, rowTitle(row.title), items, posters = row.posters) }
        }
    }
}

private const val CAROUSEL_MS = 7_000L

/** F1 TV's row names in the app's sentence case, where they're the usual ones. */
private fun rowTitle(label: String) = when (label) {
    "Shows & Analysis" -> "Shows and analysis"
    "Press Conferences" -> "Press conferences"
    else -> label
}

private fun pickImage(pick: Pick) = pick.item?.image ?: pick.resume?.image ?: pick.weekend?.image

/** A row of F1 TV videos (or, as [posters], its shows and documentaries), and the space after it. */
@Composable
private fun VideoShelf(app: AppState, title: String, items: List<CatalogItem>, key: String = title, posters: Boolean = false, action: Pair<String, () -> Unit>? = null) {
    if (items.isEmpty()) return
    Shelf(app, title, items, key = key, posters = posters, action = action) { item, width ->
        if (posters) PosterCard(item, width) { app.watch(item) }
        else ArtCard(item.image, item.title, sessionLine(item), width, live = item.live, upcoming = item.upcoming) { app.watch(item) }
    }
    Spacer(Modifier.height(28.dp))
}

/** A show's or documentary's portrait artwork, which carries its own title. */
@Composable
private fun PosterCard(item: CatalogItem, width: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .pressable(onClick = onClick)
            .clip(CardShape)
            .background(Ink.surface)
    ) {
        AsyncImage(
            model = F1TvApi.picture(item.image, 400, 600),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Every season as a row of years; each opens the calendar on that season. */
@Composable
private fun SeasonsShelf(app: AppState) {
    Column {
        Text("Every season", style = Type.title2, modifier = Modifier.padding(horizontal = SidePadding))
        Text(
            "Full races back to ${app.archiveSeasons.last()}",
            style = Type.subhead,
            modifier = Modifier.padding(start = SidePadding, end = SidePadding, top = 2.dp, bottom = 4.dp),
        )
        LazyRow(
            state = app.rowState("seasons"),
            contentPadding = PaddingValues(horizontal = SidePadding),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(app.archiveSeasons) { year ->
                Text(
                    "$year",
                    style = Type.title1,
                    modifier = Modifier
                        .pressable {
                            app.chooseCalendarSeason(year)
                            app.open(Screen.Calendar)
                        }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The carousel's artwork. It slides with the pager underneath the hero text, page for page, while
 * staying put as the page scrolls up over it like every other page's artwork.
 */
@Composable
private fun CarouselArt(picks: List<Pick>, pager: PagerState) {
    if (picks.isEmpty()) return
    val context = LocalContext.current
    val base by remember(pager) { derivedStateOf { floor(pager.currentPage + pager.currentPageOffsetFraction).toInt() } }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        for (page in base..base + 1) {
            key(page) {
                val image = pickImage(picks[page.mod(picks.size)])
                AsyncImage(
                    model = remember(image) {
                        ImageRequest.Builder(context)
                            .data(F1TvApi.picture(image, 1920, 1080))
                            .placeholderMemoryCacheKey(F1TvApi.picture(image, 640, 360))
                            .crossfade(Motion.FADE_MS)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = (page - pager.currentPage - pager.currentPageOffsetFraction) * size.width },
                )
            }
        }
    }
}

/** One slide's words and buttons: why it's here, what it is, and how to watch it. */
@Composable
private fun PickHero(app: AppState, pick: Pick) {
    val item = pick.item
    val resume = pick.resume
    val weekend = pick.weekend
    if (item?.live == true) LiveMark(Modifier.padding(bottom = 6.dp))
    else Text(pick.reason, style = Type.footnote.copy(color = Ink.primary.copy(alpha = 0.8f)))
    val title = when {
        item != null -> item.title
        resume != null -> resume.title.substringBefore(" · ")
        else -> weekend?.name.orEmpty()
    }
    val line = when {
        item != null -> listOfNotNull(
            item.meeting.takeIf { it.isNotEmpty() && !title.contains(it.substringBefore(" Grand Prix")) },
            item.subtitle.takeIf { it != "Live" },
            duration(item.durationSec),
        ).joinToString(" · ")
        resume != null -> {
            val left = (resume.durationMs - resume.positionMs).coerceAtLeast(0) / 1000
            listOfNotNull(
                resume.title.substringAfter(" · ", "").ifEmpty { null },
                if (left >= 3600) "${left / 3600}h ${left / 60 % 60}m left" else "${left / 60}m left",
            ).joinToString(" · ")
        }
        else -> weekend?.place.orEmpty()
    }
    Text(title, style = Type.largeTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    if (line.isNotEmpty()) Text(line, style = Type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            resume != null -> PrimaryButton("Resume", Icons.Rounded.PlayArrow) { app.resumeWatching(resume) }
            item != null -> {
                PrimaryButton(if (item.live) "Watch live" else "Play", Icons.Rounded.PlayArrow) { app.watch(item) }
                app.weekendOf(item)?.let { w -> SecondaryButton("Weekend") { app.open(Screen.Weekend(w)) } }
            }
            weekend != null -> {
                val ahead = weekend.startMs > System.currentTimeMillis() || app.nextSession(weekend) != null
                PrimaryButton(if (weekend.startMs > System.currentTimeMillis()) "Schedule" else "Sessions") {
                    app.open(Screen.Weekend(weekend))
                }
                // Sessions still to come: a way to be reminded of them.
                if (ahead) SecondaryButton(if (app.remindersOn) "Reminders on" else "Notify me") { app.openReminders() }
            }
        }
    }
}

/** Which slide is showing: small dots, the current one white. */
@Composable
private fun PageDots(count: Int, pager: PagerState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val on = pager.currentPage.mod(count) == i
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) Ink.primary else Ink.tertiary))
        }
    }
}

@Composable
fun WeekendScreen(app: AppState, weekend: WeekendInfo, artKey: String?) {
    val content = app.weekendContent[weekend.meetingKey]
    ArtPage(
        image = weekend.image,
        artKey = artKey,
        title = weekend.name,
        listState = rememberLazyListState(),
        leading = { backdrop -> GlyphButton(Icons.Rounded.ArrowBackIosNew, "Back", backdrop = backdrop) { app.back() } },
        hero = {
            Text("${roundLabel(weekend)} · ${dates(weekend)}", style = Type.footnote.copy(color = Ink.primary.copy(alpha = 0.8f)))
            Text(weekend.name, style = Type.largeTitle, modifier = Modifier.padding(top = 2.dp))
            Text(weekend.place, style = Type.subhead)
        },
    ) {
        item { Spacer(Modifier.height(20.dp)) }
        item {
            AnimatedVisibility(content == null, enter = fadeIn(tween(Motion.FADE_MS)), exit = fadeOut(tween(Motion.FADE_MS))) {
                Text("Loading sessions…", style = Type.subhead, modifier = Modifier.padding(horizontal = SidePadding))
            }
        }
        if (content != null) {
            if (content.sessions.isEmpty() && content.videos.isEmpty()) {
                item {
                    Text(
                        "Sessions show up here once they start.",
                        style = Type.subhead,
                        modifier = Modifier.padding(horizontal = SidePadding),
                    )
                }
            }
            item {
                Shelf(app, "Sessions", content.sessions) { item, width ->
                    ArtCard(item.image, item.title, sessionLine(item), width, live = item.live, upcoming = item.upcoming) {
                        app.openPicker(item.contentId, "${weekend.name} · ${item.title}", item.image)
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
            item {
                Shelf(app, "Highlights and shows", content.videos) { item, width ->
                    ArtCard(item.image, item.title, sessionLine(item), width, live = item.live, upcoming = item.upcoming) {
                        app.openPicker(item.contentId, item.title, item.image)
                    }
                }
            }
        }
    }
}

/** A show's or documentary's page: its episodes, a row per season. */
@Composable
fun CollectionScreen(app: AppState, pageId: Int, title: String) {
    val page = app.collections[pageId]
    ArtPage(
        image = page?.image,
        artKey = null,
        title = title,
        listState = rememberLazyListState(),
        leading = { backdrop -> GlyphButton(Icons.Rounded.ArrowBackIosNew, "Back", backdrop = backdrop) { app.back() } },
        hero = {
            Text(page?.title?.ifBlank { null } ?: title, style = Type.largeTitle)
            page?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Type.subhead, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        },
    ) {
        item { Spacer(Modifier.height(20.dp)) }
        if (page == null) item { Text("Loading…", style = Type.subhead, modifier = Modifier.padding(horizontal = SidePadding)) }
        page?.rows?.forEach { row -> item { VideoShelf(app, row.title, row.items, key = "c$pageId-${row.title}") } }
    }
}

/** The races F1 TV viewers watch most, a row per season, and the way to every season. */
@Composable
fun ArchiveScreen(app: AppState) {
    val races = app.archive
    ArtPage(
        image = races?.firstOrNull()?.image,
        artKey = null,
        title = "From the archive",
        listState = rememberLazyListState(),
        leading = { backdrop -> GlyphButton(Icons.Rounded.ArrowBackIosNew, "Back", backdrop = backdrop) { app.back() } },
        hero = {
            Text("From the archive", style = Type.largeTitle)
            Text("The races F1 TV viewers watch most. Every season since ${app.archiveSeasons.last()} is in the calendar.", style = Type.subhead)
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Every season") { app.open(Screen.Calendar) }
        },
    ) {
        item { Spacer(Modifier.height(20.dp)) }
        if (races == null) item { Text("Loading…", style = Type.subhead, modifier = Modifier.padding(horizontal = SidePadding)) }
        races.orEmpty().groupBy { it.year }.toSortedMap(compareByDescending { it }).forEach { (year, list) ->
            item { VideoShelf(app, if (year > 0) "$year" else "More races", list, key = "archive-$year") }
        }
    }
}
