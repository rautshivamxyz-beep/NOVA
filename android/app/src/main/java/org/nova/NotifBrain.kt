package org.nova

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File

/**
 * Listens to the phone's notifications and keeps the most recent ones in a
 * small local file, so the user can ask NOVA "what did I miss?" and get a
 * private, on-device summary. Nothing ever leaves the phone.
 *
 * Needs the user to grant notification access once (Android settings).
 */
class NotifBrain : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val big = ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: text
            val body = if (big.length >= text.length) big else text
            if (title.isBlank() && body.isBlank()) return
            val f = File(filesDir, "notifs.txt")
            val line = listOf(
                (sbn.postTime / 1000L).toString(),
                sbn.packageName,
                title.replace('\n', ' ').take(80),
                body.replace('\n', ' ').take(300)
            ).joinToString("\u241F") + "\n"
            synchronized(lock) {
                f.appendText(line)
                if (f.length() > 400_000) {
                    val lines = f.readLines().takeLast(400)
                    f.writeText(lines.joinToString("\n") + "\n")
                }
            }
        } catch (e: Exception) { }
    }

    companion object {
        private val lock = Any()

        /** True when NOVA has been granted notification access. */
        fun isEnabled(ctx: Context): Boolean = try {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners")
                ?.contains(ctx.packageName) == true
        } catch (e: Exception) { false }

        /**
         * Builds a readable digest of recent notifications for the model:
         * "12min ago Title (package): text", newest last.
         */
        fun digest(ctx: Context, maxLines: Int = 120): String {
            val f = File(ctx.filesDir, "notifs.txt")
            if (!f.exists()) return ""
            val now = System.currentTimeMillis() / 1000L
            return try {
                f.readLines().takeLast(maxLines).mapNotNull { l ->
                    val p = l.split('\u241F')
                    if (p.size < 4) return@mapNotNull null
                    val mins = ((now - (p[0].toLongOrNull() ?: now)) / 60).toInt()
                    val ago = if (mins < 1) "just now" else if (mins < 60) "${mins}min ago" else "${mins / 60}h ago"
                    "$ago ${p[2]} (${p[1]}): ${p[3]}"
                }.joinToString("\n")
            } catch (e: Exception) { "" }
        }
    }
}
