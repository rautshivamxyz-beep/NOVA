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
import org.nova.ncie.android.NcieFiles

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

    /**
     * Contact resolution, shared by the chat command and the receiver.
     * v9.16.6: three strategies, because the single CONTENT_FILTER_URI
     * prefix lookup missed contacts the user clearly has ("call mom"
     * answered "Couldn't find 'mom' in contacts"):
     *   1. the phone filter (a name prefix or a number) - the fast path;
     *   2. the contacts filter, then the number for the matched contact id;
     *   3. a case-insensitive "contains" scan over every phone contact.
     */
    fun lookupContact(c: Context, name: String): String? {
        val q = name.trim().trimEnd('.', '!', '?', ',', ' ').trim()
        if (q.isEmpty()) return null
        // 1. fast: the phone filter matches a name prefix or a number
        filterNumber(c, ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, q)?.let { return it }
        // 2. the contacts filter resolves names the phone filter can miss
        try {
            c.contentResolver.query(
                ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon().appendPath(q).build(),
                arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    numberForContact(c, cur.getString(0))?.let { return it }
                }
            }
        } catch (e: Exception) { NovaLog.quiet("SendReceiver", e) }
        // 3. last resort: a case-insensitive contains scan over the contacts
        try {
            val ql = q.lowercase()
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val dn = cur.getString(0) ?: continue
                    if (dn.lowercase().contains(ql)) return cur.getString(1)
                }
            }
        } catch (e: Exception) { NovaLog.quiet("SendReceiver", e) }
        return null
    }

    /** First phone number matching [q] in the given filter [base] URI. */
    private fun filterNumber(c: Context, base: android.net.Uri, q: String): String? = try {
        c.contentResolver.query(base.buildUpon().appendPath(q).build(),
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    } catch (e: Exception) { null }

    /** The first phone number for a contact id. */
    private fun numberForContact(c: Context, id: String): String? = try {
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", arrayOf(id),
            null)?.use { cur ->
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

    /** v9.16.5: true when NOVA may send the text itself (SEND_SMS granted). */
    fun canSendDirect(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * v9.16.5 "Direct SMS": send the text ourselves through SmsManager - no
     * messaging app opens, and it works from the background (the alarm
     * receiver), unlike a startActivity draft which Android 10+ blocks.
     * Returns false on any failure so the caller can fall back to the
     * draft / notification path.
     */
    fun sendDirect(c: Context, number: String, message: String): Boolean = try {
        @Suppress("DEPRECATION")
        val sms = android.telephony.SmsManager.getDefault()
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(message), null, null)
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

    /**
     * v9.33.123 "The scheduled text is the whole text": the store, or null
     * when the file is there and could not be read.
     *
     * v9.33.119 gave every store that rewrites what it read the difference
     * between "nothing there" and "could not be read"; v9.33.121 finished the
     * sweep over the notes and the deck. This one was missed. [load] answers
     * an empty list either way, and [add] writes that list straight back - so
     * one unreadable read deleted every scheduled text the user had, and NOVA
     * still answered "Scheduled: ... to ...", true about the new one and a
     * lie about the rest.
     */
    fun loadOrNull(ctx: Context): MutableList<Send>? {
        val text = NcieFiles.readOrNull(file(ctx)) ?: return null
        return decode(text)
    }

    fun load(ctx: Context): MutableList<Send> = loadOrNull(ctx) ?: mutableListOf()

    /**
     * v9.33.123: the file is one tab-separated line per send, and the message
     * used to be written into it raw. A two-line draft - or anything pasted
     * with a newline in it - became two lines, and the reader kept only the
     * first: the text that actually sent was silently cut at the first
     * newline, and NOVA still reported it "sent". A TAB was worse: it is the
     * field separator, so it ate the rest of the message and the remainder
     * became the "notified" flag.
     *
     * [esc] on the way out, [unesc] on the way in. A line written before this
     * release carries no backslash and decodes exactly as it did.
     */
    internal fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    /** The inverse of [esc]. An unknown escape is kept as written. */
    internal fun unesc(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    /**
     * The lines of the store, parsed. One broken line costs that line only -
     * the same rule v9.33.101 gave every JSON loader in the app.
     */
    internal fun decode(text: String): MutableList<Send> {
        val out = mutableListOf<Send>()
        for (line in text.split('\n')) {
            // v9.13.0: limit 5 - an optional 5th field ("n") marks a
            // background-notified send. Old 4-field lines parse unchanged.
            val p = line.trim('\r').split('\t', limit = 5)
            val ms = p.getOrNull(1)?.toLongOrNull()
            if (p.size >= 4 && ms != null && p[2].isNotEmpty() && p[3].isNotEmpty())
                out.add(Send(unesc(p[0]), ms, unesc(p[2]), unesc(p[3]), p.getOrNull(4) == "n"))
        }
        return out
    }

    /** The file's exact text for [list] - the inverse of [decode]. */
    internal fun encode(list: List<Send>): String = list.joinToString("\n") { s ->
        esc(s.id) + "\t" + s.millis + "\t" + esc(s.name) + "\t" + esc(s.message) +
            (if (s.notified) "\tn" else "")
    }

    private fun save(ctx: Context, list: List<Send>): Boolean = try {
        // v9.33.98: a scheduled text that is lost here is lost silently
        NcieFiles.write(file(ctx), encode(list))
    } catch (e: Exception) { NovaLog.quiet("SendReceiver", e); false }

    /**
     * True when the send is on disk. False when the store could not be read -
     * saving then would rewrite the file from nothing and delete every other
     * scheduled text the user had, so nothing is written at all.
     */
    fun add(ctx: Context, atMillis: Long, name: String, message: String): Boolean {
        val all = loadOrNull(ctx) ?: return false
        all.add(Send(System.currentTimeMillis().toString(), atMillis, name, message))
        if (!save(ctx, all)) return false
        armNext(ctx)
        return true
    }

    /** "cancel scheduled texts" - clears the file and the alarm. */
    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (e: Exception) { NovaLog.quiet("SendReceiver", e) }
        cancelAlarm(ctx)
    }

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, ALARM_ID, Intent(ctx, SendReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelAlarm(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(ctx))
        } catch (e: Exception) { NovaLog.quiet("SendReceiver", e) }
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
        } catch (e: Exception) { NovaLog.quiet("SendReceiver", e) }
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
        // v9.33.123: an unreadable store is not an empty one - this used to
        // return here too, which was safe, but the save below would have
        // rewritten the file from nothing if the read had only partly failed.
        val all = loadOrNull(ctx) ?: return
        if (all.isEmpty()) return
        val due = all.filter { it.millis <= now }
        val rest = ArrayList(all.filter { it.millis > now })
        for (s in due) {
            val number = NovaSms.lookupContact(ctx, s.name)
            when {
                number == null ->
                    Toast.makeText(ctx, "Couldn't find '${s.name}' in contacts - scheduled text not sent",
                        Toast.LENGTH_LONG).show()
                NovaSms.canSendDirect(ctx) && NovaSms.sendDirect(ctx, number, s.message) ->
                    Toast.makeText(ctx, "NOVA sent: ${s.message} to ${s.name}",
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
