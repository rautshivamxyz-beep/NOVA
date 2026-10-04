package org.nova.ncie.android

import android.content.Context
import org.nova.NovaListener
import org.nova.NovaSms
import org.nova.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v9.17.0 "Leftovers": the rest of the automation backlog, shipped together -
 * a guardian/SOS check-in, quiet hours, recurring daily reminders, a
 * commitment ledger, a money radar over bank/UPI notifications, a scam
 * shield, document-expiry tracking and a package tracker.
 *
 * Every one of them is deterministic: it reads only local state (the
 * notification log and small files in filesDir) and never touches the
 * network - nothing leaves the phone, and they work with no model loaded.
 *
 * NcieChat calls [handle] once, right after its own deterministic
 * intercepts; a null return means "not mine, carry on".
 */
object NcieLeftovers {

    /** The single entry point. Returns the reply text, or null when [text]
     *  is not one of the leftover commands. */
    fun handle(ctx: Context, text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 200) return null

        ledgerAdd(t)?.let { return NcieLedger.add(ctx, it) }
        if (LEDGER_LIST.matches(t)) return NcieLedger.list(ctx)
        if (LEDGER_CLEAR.matches(t)) return NcieLedger.clear(ctx)

        guardianContact(t)?.let { return NcieGuardian.setContact(ctx, it) }
        guardianArm(t)?.let { return NcieGuardian.arm(ctx, it) }
        // v9.17.1: "i'm fine" / "i'm okay" is ordinary chat unless a
        // check-in is actually armed - only then is it a guardian command.
        if (GUARDIAN_OK.matches(t) && Settings(ctx).guardianUntil > System.currentTimeMillis())
            return NcieGuardian.cancel(ctx)
        if (GUARDIAN_STATUS.matches(t)) return NcieGuardian.status(ctx)

        quietSet(t)?.let { return NcieQuiet.set(ctx, it.first, it.second) }
        if (QUIET_OFF.matches(t)) return NcieQuiet.off(ctx)
        if (QUIET_STATUS.matches(t)) return NcieQuiet.status(ctx)

        recurringAdd(t)?.let { return NcieRecurring.add(ctx, it.first, it.second) }
        if (RECURRING_LIST.matches(t)) return NcieRecurring.list(ctx)
        if (RECURRING_CLEAR.matches(t)) return NcieRecurring.clear(ctx)

        if (MONEY_Q.matches(t)) return NcieMoney.report(ctx)
        if (SCAM_Q.matches(t)) return NcieScam.report(ctx)
        if (PACKAGE_Q.matches(t)) return NciePackages.report(ctx)

        expiryAdd(t)?.let { return NcieExpiry.add(ctx, it.first, it.second) }
        if (EXPIRY_LIST.matches(t)) return NcieExpiry.list(ctx)
        if (EXPIRY_CLEAR.matches(t)) return NcieExpiry.clear(ctx)

        return null
    }

    /** v9.17.0: true inside the user's quiet-hours window. NovaListener asks
     *  this before auto-replying, so NOVA stays silent when asked. */
    fun isQuietNow(ctx: Context): Boolean = NcieQuiet.isQuietNow(ctx)

    /** v9.21.0 "Reliable": re-arm the leftover alarms - the daily recurring
     *  reminders and an armed guardian. Idempotent; called at app start, on
     *  boot and from the health screen. */
    fun rearm(ctx: Context) {
        try { NcieRecurring.arm(ctx) } catch (e: Exception) { }
        try { NcieGuardian.rearm(ctx) } catch (e: Exception) { }
    }

    /** v9.19.0 "Polish": one short digest of the leftover state, shared by
     *  the morning briefing and the dream report so a single screen shows
     *  the whole day. Empty when there is nothing to say. */
    fun dailyDigest(ctx: Context): String {
        val sb = StringBuilder()
        try {
            val soon = NcieExpiry.upcoming(ctx, 30)
            if (soon.isNotEmpty())
                sb.append("expiring soon: ")
                    .append(soon.joinToString(", ") { it.first + " (" + it.second + "d)" })
                    .append('\n')
        } catch (e: Exception) { }
        try {
            val today = NcieRecurring.today(ctx)
            if (today.isNotEmpty())
                sb.append("reminders: ")
                    .append(today.take(3).joinToString(", ") { NcieQuiet.fmt(it.first) + " " + it.second })
                    .append('\n')
        } catch (e: Exception) { }
        try {
            val n = NcieLedger.count(ctx)
            if (n > 0) sb.append("promises open: ").append(n).append('\n')
        } catch (e: Exception) { }
        try {
            val until = Settings(ctx).guardianUntil
            if (until > System.currentTimeMillis())
                sb.append("guardian: armed, ")
                    .append(((until - System.currentTimeMillis()) / 60_000L).toInt() + 1)
                    .append(" min left\n")
        } catch (e: Exception) { }
        return sb.toString()
    }

    // ---- command patterns ----

    private val LEDGER_ADD1 = Regex("(?i)^\\s*i\\s+(?:promised|promise)\\s+(?:to\\s+)?(.{2,120})$")
    private val LEDGER_ADD2 = Regex("(?i)^\\s*promise\\s+to\\s+(.{2,120})$")
    private val LEDGER_LIST = Regex("(?i)^\\s*(?:what\\s+did\\s+i\\s+promise|my\\s+promises|commitments?|promise\\s+list)\\s*[.!?]*\\s*$")
    private val LEDGER_CLEAR = Regex("(?i)^\\s*(?:clear|delete|forget)\\s+(?:my\\s+)?(?:promises|commitments)\\s*[.!?]*\\s*$")

    private fun ledgerAdd(t: String): String? {
        LEDGER_ADD1.find(t)?.let { return it.groupValues[1].trim() }
        LEDGER_ADD2.find(t)?.let { return it.groupValues[1].trim() }
        return null
    }

    private val GUARDIAN_CONTACT = Regex("(?i)^\\s*(?:set\\s+)?guardian\\s+contact\\s+(.{2,40})$")
    private val GUARDIAN_ARM = Regex("(?i)^\\s*(?:guardian\\s+on|check\\s*in\\s+in|sos\\s+in)\\s*(\\d{1,3})?\\s*(?:min(?:ute)?s?)?\\s*[.!]*\\s*$")
    private val GUARDIAN_OK = Regex("(?i)^\\s*(?:i'?m\\s+(?:ok|okay|fine|safe)|i\\s+am\\s+(?:ok|okay|fine|safe)|check\\s*in|guardian\\s+off|cancel\\s+guardian)\\s*[.!]*\\s*$")
    private val GUARDIAN_STATUS = Regex("(?i)^\\s*guardian(?:\\s+status)?\\s*[.!?]*\\s*$")

    private fun guardianContact(t: String): String? =
        GUARDIAN_CONTACT.find(t)?.groupValues?.get(1)?.trim()

    private fun guardianArm(t: String): Int? {
        val m = GUARDIAN_ARM.find(t) ?: return null
        return (m.groupValues[1].toIntOrNull() ?: 30).coerceIn(1, 24 * 60)
    }

    private val QUIET_SET = Regex("(?i)^\\s*(?:quiet\\s*hours?|silence|do\\s*not\\s*disturb|dnd)\\s+(?:from\\s+)?(.+)$")
    private val QUIET_OFF = Regex("(?i)^\\s*(?:quiet\\s*hours?|silence|dnd)\\s+(?:off|cancel|disable)\\s*[.!]*\\s*$")
    private val QUIET_STATUS = Regex("(?i)^\\s*(?:(?:quiet\\s*hours?|dnd)(?:\\s+status)?|silence\\s+status)\\s*[.!?]*\\s*$")
    private val TIME_RE = Regex("(?i)(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")

    private fun quietSet(t: String): Pair<Int, Int>? {
        val m = QUIET_SET.find(t) ?: return null
        val times = TIME_RE.findAll(m.groupValues[1]).toList()
        if (times.size < 2) return null
        val a = parseTime(times[0].groupValues) ?: return null
        val b = parseTime(times[1].groupValues) ?: return null
        return a to b
    }

    private fun parseTime(g: List<String>): Int? {
        val h = g.getOrNull(1)?.toIntOrNull() ?: return null
        val mi = g.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val ap = g.getOrNull(3)?.lowercase() ?: ""
        var hh = h
        if (ap == "pm" && hh < 12) hh += 12
        if (ap == "am" && hh == 12) hh = 0
        if (hh !in 0..23 || mi !in 0..59) return null
        return hh * 60 + mi
    }

    private val RECURRING_ADD = Regex("(?i)^\\s*(?:remind\\s+me\\s+)?every\\s*day\\s+at\\s+(.+?)\\s+to\\s+(.+)$")
    private val RECURRING_LIST = Regex("(?i)^\\s*(?:my\\s+)?(?:recurring\\s+(?:reminders?|messages?)|daily\\s+reminders?)\\s*[.!?]*\\s*$")
    private val RECURRING_CLEAR = Regex("(?i)^\\s*clear\\s+(?:my\\s+)?(?:recurring\\s+(?:reminders?|messages?)|daily\\s+reminders?)\\s*[.!?]*\\s*$")

    private fun recurringAdd(t: String): Pair<Int, String>? {
        val m = RECURRING_ADD.find(t) ?: return null
        val tm = TIME_RE.find(m.groupValues[1]) ?: return null
        val mins = parseTime(tm.groupValues) ?: return null
        val what = m.groupValues[2].trim()
        if (what.length < 2) return null
        return mins to what
    }

    private val MONEY_Q = Regex("(?i)^\\s*(?:money\\s+radar|my\\s+spending|how\\s+much\\s+did\\s+i\\s+spend(?:\\s+this\\s+month)?|spending\\s+this\\s+month)\\s*[.!?]*\\s*$")
    private val SCAM_Q = Regex("(?i)^\\s*(?:scam\\s+shield|check\\s+for\\s+scams?|any\\s+scams?|scan\\s+for\\s+scams?)\\s*[.!?]*\\s*$")
    private val PACKAGE_Q = Regex("(?i)^\\s*(?:my\\s+packages?|package\\s+tracker|track\\s+my\\s+packages?|any\\s+deliveries|my\\s+deliveries)\\s*[.!?]*\\s*$")

    private val EXPIRY_ADD = Regex("(?i)^\\s*(?:my\\s+)?(.{2,30}?)\\s+expires?\\s+(?:on\\s+)?(.+?)\\s*[.!]?\\s*$")
    private val EXPIRY_LIST = Regex("(?i)^\\s*(?:my\\s+)?(?:expir(?:y|ies|ations?)|what'?s\\s+expiring|document\\s+expiry)\\s*[.!?]*\\s*$")
    private val EXPIRY_CLEAR = Regex("(?i)^\\s*clear\\s+(?:my\\s+)?expir(?:y|ies)\\s*[.!?]*\\s*$")

    private fun expiryAdd(t: String): Pair<String, Long>? {
        val m = EXPIRY_ADD.find(t) ?: return null
        val name = m.groupValues[1].trim()
        if (name.length < 2) return null
        val when0 = parseDate(m.groupValues[2].trim()) ?: return null
        return name to when0
    }

    private fun parseDate(raw: String): Long? {
        val s = raw.trim()
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(s)?.let {
            return mkDate(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        Regex("^(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})$").find(s)?.let {
            return mkDate(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
        }
        Regex("^(\\d{1,2})[/-](\\d{4})$").find(s)?.let {
            return mkDate(it.groupValues[2].toInt(), it.groupValues[1].toInt(), 1)
        }
        Regex("^(\\d{4})$").find(s)?.let {
            return mkDate(it.groupValues[1].toInt(), 1, 1)
        }
        return null
    }

    private fun mkDate(y: Int, m: Int, d: Int): Long? = try {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, (m - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, d.coerceIn(1, 28))
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    } catch (e: Exception) { null }
}

/** v9.17.0 "Commitment ledger": the promises you tell NOVA about. */
object NcieLedger {
    private const val FILE = "commitments.txt"
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun add(ctx: Context, whoWhat: String): String {
        val clean = whoWhat.replace('\t', ' ').replace('\n', ' ').trim()
        try { file(ctx).appendText(System.currentTimeMillis().toString() + "\t" + clean + "\n") } catch (e: Exception) { }
        return "Noted - I'll keep track. Say \"what did I promise\" any time."
    }

    fun list(ctx: Context): String {
        val lines = try {
            val f = file(ctx)
            if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
        } catch (e: Exception) { emptyList() }
        if (lines.isEmpty()) return "You haven't logged any promises yet. Say \"I promised <who> <what>\"."
        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val sb = StringBuilder("Your promises:\n")
        for (l in lines) {
            val p = l.split('\t', limit = 2)
            if (p.size < 2) continue
            val d = p[0].toLongOrNull()?.let { fmt.format(Date(it)) }
            sb.append("- ").append(p[1])
            if (d != null) sb.append("  (").append(d).append(")")
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    fun clear(ctx: Context): String {
        try { file(ctx).delete() } catch (e: Exception) { }
        return "Cleared your promises."
    }

    /** v9.19.0: how many promises are on the ledger. */
    fun count(ctx: Context): Int = try {
        val f = file(ctx)
        if (f.exists()) f.readLines().count { it.isNotBlank() } else 0
    } catch (e: Exception) { 0 }
}

/** v9.17.0 "Guardian / SOS": arm a check-in; if you don't cancel it in time,
 *  NOVA texts your trusted contact (or notifies you to send it yourself). */
object NcieGuardian {
    const val REQ = 7411

    fun setContact(ctx: Context, name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return "Give me a name - e.g. \"set guardian contact mom\"."
        Settings(ctx).guardianContact = n
        return "Guardian contact set to " + n + ". Say \"guardian on 30\" to arm a check-in."
    }

    fun arm(ctx: Context, minutes: Int): String {
        val c = Settings(ctx).guardianContact
        if (c.isBlank()) return "Set a guardian contact first: \"set guardian contact <name>\"."
        if (NovaSms.lookupContact(ctx, c) == null)
            return "I can't find \"" + c + "\" in your contacts - set another with \"set guardian contact <name>\"."
        val until = System.currentTimeMillis() + minutes * 60_000L
        Settings(ctx).guardianUntil = until
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            try {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, until, pi(ctx))
            } catch (e: SecurityException) {
                am.set(android.app.AlarmManager.RTC_WAKEUP, until, pi(ctx))
            }
        } catch (e: Exception) { }
        return "Guardian armed - if you don't check in within " + minutes +
            " min I'll text " + c + ". Say \"i'm ok\" to cancel."
    }

    fun cancel(ctx: Context): String {
        Settings(ctx).guardianUntil = 0L
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.cancel(pi(ctx))
        } catch (e: Exception) { }
        return "Checked in - guardian disarmed."
    }

    fun status(ctx: Context): String {
        val until = Settings(ctx).guardianUntil
        val c = Settings(ctx).guardianContact
        val contactBit = if (c.isNotBlank()) " Contact: " + c + "." else " Set a contact with \"set guardian contact <name>\"."
        if (until <= System.currentTimeMillis()) return "Guardian is off." + contactBit
        val mins = ((until - System.currentTimeMillis()) / 60_000L).toInt() + 1
        return "Guardian armed - about " + mins + " min left, then I text " + c + "."
    }

    /** Fired by LeftoverReceiver when the check-in comes due. */
    fun fire(ctx: Context) {
        val until = Settings(ctx).guardianUntil
        if (until == 0L || until > System.currentTimeMillis()) return
        Settings(ctx).guardianUntil = 0L
        val c = Settings(ctx).guardianContact
        val msg = Settings(ctx).guardianMsg
        val num = if (c.isBlank()) null else NovaSms.lookupContact(ctx, c)
        if (num != null && NovaSms.canSendDirect(ctx) && NovaSms.sendDirect(ctx, num, msg)) return
        postNotif(ctx, REQ, "NOVA guardian",
            "Check-in missed. Send this to " + (if (c.isBlank()) "your contact" else c) + ": " + msg)
    }

    /** v9.21.0 "Reliable": re-arm an armed check-in after a reboot or a
     *  force-stop (AlarmManager drops alarms on both). */
    fun rearm(ctx: Context) {
        val until = Settings(ctx).guardianUntil
        if (until <= System.currentTimeMillis()) return
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            try {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, until, pi(ctx))
            } catch (e: SecurityException) {
                am.set(android.app.AlarmManager.RTC_WAKEUP, until, pi(ctx))
            }
        } catch (e: Exception) { }
    }

    private fun pi(ctx: Context): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(ctx, REQ,
            android.content.Intent(ctx, org.nova.LeftoverReceiver::class.java).apply { putExtra("kind", "guardian") },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
}

/** v9.17.0 "Quiet hours": a window where NOVA stays silent (no auto-reply). */
object NcieQuiet {
    fun set(ctx: Context, start: Int, end: Int): String {
        val s = Settings(ctx)
        s.quietStart = start
        s.quietEnd = end
        return "Quiet hours set: " + fmt(start) + " to " + fmt(end) + ". NOVA won't auto-reply in that window."
    }

    fun off(ctx: Context): String {
        val s = Settings(ctx)
        s.quietStart = -1
        s.quietEnd = -1
        return "Quiet hours off."
    }

    fun status(ctx: Context): String {
        val s = Settings(ctx)
        if (s.quietStart < 0 || s.quietEnd < 0) return "Quiet hours are off."
        return "Quiet hours: " + fmt(s.quietStart) + " to " + fmt(s.quietEnd) +
            (if (isQuietNow(ctx)) " (active now)" else "")
    }

    fun isQuietNow(ctx: Context): Boolean {
        val s = Settings(ctx)
        val start = s.quietStart
        val end = s.quietEnd
        if (start < 0 || end < 0) return false
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (start <= end) now in start until end else (now >= start || now < end)
    }

    fun fmt(m: Int): String = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
}

/** v9.17.0 "Recurring reminders": a daily notification at a fixed time. */
object NcieRecurring {
    private const val FILE = "recurring.txt"
    const val REQ = 7421

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun load(ctx: Context): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        try {
            val f = file(ctx)
            if (f.exists()) for (l in f.readLines()) {
                val p = l.split('\t', limit = 2)
                if (p.size < 2) continue
                val m = p[0].toIntOrNull() ?: continue
                if (p[1].isNotBlank()) out.add(m to p[1])
            }
        } catch (e: Exception) { }
        return out
    }

    fun add(ctx: Context, atMinutes: Int, what: String): String {
        val clean = what.replace('\t', ' ').replace('\n', ' ').trim()
        try { file(ctx).appendText(atMinutes.toString() + "\t" + clean + "\n") } catch (e: Exception) { }
        arm(ctx)
        return "Every day at " + NcieQuiet.fmt(atMinutes) + " I'll remind you: " + clean
    }

    fun list(ctx: Context): String {
        val items = load(ctx)
        if (items.isEmpty()) return "No daily reminders yet. Say \"remind me every day at 7am to take my medicine\"."
        val sb = StringBuilder("Daily reminders:\n")
        for (item in items.sortedBy { it.first })
            sb.append("- ").append(NcieQuiet.fmt(item.first)).append("  ").append(item.second).append('\n')
        return sb.toString().trim()
    }

    fun clear(ctx: Context): String {
        try { file(ctx).delete() } catch (e: Exception) { }
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.cancel(pi(ctx))
        } catch (e: Exception) { }
        return "Cleared your daily reminders."
    }

    /** v9.19.0: the reminders for today, soonest first. */
    fun today(ctx: Context): List<Pair<Int, String>> = load(ctx).sortedBy { it.first }


    fun arm(ctx: Context) {
        val items = load(ctx)
        if (items.isEmpty()) return
        var best = Long.MAX_VALUE
        for (item in items) {
            val m = item.first
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            if (c.timeInMillis < best) best = c.timeInMillis
        }
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            try {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, best, pi(ctx))
            } catch (e: SecurityException) {
                am.set(android.app.AlarmManager.RTC_WAKEUP, best, pi(ctx))
            }
        } catch (e: Exception) { }
    }

    /** Fired by LeftoverReceiver: notify anything due now, then re-arm. */
    fun fire(ctx: Context) {
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val due = load(ctx).filter { val d = it.first - now; d >= -5 && d <= 5 }
        if (due.isNotEmpty()) {
            val body = due.joinToString("\n") { "- " + it.second }
            postNotif(ctx, REQ, "NOVA reminder", body)
        }
        arm(ctx)
    }

    private fun pi(ctx: Context): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(ctx, REQ,
            android.content.Intent(ctx, org.nova.LeftoverReceiver::class.java).apply { putExtra("kind", "recurring") },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
}

/** v9.17.0 "Money radar": a spending read over bank/UPI notifications. */
object NcieMoney {
    fun report(ctx: Context): String {
        if (!NovaListener.isEnabled(ctx))
            return "I need notification access to read bank messages - turn it on in Settings."
        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val amtRe = Regex("(?i)(?:rs\\.?|inr|\u20B9)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        val debit = Regex("(?i)debited|spent|paid|withdrawn|purchase")
        val credit = Regex("(?i)credited|received|deposited|refund")
        var spent = 0.0
        var got = 0.0
        val merchants = HashMap<String, Int>()
        for (l in NovaListener.readLog(ctx)) {
            val p = l.split('\t', limit = 4)
            if (p.size < 4) continue
            val t = p[0].toLongOrNull() ?: continue
            if (t < monthStart) continue
            val body = p[2] + " " + p[3]
            val m = amtRe.find(body) ?: continue
            val amtV = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (debit.containsMatchIn(body)) {
                spent += amtV
                val who = p[2].take(24)
                merchants[who] = (merchants[who] ?: 0) + 1
            } else if (credit.containsMatchIn(body)) {
                got += amtV
            }
        }
        if (spent == 0.0 && got == 0.0)
            return "No bank or UPI messages spotted this month. (I read only your notification log, on-device.)"
        val sb = StringBuilder("Money radar - " + SimpleDateFormat("MMMM", Locale.getDefault()).format(Date()) + ":\n")
        sb.append("spent: Rs ").append(amt(spent)).append('\n')
        if (got > 0) sb.append("received: Rs ").append(amt(got)).append('\n')
        if (merchants.isNotEmpty())
            sb.append("top: ").append(merchants.entries.sortedByDescending { it.value }.take(3)
                .joinToString(", ") { it.key }).append('\n')
        sb.append("(from your notification log, on-device)")
        return sb.toString().trim()
    }

    private fun amt(d: Double): String =
        if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.US, "%.2f", d)
}

/** v9.17.0 "Scam shield": flags likely scam notifications from local patterns. */
object NcieScam {
    private val PATTERNS = listOf(
        Regex("(?i)\\bkyc\\b.{0,40}\\b(update|expire|expiring|suspend|block|verify)"),
        Regex("(?i)\\b(share|send|provide|tell)\\b.{0,30}\\botp\\b"),
        Regex("(?i)\\b(you|u)\\s+(have\\s+)?won\\b|\\blottery\\b|\\bjackpot\\b"),
        Regex("(?i)\\bclick\\b.{0,30}\\b(link|bit\\.ly|tinyurl|t\\.cn|http)"),
        Regex("(?i)\\b(account|card|sim|kyc)\\b.{0,40}\\b(blocked|suspended|deactivated|frozen)\\b"),
        Regex("(?i)\\b(urgent|immediately|within\\s+24\\s*hours)\\b.{0,40}\\b(verify|update|pay|click)\\b"),
        Regex("(?i)\\brefund\\b.{0,40}\\b(claim|process|pending|link)\\b")
    )

    fun report(ctx: Context): String {
        if (!NovaListener.isEnabled(ctx))
            return "Turn on notification access and I can scan for scam patterns."
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val hits = ArrayList<String>()
        for (l in NovaListener.readLog(ctx)) {
            val p = l.split('\t', limit = 4)
            if (p.size < 4) continue
            val t = p[0].toLongOrNull() ?: continue
            if (t < weekAgo) continue
            val body = p[2] + " - " + p[3]
            if (PATTERNS.any { it.containsMatchIn(body) }) hits.add(body.take(120))
        }
        if (hits.isEmpty())
            return "No scam patterns in the last week's notifications. Stay careful with any OTP or KYC link."
        val sb = StringBuilder("Possible scams (last 7 days) - never share an OTP or tap these links:\n")
        for (h in hits.take(6)) sb.append("- ").append(h).append('\n')
        return sb.toString().trim()
    }
}

/** v9.17.0 "Package tracker": surfaces delivery notifications. */
object NciePackages {
    private val PATTERNS = listOf(
        Regex("(?i)out\\s+for\\s+delivery"),
        Regex("(?i)\\bdelivered\\b"),
        Regex("(?i)\\bshipped\\b|\\bdispatched\\b"),
        Regex("(?i)arriving\\s+(today|tomorrow|by|on)"),
        Regex("(?i)\\btracking\\b|\\bawb\\b|\\bshipment\\b")
    )

    fun report(ctx: Context): String {
        if (!NovaListener.isEnabled(ctx))
            return "Turn on notification access and I can watch for delivery messages."
        val since = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
        val hits = ArrayList<String>()
        for (l in NovaListener.readLog(ctx)) {
            val p = l.split('\t', limit = 4)
            if (p.size < 4) continue
            val t = p[0].toLongOrNull() ?: continue
            if (t < since) continue
            val body = p[2] + " - " + p[3]
            if (PATTERNS.any { it.containsMatchIn(body) }) hits.add(body.take(120))
        }
        if (hits.isEmpty()) return "No delivery messages in the last two weeks."
        val sb = StringBuilder("Deliveries (last 14 days):\n")
        for (h in hits.take(8)) sb.append("- ").append(h).append('\n')
        return sb.toString().trim()
    }
}

/** v9.17.0 "Document expiry": tracks dates and warns as they approach. */
object NcieExpiry {
    private const val FILE = "expiry.txt"
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun add(ctx: Context, name: String, millis: Long): String {
        val clean = name.replace('\t', ' ').replace('\n', ' ').trim()
        try { file(ctx).appendText(clean + "\t" + millis + "\n") } catch (e: Exception) { }
        return "Noted: " + clean + " expires " +
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis)) + "."
    }

    fun list(ctx: Context): String {
        val items = ArrayList<Pair<String, Long>>()
        try {
            val f = file(ctx)
            if (f.exists()) for (l in f.readLines()) {
                val p = l.split('\t', limit = 2)
                if (p.size < 2) continue
                val ms = p[1].toLongOrNull() ?: continue
                items.add(p[0] to ms)
            }
        } catch (e: Exception) { }
        if (items.isEmpty()) return "Nothing tracked yet. Say \"my passport expires 2030-05-01\"."
        val dayMs = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val sb = StringBuilder("Expiry tracker:\n")
        for (item in items.sortedBy { it.second }) {
            val days = ((item.second - now) / dayMs).toInt()
            val when0 = if (days < 0) "expired" else if (days == 0) "today" else "in " + days + " days"
            sb.append("- ").append(item.first).append(" - ").append(fmt.format(Date(item.second)))
                .append(" (").append(when0).append(")\n")
        }
        return sb.toString().trim()
    }

    fun clear(ctx: Context): String {
        try { file(ctx).delete() } catch (e: Exception) { }
        return "Cleared the expiry tracker."
    }

    /** v9.19.0: entries expiring within [days] (future only), soonest first. */
    fun upcoming(ctx: Context, days: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val dayMs = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        try {
            val f = file(ctx)
            if (f.exists()) for (l in f.readLines()) {
                val p = l.split('\t', limit = 2)
                if (p.size < 2) continue
                val ms = p[1].toLongOrNull() ?: continue
                val d = ((ms - now) / dayMs).toInt()
                if (d in 0..days) out.add(p[0] to d)
            }
        } catch (e: Exception) { }
        return out.sortedBy { it.second }
    }
}

/** v9.17.0: the shared "NOVA alerts" notification for the leftover features. */
private fun postNotif(ctx: Context, id: Int, title: String, text: String) {
    try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(android.app.NotificationChannel(
            "nova_leftovers", "NOVA alerts", android.app.NotificationManager.IMPORTANCE_HIGH))
        val n = androidx.core.app.NotificationCompat.Builder(ctx, "nova_leftovers")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    } catch (e: Exception) { }
}
