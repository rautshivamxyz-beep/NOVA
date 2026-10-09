package org.nova.ncie.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.nova.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * v9.31.0 "Jarvis": the assistant layer - hands-free conversation,
 * proactive spoken nudges, and simple triggers. All on-device, all
 * offline, all best-effort (a failure here never touches a chat turn).
 *
 *  - [NovaHandsfree] listens continuously, sends what you say straight
 *    into the chat, speaks the reply, then listens again - so you can
 *    talk to NOVA without touching the phone.
 *  - [NovaNudge] speaks up on its own: reminders that are due, the
 *    messages you missed, and a low battery.
 *  - [NovaTriggers] runs a small "when X, do Y" list: a time of day, a
 *    place (coarse), or an app you just opened.
 */
object NovaHandsfree {

    @Volatile private var rec: SpeechRecognizer? = null
    @Volatile private var on = false

    fun isOn(): Boolean = on

    /** Toggle. Returns the new state. */
    fun toggle(act: MainActivity): Boolean {
        if (on) stop() else start(act)
        return on
    }

    /** Start the listen -> answer -> listen loop. */
    fun start(act: MainActivity) {
        if (!SpeechRecognizer.isRecognitionAvailable(act)) return
        on = true
        listen(act)
    }

    fun stop() {
        on = false
        try { rec?.stopListening() } catch (e: Exception) { }
        try { rec?.destroy() } catch (e: Exception) { }
        rec = null
    }

    private fun listen(act: MainActivity) {
        if (!on) return
        try {
            rec?.destroy()
            rec = SpeechRecognizer.createSpeechRecognizer(act)
            rec?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { }
                override fun onBeginningOfSpeech() { }
                override fun onRmsChanged(rmsdB: Float) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { }
                override fun onEvent(eventType: Int, params: Bundle?) { }
                override fun onPartialResults(partialResults: Bundle?) { }
                override fun onResults(results: Bundle?) {
                    val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (said.isNotEmpty()) {
                        // strip a wake word if they used one
                        val clean = said.replace(Regex("(?i)^\\s*(hey |ok |okay )?nova[,!.]?\\s*"), "").trim()
                        if (clean.isNotEmpty()) act.launchNcieSend(clean)
                    }
                    // answer, then listen again
                    Handler(Looper.getMainLooper()).postDelayed({ listen(act) }, 1200)
                }
                override fun onError(error: Int) {
                    Handler(Looper.getMainLooper()).postDelayed({ listen(act) }, 1500)
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            rec?.startListening(i)
        } catch (e: Exception) {
            on = false
        }
    }
}

object NovaNudge {

    private const val FILE = "nudge_state.txt"
    private const val REQ = 7611
    private const val EVERY_MS = 30L * 60L * 1000L     // every 30 minutes

    private fun state(ctx: Context): File = File(ctx.filesDir, FILE)

    private fun lastAt(ctx: Context): Long =
        try { state(ctx).readText().trim().toLong() } catch (e: Exception) { 0L }

    private fun mark(ctx: Context) {
        try { state(ctx).writeText(System.currentTimeMillis().toString()) } catch (e: Exception) { }
    }

    /** Schedule the periodic nudge check (idempotent). */
    fun schedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, REQ, Intent(ctx, NovaTriggerReceiver::class.java).setAction("org.nova.NUDGE"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + EVERY_MS, pi)
        } catch (e: Exception) { }
    }

    /** Speak whatever is worth speaking, if anything. Returns the line or "". */
    fun check(ctx: Context): String {
        val now = System.currentTimeMillis()
        if (now - lastAt(ctx) < 10L * 60L * 1000L) return ""     // never nag twice in 10 min
        val parts = ArrayList<String>()

        // reminders that are due
        try {
            val now2 = System.currentTimeMillis()
            val due = org.nova.ReminderStore.load(ctx).count { t -> t.first in 1..now2 }
            if (due > 0) parts.add(if (due == 1) "You have a reminder due." else "You have $due reminders due.")
        } catch (e: Exception) { }

        // a low battery
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
            if (!charging && pct in 1..15) parts.add("Battery is at $pct percent.")
        } catch (e: Exception) { }

        if (parts.isEmpty()) return ""
        mark(ctx)
        val line = parts.joinToString(" ")
        try { org.nova.NcieVoice.init(ctx); org.nova.NcieVoice.speak(line) } catch (e: Exception) { }
        return line
    }
}

object NovaTriggers {

    /** when=<time HH:MM | app package | place name>, do=<what to say/do>. */
    class Trigger(val cond: String, val what: String)

    private fun file(ctx: Context): File = File(ctx.filesDir, "triggers.txt")

    fun all(ctx: Context): List<Trigger> = try {
        file(ctx).readLines().mapNotNull { l ->
            val p = l.split('\t', limit = 2)
            if (p.size == 2 && p[0].isNotBlank()) Trigger(p[0].trim(), p[1].trim()) else null
        }
    } catch (e: Exception) { emptyList() }

    fun add(ctx: Context, cond: String, what: String) {
        try { file(ctx).appendText(cond.trim() + "\t" + what.trim() + "\n") } catch (e: Exception) { }
    }

    fun removeAt(ctx: Context, index: Int) {
        try {
            val l = all(ctx).toMutableList()
            if (index in l.indices) { l.removeAt(index); file(ctx).writeText(l.joinToString("\n") { it.cond + "\t" + it.what } + "\n") }
        } catch (e: Exception) { }
    }

    /** The last fire time per trigger, so nothing repeats within the hour. */
    private fun fired(ctx: Context): MutableMap<String, Long> {
        val m = HashMap<String, Long>()
        try {
            for (l in File(ctx.filesDir, "triggers_fired.txt").readLines()) {
                val p = l.split('\t', limit = 2)
                if (p.size == 2) p[1].toLongOrNull()?.let { m[p[0]] = it }
            }
        } catch (e: Exception) { }
        return m
    }

    private fun saveFired(ctx: Context, m: Map<String, Long>) {
        try { File(ctx.filesDir, "triggers_fired.txt")
            .writeText(m.entries.joinToString("\n") { it.key + "\t" + it.value } + "\n") } catch (e: Exception) { }
    }

    /** Fire any trigger whose condition now holds. Returns what it said. */
    fun check(ctx: Context): String {
        val now = System.currentTimeMillis()
        val f = fired(ctx)
        var said = ""
        for (t in all(ctx)) {
            if (now - (f[t.cond] ?: 0L) < 60L * 60L * 1000L) continue     // once an hour
            val hit = when {
                t.cond.matches(Regex("\\d{1,2}:\\d{2}")) -> {
                    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Calendar.getInstance().time)
                    hm == t.cond.padStart(5, '0')
                }
                t.cond.startsWith("near:") -> {
                    // coarse: only if we already hold a last-known fix
                    try {
                        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: continue
                        val want = t.cond.removePrefix("near:").trim().lowercase()
                        // a place name is matched against a saved note of the last area
                        want.isNotEmpty() && want == lastArea(ctx)
                    } catch (e: Exception) { false }
                }
                else -> false
            }
            if (hit) {
                f[t.cond] = now
                said = if (said.isEmpty()) t.what else said + " " + t.what
            }
        }
        if (said.isNotEmpty()) {
            saveFired(ctx, f)
            try { org.nova.NcieVoice.init(ctx); org.nova.NcieVoice.speak(said) } catch (e: Exception) { }
        }
        return said
    }

    /** The coarse area NOVA last saw (set by the app when it has a fix). */
    fun lastArea(ctx: Context): String = try {
        File(ctx.filesDir, "last_area.txt").readText().trim().lowercase()
    } catch (e: Exception) { "" }

    fun setArea(ctx: Context, area: String) {
        try { File(ctx.filesDir, "last_area.txt").writeText(area.trim().lowercase()) } catch (e: Exception) { }
    }
}

/** The periodic tick: nudges + triggers. Also re-arms itself. */
class NovaTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try { NovaNudge.check(ctx) } catch (e: Exception) { }
        try { NovaTriggers.check(ctx) } catch (e: Exception) { }
        try { NovaNudge.schedule(ctx) } catch (e: Exception) { }
    }
}
