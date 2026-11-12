package app.apexview

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records F1's live timing in the background through each session, so the track map has the
 * whole session however late it's opened, or however far back it's wound. Live timing only sends
 * what happens from the moment it's joined, and F1 keeps the rest to itself until the session is
 * over. Starts a little before each session (an alarm, like the reminders) and stops a while
 * after it should have ended; a notification shows while it runs, with a button to stop it.
 */
object LiveRecording {
    private const val PREFS = "recording"
    private const val PREF_ON = "on"
    private const val PREF_ALL = "allSessions"
    private const val PREF_SCHEDULE = "schedule"
    /** Started this long before the session: the grid and the build-up come first. */
    private const val LEAD_MS = 10 * 60_000L
    /** Kept going this long after the session's planned end: red flags, and the results after. */
    private const val AFTER_MS = 45 * 60_000L

    fun enabled(context: Context) = prefs(context).getBoolean(PREF_ON, true)
    fun allSessions(context: Context) = prefs(context).getBoolean(PREF_ALL, false)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(PREF_ON, on).apply()
    fun setAllSessions(context: Context, all: Boolean) = prefs(context).edit().putBoolean(PREF_ALL, all).apply()

    /** How long a session runs, roughly: a race two and a half hours at most, anything else an hour. */
    private fun lengthMs(title: String) = if (isRace(title)) 150 * 60_000L else 3_600_000L

    private fun isRace(title: String) = title.contains("Race", ignoreCase = true) && !title.contains("Sprint", ignoreCase = true)

    /** Qualifying, sprints and races: the ones recorded unless every session is asked for. */
    private fun isMain(title: String) =
        title.contains("Race", ignoreCase = true) || title.contains("Qualifying", ignoreCase = true) ||
            title.contains("Sprint", ignoreCase = true) || title.contains("Shootout", ignoreCase = true)

    fun untilMs(s: Reminders.Session) = s.startMs + lengthMs(s.title) + AFTER_MS

    /**
     * Sets alarms for every session ahead that should be recorded, replacing the last lot, and
     * starts recording now if one of them is under way. Called with the app open.
     */
    fun sync(context: Context, sessions: List<Reminders.Session>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        stored(context).forEach { alarms.cancel(pending(context, it)) }
        val now = System.currentTimeMillis()
        val wanted = if (!enabled(context)) emptyList() else sessions
            .filter { untilMs(it) > now }
            .filter { allSessions(context) || isMain(it.title) }
        prefs(context).edit().putString(PREF_SCHEDULE, JSONArray(wanted.map { s ->
            JSONObject().put("id", s.id).put("weekend", s.weekend).put("title", s.title).put("start", s.startMs)
        }).toString()).apply()
        Log.i("ApexviewLive", "recording schedule: ${sessions.size} sessions known, recording " +
            wanted.joinToString { "${it.title} at ${java.time.Instant.ofEpochMilli(it.startMs)}" })
        wanted.forEach { s ->
            if (s.startMs - LEAD_MS > now) schedule(context, alarms, s)
            else start(context, s)
        }
        if (!enabled(context)) stop(context)
    }

    /** After a restart: the saved alarms, set again. */
    fun restore(context: Context) {
        if (!enabled(context)) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        stored(context).filter { it.startMs - LEAD_MS > now }.forEach { schedule(context, alarms, it) }
    }

    private fun schedule(context: Context, alarms: AlarmManager, s: Reminders.Session) {
        val at = s.startMs - LEAD_MS
        val intent = pending(context, s)
        // Exact, so Android lets the alarm start the recorder from the background.
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun pending(context: Context, s: Reminders.Session): PendingIntent {
        val intent = Intent(context, RecordingReceiver::class.java)
            .putExtra(LiveRecorderService.EXTRA_TITLE, "${s.weekend}: ${s.title}")
            .putExtra(LiveRecorderService.EXTRA_UNTIL, untilMs(s))
        return PendingIntent.getBroadcast(
            context, s.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun start(context: Context, s: Reminders.Session) =
        start(context, "${s.weekend}: ${s.title}", untilMs(s))

    fun start(context: Context, title: String, until: Long) {
        if (TokenStore.load(context) == null) return
        val intent = Intent(context, LiveRecorderService::class.java)
            .putExtra(LiveRecorderService.EXTRA_TITLE, title)
            .putExtra(LiveRecorderService.EXTRA_UNTIL, until)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Android 12+ refuses a start from the background outside an exact alarm.
            Log.w("ApexviewLive", "recorder not started: ${e.message}")
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LiveRecorderService::class.java))
    }

    private fun stored(context: Context): List<Reminders.Session> = runCatching {
        val list = JSONArray(prefs(context).getString(PREF_SCHEDULE, null) ?: return emptyList())
        (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            Reminders.Session(o.getLong("id"), o.getString("weekend"), o.getString("title"), o.getLong("start"))
        }
    }.getOrDefault(emptyList())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** A session's alarm: starts the recorder. */
class RecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LiveRecording.start(
            context,
            intent.getStringExtra(LiveRecorderService.EXTRA_TITLE) ?: "Live session",
            intent.getLongExtra(LiveRecorderService.EXTRA_UNTIL, System.currentTimeMillis() + 3 * 3_600_000L),
        )
    }
}

/** Keeps the live timing connection up, and so recorded, until the session's done. */
class LiveRecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var handle: LiveConnection.Handle? = null
    private var stopAt: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Live session"
        val until = intent?.getLongExtra(EXTRA_UNTIL, 0L)?.takeIf { it > 0 } ?: (System.currentTimeMillis() + 3 * 3_600_000L)
        val notification = notification(title)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val token = TokenStore.load(this)
        if (token == null || until <= System.currentTimeMillis()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (handle == null) {
            val userAgent = runCatching { WebSettings.getDefaultUserAgent(this).replace("; wv", "") }.getOrDefault("Mozilla/5.0")
            handle = LiveConnection.open(this, token, userAgent)
            Log.i("ApexviewLive", "recorder started for $title")
        }
        stopAt?.cancel()
        stopAt = scope.launch {
            delay(until - System.currentTimeMillis())
            stopSelf()
        }
        return START_REDELIVER_INTENT
    }

    /** Android 15 caps background data sync at six hours a day: a session is well under. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        handle?.close()
        handle = null
        scope.cancel()
        Log.i("ApexviewLive", "recorder stopped")
        super.onDestroy()
    }

    private fun notification(title: String): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Live timing recording", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveRecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pip_play)
            .setContentTitle("Recording live timing")
            .setContentText("$title. The track map will have the whole session.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_UNTIL = "until"
        private const val ACTION_STOP = "app.apexview.STOP_RECORDING"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 7
    }
}
