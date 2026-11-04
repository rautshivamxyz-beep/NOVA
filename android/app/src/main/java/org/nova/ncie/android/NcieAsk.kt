package org.nova.ncie.android

/**
 * v9.33.106 "A typed command is the whole message".
 *
 * Every deterministic command in NOVA is recognised by a regex, and each of
 * them is allowed to fire only on a SHORT message - the length gate at the
 * top of its `answerXxx`. That gate was standing in for an end anchor, and
 * it does that job badly: 60 and 80 characters are plenty of room for a
 * real question.
 *
 * Two commands were therefore answering questions that were never asked of
 * them:
 *
 *  - the diagnostics report matched any message that merely STARTED with
 *    "health" ("health benefits of walking to college", 41 characters) or
 *    with "what's not working" ("what's not working in this function"), and
 *    replied with NOVA's own setup checklist instead of the answer;
 *  - the growth report matched any message that started with "what skills"
 *    ("what skills do I need to become a data analyst"), so a career
 *    question got NOVA's list of its own skills.
 *
 * Both are whole-message commands now: the pattern has to consume the
 * message, not just begin it. The generous phrasings that really are
 * commands are kept - "nova health check", "fix nova please", "what skills
 * do you have", "how have you grown" - and a trailing question mark or
 * full stop is still fine.
 *
 * Pure string work with no Android types, so the JVM unit tests can pin
 * both directions: the commands still fire, and the questions no longer do.
 */
object NcieAsk {

    /**
     * A typed command is short. These are the same ceilings the two call
     * sites already used, so nothing but the anchor changes.
     */
    const val HEALTH_MAX = 60
    const val SKILLS_MAX = 80

    /**
     * NOVA's own diagnostics: "health", "nova health", "health check",
     * "what's not working", "what is broken", "fix nova", "nova setup",
     * "setup nova", "diagnose" - optionally followed by one of the small
     * words people actually add ("check", "please", "now", "nova",
     * "status", "with nova", "in nova").
     */
    val HEALTH_Q = Regex(
        "(?i)^\\s*(?:health|nova\\s+health|what(?:'s| is)\\s+not\\s+working|" +
            "what\\s+is\\s+broken|fix\\s+nova|nova\\s+setup|setup\\s+nova|diagnose)" +
            "(?:\\s+(?:check|please|now|nova|status|with\\s+nova|in\\s+nova))?" +
            "\\s*[.!?]*\\s*$")

    /**
     * NOVA's own growth: "skills", "your skills", "my skills", "nova's
     * skills", "growth", "what skills do you have", "what skills have you
     * learned", "how have you grown", "what have you taught yourself".
     * Every branch consumes the whole message, so "what skills do I need
     * for a data analyst job" is a question for the model again.
     */
    val SKILLS_Q = Regex(
        "(?i)^\\s*(?:(?:your|my|nova'?s)\\s+)?skills?\\s*[.!?]*\\s*$" +
            "|^\\s*what\\s+skills\\s+(?:do\\s+you\\s+have|" +
            "have\\s+you\\s+(?:got|learned|learnt)|can\\s+you\\s+do|" +
            "do\\s+you\\s+know|now)\\s*[.!?]*\\s*$" +
            "|^\\s*growth\\s*[.!?]*\\s*$" +
            "|^\\s*how\\s+have\\s+you\\s+grown\\s*[.!?]*\\s*$" +
            "|^\\s*what\\s+have\\s+you\\s+taught\\s+yourself\\s*[.!?]*\\s*$")

    /** The diagnostics command - on a short message that IS the command. */
    fun health(text: String): Boolean =
        text.length <= HEALTH_MAX && HEALTH_Q.containsMatchIn(text)

    /** The growth command - on a short message that IS the command. */
    fun skills(text: String): Boolean =
        text.length <= SKILLS_MAX && SKILLS_Q.containsMatchIn(text)
}
