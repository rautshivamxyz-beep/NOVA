package org.nova

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File

/**
 * v8.8.0: NOVA's eyes. A second, quiet listener beside NotifBrain: it
 * appends every posted notification to a small local log
 * (filesDir/notif_log.txt, one "millis\tpackage\ttitle\ttext" line per
 * notification, tab-separated like fetch_log.txt) so the chat can answer
 * "what did I miss?" and "any messages from X?" deterministically from
 * the log alone - no model, no network, nothing ever leaves the phone.
 *
 * The user grants notification access once (drawer -> Notifications).
 * NOVA's own notifications are skipped, an identical package+title+text
 * inside 5 seconds is a re-post and is skipped too, and the log rotates
 * at 512 KB down to its last 2000 lines.
 */
class NovaListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.notification == null) return
        // the callback arrives on the main thread - keep file I/O off it
        Thread {
            try {
                val ex = sbn.notification.extras
                val title = (ex.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: "").toString()
                val text = (ex.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: "").toString()
                if (title.isBlank() && text.isBlank()) return@Thread
                val pkg = sbn.packageName ?: return@Thread
                // never log our own chatter back at ourselves
                if (pkg == packageName) return@Thread
                // tab/newline-free fields keep one line = one notification
                val cleanTitle = title.replace('\n', ' ').replace('\t', ' ')
                val cleanText = text.replace('\n', ' ').replace('\t', ' ').take(200)
                val now = sbn.postTime
                val key = pkg + "\t" + cleanTitle + "\t" + cleanText
                synchronized(lock) {
                    // the same notification re-posted inside 5s (update
                    // tick, listener rebind) - not a new event, skip it
                    if (key == lastLine && now - lastTime < 5000L) return@Thread
                    append(this, now.toString() + "\t" + key)
                    lastLine = key
                    lastTime = now
                }
            } catch (e: Exception) { }
        }.start()
    }

    companion object {
        private val lock = Any()
        private var lastLine: String? = null
        private var lastTime = 0L

        fun logPath(ctx: Context): File = File(ctx.filesDir, "notif_log.txt")

        fun append(ctx: Context, line: String) {
            synchronized(lock) {
                val f = logPath(ctx)
                f.appendText(line + "\n")
                if (f.length() > 512 * 1024) {
                    val kept = f.readLines().takeLast(2000)
                    f.writeText(kept.joinToString("\n") + "\n")
                }
            }
        }

        fun readLog(ctx: Context): List<String> = try {
            logPath(ctx).readLines().filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }

        /** True when NOVA has been granted notification access. */
        fun isEnabled(ctx: Context): Boolean = try {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners")
                ?.contains(ctx.packageName) == true
        } catch (e: Exception) { false }
    }
}
