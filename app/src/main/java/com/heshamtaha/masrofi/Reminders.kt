package com.heshamtaha.masrofi

import android.Manifest
import android.annotation.SuppressLint
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
import java.util.Calendar

/**
 * Schedules the 11 PM daily reminder and the debt / gam'eya reminders
 * that the web app sends through AndroidBridge.syncReminders().
 */
object Reminders {
    private const val CHANNEL = "masrofi_reminders"
    private const val PREFS = "masrofi_reminders"
    const val ACTION_DAILY = "com.heshamtaha.masrofi.DAILY"
    const val ACTION_DUE = "com.heshamtaha.masrofi.DUE"
    private const val DAILY_HOUR = 23
    private const val MAX_DUE = 40

    fun createChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "تذكيرات مصروفي", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun save(c: Context, json: String) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("cfg", json).apply()

    fun apply(c: Context) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cfg = try { JSONObject(prefs.getString("cfg", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val am = c.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()

        // Daily 11 PM reminder
        val daily = pending(c, 1, ACTION_DAILY, null, null)
        am.cancel(daily)
        if (cfg.optBoolean("daily", false)) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(DAILY_HOUR), daily)
        }

        // Debt and gam'eya reminders
        val oldCount = prefs.getInt("dueCount", 0)
        for (i in 0 until oldCount) am.cancel(pending(c, 100 + i, ACTION_DUE, null, null))
        val items = cfg.optJSONArray("due") ?: JSONArray()
        var n = 0
        for (i in 0 until items.length()) {
            if (n >= MAX_DUE) break
            val o = items.getJSONObject(i)
            val at = o.optLong("at")
            if (at <= now) continue
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at,
                pending(c, 100 + n, ACTION_DUE, o.optString("title"), o.optString("body"))
            )
            n++
        }
        prefs.edit().putInt("dueCount", n).apply()
    }

    private fun next(hour: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun pending(c: Context, code: Int, action: String, title: String?, body: String?): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).setAction(action)
        if (title != null) i.putExtra("title", title)
        if (body != null) i.putExtra("body", body)
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun show(c: Context, id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(c)
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(ContextCompat.getColor(c, R.color.brand))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: SecurityException) { }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Reminders.ACTION_DAILY -> {
                Reminders.show(c, 1, "سجلت مصاريف النهارده؟", "لو صرفت حاجة النهارده سجلها قبل ما تنسى.")
                Reminders.apply(c) // schedule tomorrow
            }
            Reminders.ACTION_DUE -> Reminders.show(
                c, 1000 + (System.currentTimeMillis() % 100000).toInt(),
                i.getStringExtra("title") ?: "مصروفي", i.getStringExtra("body") ?: ""
            )
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // Alarms are cleared on reboot / app update, so schedule them again
        Reminders.apply(c)
    }
}
