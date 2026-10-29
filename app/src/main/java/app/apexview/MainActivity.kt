package app.apexview

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.LayoutInflater
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var app: AppState
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        app.onLoginFinished()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReports.install(applicationContext)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val userAgent = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        val chromeMajor = WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.') ?: "140"
        app = AppState(this, lifecycleScope, userAgent, chromeMajor) {
            login.launch(Intent(this, LoginActivity::class.java))
        }
        setContent { AppTheme { Root(app) } }
        app.restore()
        app.updater.checkOnLaunch()
        // The fold's posture: half-open with the hinge running across the screen is laptop mode.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                androidx.window.layout.WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { info ->
                        val fold = info.displayFeatures.filterIsInstance<androidx.window.layout.FoldingFeature>().firstOrNull {
                            it.state == androidx.window.layout.FoldingFeature.State.HALF_OPENED &&
                                it.orientation == androidx.window.layout.FoldingFeature.Orientation.HORIZONTAL
                        }
                        app.updatePosture(fold?.bounds?.let { it.top.toFloat() to it.bottom.toFloat() })
                    }
            }
        }
        addOnPictureInPictureModeChangedListener { app.setPip(it.isInPictureInPictureMode) }
        ContextCompat.registerReceiver(
            this, pipControls,
            IntentFilter().apply { listOf(PIP_BACK, PIP_PLAY, PIP_FORWARD).forEach(::addAction) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** The pop-out window's buttons, which arrive as broadcasts from the system's window. */
    private val pipControls = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PIP_BACK -> app.skip(-10_000)
                PIP_PLAY -> app.togglePlay()
                PIP_FORWARD -> app.skip(10_000)
            }
        }
    }

    /** Android 12+ pops out on its own (auto-enter); older versions need asking on the way out. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && app.canPip) enterPictureInPictureMode(pipParams(this, app.pipTile(), app.canPip, app.playing))
    }

    /**
     * Stopped means nothing of the app is on screen: the pop-out keeps it started while it shows.
     * Dismissing the pop-out stops the app while it still reports picture-in-picture, which 0.13
     * to 0.26 read as "still showing" and kept playing. Now any stop pauses (and saves the spot).
     */
    override fun onStop() {
        super.onStop()
        app.pauseForBackground()
    }

    /** Back in front: anything that lay over the app (the vote sheet) has gone. */
    override fun onResume() {
        super.onResume()
        app.onResumed()
    }

    override fun onDestroy() {
        unregisterReceiver(pipControls)
        app.stopAll()
        app.close()
        super.onDestroy()
    }
}

/**
 * 16:9, growing out of the tile's place on screen (the source rect), with auto-enter on swipe
 * home while watching.
 */
fun pipParams(context: Context, tile: Tile?, autoEnter: Boolean, playing: Boolean): PictureInPictureParams =
    PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .setActions(
            listOf(
                pipAction(context, R.drawable.ic_pip_back, "Back 10 seconds", PIP_BACK, 1),
                if (playing) pipAction(context, R.drawable.ic_pip_pause, "Pause", PIP_PLAY, 2)
                else pipAction(context, R.drawable.ic_pip_play, "Play", PIP_PLAY, 2),
                pipAction(context, R.drawable.ic_pip_forward, "Forward 10 seconds", PIP_FORWARD, 3),
            )
        )
        .apply {
            tile?.let { setSourceRectHint(Rect(it.x.toInt(), it.y.toInt(), (it.x + it.w).toInt(), (it.y + it.h).toInt())) }
            if (Build.VERSION.SDK_INT >= 31) {
                setAutoEnterEnabled(autoEnter)
                setSeamlessResizeEnabled(false)
            }
        }
        .build()

private const val PIP_BACK = "app.apexview.PIP_BACK"
private const val PIP_PLAY = "app.apexview.PIP_PLAY"
private const val PIP_FORWARD = "app.apexview.PIP_FORWARD"

/** A pop-out button: sends one of the actions above, to this app only. */
private fun pipAction(context: Context, icon: Int, title: String, action: String, code: Int) = RemoteAction(
    Icon.createWithResource(context, icon), title, title,
    PendingIntent.getBroadcast(context, code, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE),
)

/** Keeps the pop-out settings in step with what's on screen. */
@Composable
private fun PipSetup(app: AppState) {
    val activity = LocalContext.current as Activity
    val tile = app.pipTile()
    // Rounded so a drag doesn't send an update every frame.
    LaunchedEffect(app.canPip, app.playing, tile?.id, tile?.x?.div(40)?.toInt(), tile?.y?.div(40)?.toInt(), tile?.w?.div(40)?.toInt()) {
        activity.setPictureInPictureParams(pipParams(activity, tile, app.canPip, app.playing))
    }
}

/** The pop-out window: just the one feed. */
@Composable
private fun PipScreen(app: AppState) {
    val tile = app.pipTile() ?: return
    Box(Modifier.fillMaxSize().background(Ink.background)) {
        AndroidView(
            factory = { context ->
                val layout = if (app.settings.softwareDrm) R.layout.player_texture else R.layout.player_surface
                LayoutInflater.from(context).inflate(layout, null) as PlayerView
            },
            update = { it.player = tile.feed.player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun Root(app: AppState) {
    PipSetup(app)
    val watching = app.tiles.isNotEmpty() && !app.browsing
    BackHandler(enabled = app.auth == AuthState.SIGNED_IN) {
        when {
            app.tileMenu != null -> app.closeTileMenu()
            app.mapSheetOpen -> app.mapSheetOpen = false
            watching && app.picker == null -> app.stopAll()
            !app.back() -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(Ink.background)) {
        when (app.auth) {
            AuthState.CHECKING -> Unit
            AuthState.SIGNED_OUT -> SignInScreen(app)
            AuthState.SIGNED_IN -> {
                AnimatedContent(
                    targetState = watching,
                    transitionSpec = { fadeIn(tween(Motion.SCREEN_MS, easing = EaseOut)) togetherWith fadeOut(tween(Motion.FADE_MS)) },
                    label = "viewer",
                ) { showViewer ->
                    when {
                        showViewer && app.inPip -> PipScreen(app)
                        showViewer -> ViewerScreen(app)
                        else -> Browse(app)
                    }
                }
            }
        }
        if (!app.inPip) app.picker?.let { FeedPickerSheet(app, it) }
        // Offered while browsing, never over a stream being watched.
        if (!app.inPip && !watching) UpdatePrompt(app.updater)
        if (!app.inPip) PlanPrompt(app)
        if (!app.inPip && !watching) CrashPrompt(app)
    }
}

/**
 * Pages fade through rather than slide: the new one settles up from 98% as it fades in, over the
 * old one fading out, and going back reverses it. Artwork marked with sharedArt travels between
 * the two (a weekend card grows into the weekend page's artwork). One curve, EaseOut, throughout.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Browse(app: AppState) {
    SharedTransitionLayout {
        AnimatedContent(
            targetState = app.backStack.toList(),
            transitionSpec = {
                val forward = targetState.size >= initialState.size
                val enter = tween<Float>(Motion.SCREEN_MS, delayMillis = 60, easing = EaseOut)
                val exit = tween<Float>(Motion.FADE_MS, easing = EaseOut)
                if (forward) {
                    (fadeIn(enter) + scaleIn(enter, initialScale = 0.98f)) togetherWith fadeOut(exit)
                } else {
                    fadeIn(enter) togetherWith (fadeOut(exit) + scaleOut(exit, targetScale = 0.98f))
                }
            },
            contentKey = { it.size to it.last() },
            label = "browse",
        ) { stack ->
            CompositionLocalProvider(LocalSharedScope provides this@SharedTransitionLayout, LocalPageScope provides this) {
                when (val screen = stack.last()) {
                    Screen.Home -> HomeScreen(app)
                    is Screen.Weekend -> WeekendScreen(app, screen.weekend, screen.artKey)
                    Screen.Settings -> SettingsScreen(app)
                    Screen.Calendar -> CalendarScreen(app)
                    is Screen.Collection -> CollectionScreen(app, screen.pageId, screen.title)
                    Screen.Archive -> ArchiveScreen(app)
                }
            }
        }
    }
}
