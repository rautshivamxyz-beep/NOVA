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
     * scheduled texts through the identical code.
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

    class Send(val id: String, val millis: Long, val name: String, val message: String)

    private const val FILE = "pending_sends.txt"
    private const val ALARM_ID = 9091

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    fun load(ctx: Context): MutableList<Send> {
        val out = mutableListOf<Send>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                for (line in f.readText().split('\n')) {
                    val p = line.trim('\r').split('\t', limit = 4)
                    val ms = p.getOrNull(1)?.toLongOrNull()
                    if (p.size == 4 && ms != null && p[2].isNotEmpty() && p[3].isNotEmpty())
                        out.add(Send(p[0], ms, p[2], p[3]))
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
                "${s.id}\t${s.millis}\t${s.name}\t${s.message}"
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
     * Fires everything that is due, removes the fired lines, persists the
     * rest and re-arms the nearest future send. Called by the alarm, by
     * BootReceiver after a reboot, and by the v9.4.0 startup re-arm pass
     * in MainActivity.onCreate.
     */
    fun fireDue(ctx: Context) {
        val now = System.currentTimeMillis()
        val all = load(ctx)
        if (all.isEmpty()) return
        val due = all.filter { it.millis <= now }
        val rest = all.filter { it.millis > now }
        for (s in due) {
            val number = NovaSms.lookupContact(ctx, s.name)
            when {
                number == null ->
                    Toast.makeText(ctx, "Couldn't find '${s.name}' in contacts - scheduled text not sent",
                        Toast.LENGTH_LONG).show()
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
