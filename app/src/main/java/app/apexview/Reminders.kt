package app.apexview

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Notifications a little before sessions start, for the weekend under way and the next one.
 * The schedule is kept in storage too, so it can be set again after the phone restarts (alarms
 * don't survive that).
 */
object Reminders {
    const val LEAD_MS = 15 * 60_000L
    private const val PREFS = "reminders"
    private const val PREF_ON = "on"
    private const val PREF_MAIN_ONLY = "mainOnly"
    private const val PREF_SCHEDULE = "schedule"
    private const val CHANNEL = "sessions"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"

    fun enabled(context: Context) = prefs(context).getBoolean(PREF_ON, false)
    fun mainOnly(context: Context) = prefs(context).getBoolean(PREF_MAIN_ONLY, false)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(PREF_ON, on).apply()
    fun setMainOnly(context: Context, only: Boolean) = prefs(context).edit().putBoolean(PREF_MAIN_ONLY, only).apply()

    /** A session worth a reminder: its weekend and title, and when it starts. */
    class Session(val id: Long, val weekend: String, val title: String, val startMs: Long)

    /**
     * Replaces whatever was scheduled with reminders for [sessions] that are still ahead (or
     * cancels everything when reminders are off). Qualifying and races only, if asked.
     */
    fun sync(context: Context, sessions: List<Session>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        stored(context).forEach { alarms.cancel(pending(context, it)) }
        val now = System.currentTimeMillis()
        val wanted = if (!enabled(context)) emptyList() else sessions
            .filter { it.startMs - LEAD_MS > now }
            .filter { !mainOnly(context) || isMain(it.title) }
        wanted.forEach { schedule(context, alarms, it) }
        prefs(context).edit().putString(PREF_SCHEDULE, JSONArray(wanted.map { s ->
            JSONObject().put("id", s.id).put("weekend", s.weekend).put("title", s.title).put("start", s.startMs)
        }).toString()).apply()
    }

    /** After a restart: the saved schedule, set again. */
    fun restore(context: Context) {
        if (!enabled(context)) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        stored(context).filter { it.startMs - LEAD_MS > now }.forEach { schedule(context, alarms, it) }
    }

    private fun isMain(title: String) =
        title.contains("Race", ignoreCase = true) || title.contains("Qualifying", ignoreCase = true) ||
            title.contains("Sprint", ignoreCase = true)

    private fun schedule(context: Context, alarms: AlarmManager, s: Session) {
        val at = s.startMs - LEAD_MS
        val intent = pending(context, s)
        // Exact when Android allows it (sideloaded apps normally may), else close enough.
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun pending(context: Context, s: Session): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_TITLE, "${s.title} starts in 15 minutes")
            .putExtra(EXTRA_TEXT, s.weekend)
        return PendingIntent.getBroadcast(
            context, s.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stored(context: Context): List<Session> = runCatching {
        val list = JSONArray(prefs(context).getString(PREF_SCHEDULE, null) ?: return emptyList())
        (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            Session(o.getLong("id"), o.getString("weekend"), o.getString("title"), o.getLong("start"))
        }
    }.getOrDefault(emptyList())

    /** Shows one reminder; tapping it opens the app. */
    fun show(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Session reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context, 0, context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_pip_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Receives a reminder's alarm, and the phone's restart (to set the alarms again). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders.restore(context)
            LiveRecording.restore(context)
            return
        }
        Reminders.show(
            context,
            intent.getStringExtra(Reminders.EXTRA_TITLE) ?: return,
            intent.getStringExtra(Reminders.EXTRA_TEXT).orEmpty(),
        )
    }
}
