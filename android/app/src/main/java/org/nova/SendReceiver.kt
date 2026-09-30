package org.nova

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import java.io.File

/**
 * v9.8.0 "Scheduled Sends": "text <name> at <time>: <message>" stores the
 * send in filesDir/pending_sends.txt (one per line:
 * id<TAB>millis<TAB>name<TAB>message) and arms ONE AlarmManager alarm at
 * the nearest pending time. When it fires, SendReceiver resolves the
 * contact and opens the SAME messaging draft the immediate
 * "text <name> <message>" command uses (NovaSms.openDraft) - the user
 * approved the message once, at command time. Fully local: nothing is
 * fetched, nothing is invented, only messages the user explicitly typed
 * are ever sent.
 *
 * v9.13.0 "Audit Fixes" (CRITICAL): firing in the BACKGROUND (the alarm
 * receiver or BootReceiver) used to call startActivity from a non-
 * activity context - silently blocked on Android 10+ - while openDraft
 * still returned true, the toast said "NOVA sent", and the send line
 * was deleted. The message was lost with a false success. Now a
 * background fire NEVER attempts the launch: it posts a high-priority
 * notification ("nova_sends") whose tap opens the same draft, keeps the
 * line (marked notified, so the startup pass re-notifies / re-opens
 * rather than double-deleting), and the entry is only ever dropped once
 * the draft has actually opened (the foreground case - MainActivity's
 * startup pass - behaves exactly as before).
 */
object NovaSms {

    /** Contact resolution, shared by the chat command and the receiver. */
    fun lookupContact(c: Context, name: String): String? = try {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI
            .buildUpon().appendPath(name).build()
        c.contentResolver.query(uri, arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    } catch (e: Exception) { null }

    /**
     * The one send path for texts: opens the messaging app with the
     * message pre-filled (ACTION_SENDTO smsto:). Extracted from
     * tryPhoneCommand's "text" branch in v9.8.0 so SendReceiver fires
     * scheduled texts through the identical code. Only meaningful from
     * an Activity context (or a notification tap) - a background
     * receiver's startActivity is blocked silently on Android 10+.
     */
    fun openDraft(c: Context, number: String, message: String): Boolean = try {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", message)
            if (c !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        c.startActivity(i)
        true
    } catch (e: Exception) { false }
}

/** Pending scheduled sends, persisted so they survive reboots. */
object ScheduledSends {

    class Send(val id: String, val millis: Long, val name: String, val message: String,
              /** v9.13.0: the background notification was posted - the line
               *  stays until the draft actually opens (foreground re-fire). */
              val notified: Boolean = false)

    private const val FILE = "pending_sends.txt"
    private const val ALARM_ID = 9091
    private const val CHANNEL = "nova_sends"

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    fun load(ctx: Context): MutableList<Send> {
        val out = mutableListOf<Send>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                for (line in f.readText().split('\n')) {
                    // v9.13.0: limit 5 - an optional 5th field ("n") marks
                    // a background-notified send. Old 4-field lines parse
                    // unchanged (notified = false).
                    val p = line.trim('\r').split('\t', limit = 5)
                    val ms = p.getOrNull(1)?.toLongOrNull()
                    if (p.size >= 4 && ms != null && p[2].isNotEmpty() && p[3].isNotEmpty())
                        out.add(Send(p[0], ms, p[2], p[3], p.getOrNull(4) == "n"))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    private fun save(ctx: Context, list: List<Send>) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(list.joinToString("\n") { s ->
                "${s.id}\t${s.millis}\t${s.name}\t${s.message}" +
                    (if (s.notified) "\tn" else "")
            })
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { }
    }

    fun add(ctx: Context, atMillis: Long, name: String, message: String) {
        val all = load(ctx)
        all.add(Send(System.currentTimeMillis().toString(), atMillis, name, message))
        save(ctx, all)
        armNext(ctx)
    }

    /** "cancel scheduled texts" - clears the file and the alarm. */
    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (e: Exception) { }
        cancelAlarm(ctx)
    }

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, ALARM_ID, Intent(ctx, SendReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelAlarm(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(ctx))
        } catch (e: Exception) { }
    }

    /** Arms ONE alarm at the nearest future pending send. */
    fun armNext(ctx: Context) {
        val next = load(ctx).filter { it.millis > System.currentTimeMillis() }
            .minOfOrNull { it.millis } ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi(ctx))
        } catch (e: SecurityException) {
            // Android 12+ needs the special "Alarms & reminders" grant for
            // exact alarms - fall back to the inexact window rather than
            // losing the scheduled send entirely
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi(ctx))
        }
    }

    /**
     * v9.13.0 "Audit Fixes": posts the tap-to-open notification for a send
     * that came due in the background. The PendingIntent wraps the SAME
     * draft intent the foreground path starts directly (FLAG_IMMUTABLE,
     * a stable per-send request code so a re-fire updates, not stacks).
     */
    private fun notifyReady(ctx: Context, s: Send, number: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL, "Scheduled texts", android.app.NotificationManager.IMPORTANCE_HIGH))
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", s.message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(ctx, s.id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = "NOVA: ready to send to ${s.name} — tap to open"
            val notif = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("NOVA")
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                    text + "\n“${s.message}”"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(s.id.hashCode(), notif)
        } catch (e: Exception) { }
    }

    /**
     * Fires everything that is due, removes the fired lines, persists the
     * rest and re-arms the nearest future send. Called by the alarm, by
     * BootReceiver after a reboot, and by the v9.4.0 startup re-arm pass
     * in MainActivity.onCreate.
     *
     * v9.13.0: [foreground] must be true only when the caller is a
     * visible Activity (MainActivity's startup pass) - only then may the
     * draft be started directly. A background caller NEVER attempts the
     * launch (Android 10+ blocks it silently): it posts the notification
     * and KEEPS the line, marked notified, so nothing is ever lost and
     * "sent" is never reported for a send that did not open.
     */
    fun fireDue(ctx: Context, foreground: Boolean = false) {
        val now = System.currentTimeMillis()
        val all = load(ctx)
        if (all.isEmpty()) return
        val due = all.filter { it.millis <= now }
        val rest = ArrayList(all.filter { it.millis > now })
        for (s in due) {
            val number = NovaSms.lookupContact(ctx, s.name)
            when {
                number == null ->
                    Toast.makeText(ctx, "Couldn't find '${s.name}' in contacts - scheduled text not sent",
                        Toast.LENGTH_LONG).show()
                !foreground -> {
                    // background (alarm / boot): the launch would be blocked
                    // silently - notify instead, keep the line, never claim
                    // "sent"
                    notifyReady(ctx, s, number)
                    rest.add(Send(s.id, s.millis, s.name, s.message, notified = true))
                }
                NovaSms.openDraft(ctx, number, s.message) ->
                    Toast.makeText(ctx, "NOVA sent: ${s.message} to ${s.name}",
                        Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(ctx, "No messaging app for the scheduled text to ${s.name}",
                        Toast.LENGTH_LONG).show()
            }
        }
        if (due.isNotEmpty()) save(ctx, rest)
        armNext(ctx)
    }
}

/** Fires the due scheduled texts when the alarm goes off. */
class SendReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ScheduledSends.fireDue(context)
    }
}
