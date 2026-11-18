package org.nova.ncie.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v9.33.162 "The clock it can read": "what time is it" / "what's the date" /
 * "what day is it" - answered from the phone's own clock.
 *
 * This was a real hole, not a nicety. Nothing in the prompt carries the date
 * (and it must not: that would add tokens to EVERY turn to answer a question
 * asked once a day), so a date question went to the model, which has no way
 * to know it. The user asked "what's the date today" and got a confident,
 * invented answer - the worst kind of wrong, because it looks right.
 *
 * The fix is the same shape as the other deterministic commands: recognise
 * the whole message, answer it before any generation starts. That is
 * strictly faster (no token is generated at all) and strictly more accurate
 * (the phone's clock is the only correct source).
 *
 * Deliberately narrow. The pattern has to consume the WHOLE message, so
 * "what time is it in Tokyo" is still a real question for the model, and
 * "what day is my exam" still belongs to the deadline radar. Anything that
 * carries extra words is left alone.
 *
 * No Android imports: the wording and the time-zone handling are pure, so
 * [NcieClockTest] pins them on the JVM.
 */
object NcieClock {

    /** What the user asked for, or [Ask.NONE] when this is not a clock question. */
    enum class Ask { NONE, TIME, DATE, DAY }

    /** The longest message still treated as a clock question. */
    private const val MAX_LEN = 60

    private val TIME_Q = Regex(
        "(?i)^\\s*(?:what(?:'s| is)\\s+the\\s+time|" +
            "what\\s+time\\s+is\\s+it(?:\\s+now)?|" +
            "current\\s+time|time\\s+please|tell\\s+me\\s+the\\s+time)" +
            "\\s*[.!?]*\\s*$")

    private val DATE_Q = Regex(
        "(?i)^\\s*(?:what(?:'s| is)\\s+today(?:'s)?\\s+date|" +
            "what(?:'s| is)\\s+the\\s+date(?:\\s+today)?|" +
            "what\\s+date\\s+is\\s+it(?:\\s+today)?|" +
            "today(?:'s)?\\s+date|" +
            "what\\s+is\\s+the\\s+date\\s+today)" +
            "\\s*[.!?]*\\s*$")

    private val DAY_Q = Regex(
        "(?i)^\\s*(?:what\\s+day\\s+is\\s+it(?:\\s+today)?|" +
            "what\\s+day\\s+is\\s+today|" +
            "which\\s+day\\s+is\\s+it(?:\\s+today)?|" +
            "what\\s+day\\s+of\\s+the\\s+week\\s+is\\s+it(?:\\s+today)?)" +
            "\\s*[.!?]*\\s*$")

    /**
     * What [text] is asking for. Order matters only for readability: the
     * three patterns are mutually exclusive, because each one has to consume
     * the whole message.
     */
    fun ask(text: String): Ask {
        if (text.length > MAX_LEN) return Ask.NONE
        return when {
            TIME_Q.containsMatchIn(text) -> Ask.TIME
            DATE_Q.containsMatchIn(text) -> Ask.DATE
            DAY_Q.containsMatchIn(text) -> Ask.DAY
            else -> Ask.NONE
        }
    }

    /** True when [text] is a clock question - the one call the chat turn makes. */
    fun matches(text: String): Boolean = ask(text) != Ask.NONE

    /**
     * The reply. A time question also carries the date, because "is it still
     * the 18th" is the next thing anyone asks; a date or day question gets
     * exactly what it asked for and nothing more.
     */
    fun answer(ask: Ask, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        when (ask) {
            Ask.NONE -> ""
            Ask.TIME -> timeText(nowMs, zone) + "\n" + dateText(nowMs, zone)
            Ask.DATE -> dateText(nowMs, zone)
            Ask.DAY -> dayText(nowMs, zone)
        }

    /** "9:07 AM" - 12-hour, because that is how the time is read out loud here. */
    internal fun timeText(nowMs: Long, zone: TimeZone): String =
        fmt(nowMs, zone, "h:mm a")

    /** "Wednesday, 18 November 2026" - the day is included, it is free. */
    internal fun dateText(nowMs: Long, zone: TimeZone): String =
        fmt(nowMs, zone, "EEEE, d MMMM yyyy")

    /** "Wednesday" */
    internal fun dayText(nowMs: Long, zone: TimeZone): String =
        fmt(nowMs, zone, "EEEE")

    /**
     * One formatter, always in [zone]. Locale.US is the same choice the rest
     * of the app makes: the wording must not change with the phone's locale,
     * and the unit tests must be able to assert an exact string.
     */
    private fun fmt(nowMs: Long, zone: TimeZone, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(nowMs))
}
