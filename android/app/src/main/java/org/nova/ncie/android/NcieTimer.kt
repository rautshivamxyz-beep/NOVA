package org.nova.ncie.android

/**
 * v9.33.146 "A timer cannot crash the app": the parser behind the
 * "timer 10 minutes" phone command.
 *
 * The command used to parse its own duration inline in MainActivity:
 *
 *     val n = m.groupValues[1].toInt()
 *     'h' -> n * 3600
 *
 * with no error handling anywhere on the path - tryPhoneCommand is called
 * straight from the chat turn. Both lines are reachable from ordinary typed
 * text and both fail badly:
 *
 *  - "timer 99999999999999 minutes" -> toInt() throws NumberFormatException.
 *    Nothing catches it, so the whole app goes down on one chat message.
 *  - "timer 1000000 hours" -> 1000000 * 3600 overflows Int to a negative
 *    number, so the clock app is handed a nonsense length.
 *
 * The arithmetic is now a pure function with no Android imports: the digits
 * are read as a Long, the range is checked BEFORE any multiplication (every
 * unit multiplies by at least 1, so a count already above [MAX_SECONDS] can
 * never come back under it), and an absurd digit count that does not even
 * fit a Long is answered the same way as any other over-long timer. Nothing
 * here can throw, so the command can no longer crash a turn.
 *
 * Pure JVM, so TimerSpecTest pins it with `gradle testDebugUnitTest`.
 */
object NcieTimer {

    /**
     * The longest timer NOVA will set: 24 hours.
     *
     * A kitchen timer, not a calendar. A clock app that is handed a month of
     * seconds either rejects it or fires in the far future with no way for
     * the user to see what happened, and an over-long request is almost
     * always a typo or a misheard number. Saying so is more honest than
     * silently clamping to a value the user never asked for.
     */
    const val MAX_SECONDS = 86_400

    /**
     * "10 minutes", "2 hrs", "90s", "1.5 hours" - one number (with an
     * optional fractional part) and one unit word.
     *
     * v9.33.146: the fraction is here because the integer-only pattern read
     * "timer 1.5 hours" as the "5 hours" inside it and set FIVE hours - the
     * user got a timer four and a half hours longer than the one they asked
     * for, with a toast agreeing with the wrong number. A comma is accepted
     * as the decimal mark too, since that is how much of the world writes it.
     */
    private val SPEC = Regex(
        "(?i)(\\d+)(?:[.,](\\d+))?\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")

    /**
     * A recognised duration.
     *
     * [tooLong] is a real answer, not a failure: the text WAS a timer request,
     * it just asked for more than [MAX_SECONDS]. Keeping the two apart is what
     * lets the caller tell "I did not understand that" apart from "that is too
     * long", and answer each honestly.
     */
    data class Spec(val seconds: Int, val tooLong: Boolean)

    /**
     * The duration in [spec], or null when it is not a timer request at all.
     *
     * Never throws: an unparseable, zero-length or over-long count is a
     * [Spec] or a null, never an exception.
     */
    fun parse(spec: String): Spec? {
        val m = SPEC.find(spec) ?: return null
        val digits = m.groupValues[1]
        // 19 digits does not fit a Long either - still "far too long", and
        // still an answer rather than a crash
        val whole = digits.toLongOrNull() ?: return Spec(0, true)
        val mult = when (m.groupValues[3].lowercase()[0]) {
            'h' -> 3600L
            'm' -> 60L
            else -> 1L
        }
        // every unit multiplies by at least 1, so this one check makes both
        // multiplications below overflow-free
        if (whole > MAX_SECONDS) return Spec(0, true)
        var secs = whole * mult
        // the fractional part, in integer arithmetic - "1.5 hours" is 5400,
        // not the 18000 the integer-only pattern used to read out of it
        val frac = m.groupValues[2]
        if (frac.isNotEmpty()) {
            val scale = if (frac.length > 9) 9 else frac.length
            val num = frac.substring(0, scale).toLongOrNull() ?: 0L
            var den = 1L
            repeat(scale) { den *= 10L }
            secs += (num * mult + den / 2L) / den
        }
        if (secs > MAX_SECONDS) return Spec(0, true)
        // "timer 0 minutes" is a timer request that asks for nothing
        if (secs < 1L) return null
        return Spec(secs.toInt(), false)
    }

    /**
     * "5400" -> "1 hour 30 minutes", for the confirmation toast.
     *
     * The old toast echoed the user's own words back ("Timer set: 10
     * minutes"), which is fine until the number is over-long or in a unit
     * that hides what was set. This states what NOVA actually handed to the
     * clock app.
     */
    fun label(seconds: Int): String {
        if (seconds <= 0) return "0 seconds"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = ArrayList<String>(3)
        if (h > 0) parts.add(h.toString() + if (h == 1) " hour" else " hours")
        if (m > 0) parts.add(m.toString() + if (m == 1) " minute" else " minutes")
        if (s > 0) parts.add(s.toString() + if (s == 1) " second" else " seconds")
        return parts.joinToString(" ")
    }
}
