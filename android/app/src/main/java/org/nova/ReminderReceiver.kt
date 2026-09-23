package org.nova

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Schedules local reminders via AlarmManager. With repeatMs > 0 the
 * reminder re-arms itself after every fire (daily / weekly).
 */
object Reminder {

    fun schedule(context: Context, atMillis: Long, text: String, repeatMs: Long = 0L) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val id = (atMillis / 1000L).toInt()
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("text", text)
            .putExtra("id", id)
            .putExtra("repeat", repeatMs)
        val pi = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }
}

/** Fires the notification when a reminder is due. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "Reminder"
        val id = intent.getIntExtra("id", 1)
        val repeatMs = intent.getLongExtra("repeat", 0L)

        // repeating reminder: schedule the next occurrence first
        if (repeatMs > 0) {
            try {
                Reminder.schedule(context, System.currentTimeMillis() + repeatMs, text, repeatMs)
            } catch (e: Exception) {
                // keep notifying even if re-arming failed
            }
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "nova_reminders", "NOVA reminders",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, "nova_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NOVA reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        nm.notify(id, notif)
    }
}
