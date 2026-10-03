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
 *
 * v9.3.0 "Audit Fixes I" (privacy): lines older than 7 days are purged
 * on append (the log is oldest-first, so a cheap probe of the first
 * line decides when a full pass is due), and a persisted flag file
 * (filesDir/notif_pause.txt - presence = paused) stops all logging:
 * onNotificationPosted returns before anything is written. "clear my
 * notifications" wipes the log, "pause/resume notifications" toggles
 * the flag - both are chat commands in NcieChat, and BackupActivity
 * keeps the log and the flag out of every backup zip.
 */
class NovaListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.notification == null) return
        // v9.3.0: paused means paused - nothing is written, nothing rotated
        if (isPaused(this)) return
        // the callback arrives on the main thread - keep file I/O off it.
        // v9.4.0 "Audit Fixes II" (audit: one Thread per notification):
        // all notification work runs on ONE shared background executor,
        // never a fresh thread per post
        io.execute {
            try {
                val ex = sbn.notification.extras
                val title = (ex.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: "").toString()
                val text = (ex.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: "").toString()
                if (title.isBlank() && text.isBlank()) return@execute
                val pkg = sbn.packageName ?: return@execute
                // never log our own chatter back at ourselves
                if (pkg == packageName) return@execute
                // tab/newline-free fields keep one line = one notification
                val cleanTitle = title.replace('\n', ' ').replace('\t', ' ')
                // v9.16.8 "Silent reply": remember the notification's own
                // direct-reply action (RemoteInput) so NOVA can answer it from
                // the shade - the same mechanism auto-responder apps use, with
                // no app opening.
                val repAction = sbn.notification.actions?.firstOrNull {
                    it.remoteInputs?.isNotEmpty() == true
                }
                if (repAction != null && repAction.remoteInputs != null) {
                    synchronized(lock) {
                        recentReplies[sbn.key] =
                            ReplyTarget(pkg, cleanTitle, sbn.postTime, repAction,
                                repAction.remoteInputs!!)
                        while (recentReplies.size > 30)
                            recentReplies.remove(recentReplies.keys.first())
                    }
                }
                val cleanText = text.replace('\n', ' ').replace('\t', ' ').take(200)
                val now = sbn.postTime
                val key = pkg + "\t" + cleanTitle + "\t" + cleanText
                synchronized(lock) {
                    // the same notification re-posted inside 5s (update
                    // tick, listener rebind) - not a new event, skip it
                    if (key == lastLine && now - lastTime < 5000L) return@execute
                    append(this, now.toString() + "\t" + key)
                    lastLine = key
                    lastTime = now
                }
            } catch (e: Exception) { }
        }
    }

    companion object {
        private val lock = Any()
        private var lastLine: String? = null
        private var lastTime = 0L

        /** v9.4.0 "Audit Fixes II": the one shared background executor
         *  every notification is handled on. */
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

        /** v9.16.8 "Silent reply": the apps whose notifications can be answered. */
        private fun isMessaging(pkg: String): Boolean =
            pkg == "com.whatsapp" || pkg.contains("messaging") ||
            pkg.contains("mms") || pkg.contains("sms") ||
            pkg.contains("telegram") || pkg.contains("signal") ||
            pkg.contains("wire")

        /** One replyable notification: its sender and the action to fire. */
        private class ReplyTarget(
            val pkg: String, val title: String, val time: Long,
            val action: android.app.Notification.Action,
            val remoteInputs: Array<android.app.RemoteInput>)

        /** The most recent replyable notifications, newest kept. */
        private val recentReplies = LinkedHashMap<String, ReplyTarget>()

        /**
         * v9.16.8 "Silent reply": answer [who]'s most recent chat message
         * through the notification's OWN direct-reply action (RemoteInput) -
         * nothing opens; the same mechanism auto-responder apps use. Returns
         * false when there is no replyable notification from them, so the
         * caller can fall back to opening the app.
         */
        fun silentReply(ctx: Context, who: String, message: String): Boolean {
            val q = who.trim().lowercase()
            val targets = synchronized(lock) {
                recentReplies.values.sortedByDescending { it.time }
            }
            for (t in targets) {
                if (!isMessaging(t.pkg)) continue
                if (q.isNotEmpty() && !t.title.lowercase().contains(q)) continue
                if (fire(ctx, t, message)) return true
            }
            return false
        }

        private fun fire(ctx: Context, t: ReplyTarget, message: String): Boolean = try {
            val intent = android.content.Intent()
            val bundle = android.os.Bundle()
            for (ri in t.remoteInputs) bundle.putCharSequence(ri.resultKey, message)
            android.app.RemoteInput.addResultsToIntent(t.remoteInputs, intent, bundle)
            t.action.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: Exception) { false }

        /** The sender of the newest replyable notification. */
        fun latestSender(ctx: Context): String? = synchronized(lock) {
            recentReplies.values.filter { isMessaging(it.pkg) }
                .maxByOrNull { it.time }?.title
        }

        fun logPath(ctx: Context): File = File(ctx.filesDir, "notif_log.txt")

        /** v9.3.0: the pause flag - its presence alone means paused. */
        fun pauseFlag(ctx: Context): File = File(ctx.filesDir, "notif_pause.txt")

        fun isPaused(ctx: Context): Boolean = try {
            pauseFlag(ctx).exists()
        } catch (e: Exception) { false }

        /** Stop logging notifications ("pause notifications"). */
        fun pause(ctx: Context) {
            try { pauseFlag(ctx).writeText("paused\n") } catch (e: Exception) { }
        }

        /** Resume logging notifications ("resume notifications"). */
        fun resume(ctx: Context) {
            try { pauseFlag(ctx).delete() } catch (e: Exception) { }
        }

        /** Wipe the log ("clear my notifications"). */
        fun clearLog(ctx: Context) {
            synchronized(lock) {
                try { logPath(ctx).delete() } catch (e: Exception) { }
                lastLine = null
                lastTime = 0L
            }
        }

        fun append(ctx: Context, line: String) {
            synchronized(lock) {
                val f = logPath(ctx)
                f.appendText(line + "\n")
                // v9.3.0 privacy: 7-day expiry. The log is oldest-first, so
                // the first line's timestamp is the cheap probe - a full
                // filtering pass runs only when it has actually aged out.
                val now = System.currentTimeMillis()
                val cutoff = now - 7L * 24 * 60 * 60 * 1000
                var lines: List<String>? = null
                if (f.length() > 512 * 1024) {
                    lines = f.readLines()
                } else {
                    val first = try {
                        f.useLines { it.firstOrNull { l -> l.isNotBlank() } }
                    } catch (e: Exception) { null }
                    val t = first?.substringBefore('\t')?.toLongOrNull()
                    if (t != null && t < cutoff) lines = f.readLines()
                }
                if (lines != null) {
                    val kept = lines.filter { l ->
                        val t = l.substringBefore('\t').toLongOrNull()
                        t != null && t >= cutoff
                    }.takeLast(2000)
                    // v9.16.1: atomic rewrite of the trimmed log
                    val tmp = java.io.File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(kept.joinToString("\n") + "\n")
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
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
