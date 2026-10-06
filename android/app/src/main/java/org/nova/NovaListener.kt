package org.nova

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nova.ncie.android.NcieStyle

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
                        // v9.16.9 "Auto-responder": opt-in silent auto-reply
                        // while you are busy - the SAME reply action as the
                        // manual silent reply, once per sender per 5 minutes
                        // so a chatty thread cannot loop.
                        // v9.22.0/v9.23.0/v9.24.0 "Busy replies": up to N
                        // DIFFERENT replies per sender, a few seconds apart,
                        // then it stops. A WhatsApp message WAKES the model -
                        // it is loaded on demand and writes the reply to what
                        // they actually said, in the user's style. Other apps
                        // use the model only if it is already warm, and
                        // everything falls back to the rotating busy lines.
                        val st = Settings(this)
                        if (st.autoReply && isMessaging(pkg) &&
                            cleanTitle.isNotBlank() &&
                            !org.nova.ncie.android.NcieLeftovers.isQuietNow(this)) {
                            val now2 = System.currentTimeMillis()
                            val who = cleanTitle.lowercase()
                            val at = autoRepliedAt[who] ?: 0L
                            val count = if (now2 - at > 30 * 60 * 1000L) 0
                                        else (autoReplied[who] ?: 0)
                            val gapOk = now2 - at > 15 * 1000L
                            if (count < st.autoReplyMax.coerceIn(1, 6) && gapOk) {
                                // Reserve the slot now, then answer OFF the
                                // notification thread - loading and writing
                                // take seconds and must never block the
                                // listener's executor.
                                autoReplied[who] = count + 1
                                autoRepliedAt[who] = now2
                                val lines = st.busyLines()
                                val canned = if (lines.isEmpty()) st.autoReplyMsg
                                             else lines[count % lines.size]
                                val svc = this
                                val target = ReplyTarget(pkg, cleanTitle, sbn.postTime,
                                    repAction, repAction.remoteInputs!!)
                                val incoming = text
                                val whatsapp = pkg == "com.whatsapp"
                                replyScope.launch {
                                    val written = try {
                                        if (whatsapp)
                                            withTimeoutOrNull(120000L) {
                                                NcieStyle.wakeAndReply(svc, incoming)
                                            }
                                        else if (NovaEngine.isModelLoaded)
                                            withTimeoutOrNull(20000L) {
                                                NcieStyle.busyReply(svc, incoming)
                                            }
                                        else null
                                    } catch (e: Exception) { null }
                                    try {
                                        if (fire(svc, target, written ?: canned))
                                            logAutoReply(svc, target.title)
                                    } catch (e: Exception) { }
                                }
                            }
                        }
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

        /** v9.16.9 "Auto-responder": last auto-reply time per sender. */
        private val autoReplied = HashMap<String, Int>()
        /** v9.22.0: when each sender was last replied to (budget reset). */
        private val autoRepliedAt = HashMap<String, Long>()

        /** v9.23.0: the model-written replies run here - the notification
         *  callback must never block on generation. */
        private val replyScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

        /** v9.22.0 "Busy replies": a small local log of auto-replies, so the
         *  health screen can show that it is actually working. */
        fun autoReplyLogPath(ctx: Context): File = File(ctx.filesDir, "auto_reply_log.txt")

        fun logAutoReply(ctx: Context, who: String) {
            try {
                val f = autoReplyLogPath(ctx)
                f.appendText(System.currentTimeMillis().toString() + "\t" +
                    who.replace('\t', ' ').replace('\n', ' ') + "\n")
                if (f.length() > 60_000) {
                    val kept = f.readLines().takeLast(300)
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(kept.joinToString("\n") + "\n")
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                }
            } catch (e: Exception) { }
        }

        /** How many auto-replies went out since midnight. */
        fun autoRepliesToday(ctx: Context): Int = try {
            val f = autoReplyLogPath(ctx)
            if (!f.exists()) 0 else {
                val midnight = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                f.readLines().count { (it.substringBefore('\t').toLongOrNull() ?: 0L) >= midnight }
            }
        } catch (e: Exception) { 0 }

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

        /** v9.29.1: the readable digest "what did I miss" uses. Previously
         *  this came from the separate NotifBrain listener (its own
         *  notifs.txt); it now reads THIS listener's log so one service is
         *  the single source of truth. Shape: "12min ago Title (package):
         *  text", newest last. */
        fun digest(ctx: Context, maxLines: Int = 120): String = try {
            val now = System.currentTimeMillis()
            readLog(ctx).takeLast(maxLines).mapNotNull { l ->
                val p = l.split('\t')
                if (p.size < 4) return@mapNotNull null
                val mins = ((now - (p[0].toLongOrNull() ?: now)) / 60_000L).toInt()
                val ago = if (mins < 1) "just now"
                    else if (mins < 60) "${mins}min ago" else "${mins / 60}h ago"
                "$ago ${p[2]} (${p[1]}): ${p[3]}"
            }.joinToString("\n")
        } catch (e: Exception) { "" }

        /** True when NOVA has been granted notification access. */
        fun isEnabled(ctx: Context): Boolean = try {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners")
                ?.contains(ctx.packageName) == true
        } catch (e: Exception) { false }
    }
}
