package org.nova.ncie

/**
 * v9.33.87 "F4 the loop is cut".
 *
 * A small model that loses its thread does not stop. It repeats the same
 * phrase until the generation leash runs out, so the user sits through the
 * whole budget reading the same sentence - and on a phone that is seconds of
 * battery spent producing text they already have.
 *
 * This is the detector. It is pure string logic with no Android and no engine
 * dependency, so it is unit-tested instead of trusted.
 *
 * It only looks at the TAIL of the stream, because a loop is by definition at
 * the end of it. Three repeat lengths are recognised - a whole sentence, a
 * clause, and a stuck fragment - and each has its own copy count, because a
 * short fragment repeats many times before it is a loop while a long sentence
 * needs only three copies to be certain.
 *
 * The failure direction matters both ways. A missed loop costs the user the
 * rest of the budget; a false positive cuts a good answer short. So a repeat
 * only counts when its unit carries real words and varies - a markdown rule,
 * a line of dashes or a run of one character is formatting a model emits
 * legitimately, and is never treated as a loop.
 */
object NcieRepeat {

    /** A unit at least this long is a sentence: three copies are enough. */
    private const val LONG_MIN = 32
    private const val LONG_MAX = 240
    private const val LONG_REPS = 3

    /** A clause needs more copies to be sure. */
    private const val MID_MIN = 8
    private const val MID_REPS = 6

    /** A stuck fragment needs many. */
    private const val SHORT_MAX = 7
    private const val SHORT_REPS = 10

    /** Only the tail is examined. Public since v9.33.92: the caller keeps
     *  exactly this much while streaming, so the detector is handed a bounded
     *  string instead of the whole growing reply. */
    const val TAIL = 1600

    /**
     * v9.33.92 "Bounded tail": the part of [text] [cycle] actually looks at.
     *
     * While a reply streams, the guard re-checks every 24 characters. It used
     * to hand the detector the whole reply so far, so every check copied the
     * entire text again - on a long answer that is hundreds of copies of a
     * growing string, all on the thread that is also receiving tokens. The
     * detector only ever reads the last [TAIL] characters, so that is all it
     * needs to be given, and keeping only that much changes no verdict.
     */
    fun window(text: String): String =
        if (text.length <= TAIL) text else text.substring(text.length - TAIL)

    /** True when the end of [text] is already a loop. */
    fun looped(text: String): Boolean = cycle(text) != null

    /**
     * The length of the repeating unit when the tail is a loop, else null.
     * Kept separate from [looped] so a test can pin WHICH rule fired.
     */
    fun cycle(text: String): Int? {
        val n = text.length
        if (n < MID_MIN * MID_REPS) return null
        val tail = if (n > TAIL) TAIL else n

        var l = LONG_MIN
        while (l <= LONG_MAX) {
            if (l * LONG_REPS > tail) break
            if (repeats(text, n, l, LONG_REPS)) return l
            l++
        }
        l = MID_MIN
        while (l < LONG_MIN) {
            if (l * MID_REPS <= tail && repeats(text, n, l, MID_REPS)) return l
            l++
        }
        l = 1
        while (l <= SHORT_MAX) {
            if (l * SHORT_REPS <= tail && repeats(text, n, l, SHORT_REPS)) return l
            l++
        }
        return null
    }

    /** Are the last [times] copies of length [len], ending at [end], identical? */
    private fun repeats(t: String, end: Int, len: Int, times: Int): Boolean {
        val last = end - len
        if (last < 0) return false
        if (!substantial(t, last, len)) return false
        var i = 1
        while (i < times) {
            val start = end - len * (i + 1)
            if (start < 0) return false
            if (!t.regionMatches(start, t, last, len)) return false
            i++
        }
        return true
    }

    /**
     * A repeated unit has to carry real words and vary. A table rule, a line
     * of dashes or a run of one character repeated is formatting, never a
     * loop.
     */
    private fun substantial(t: String, at: Int, len: Int): Boolean {
        var letters = 0
        var varied = false
        val first = t[at]
        var i = at
        while (i < at + len) {
            val c = t[i]
            if (c.isLetterOrDigit()) letters++
            if (c != first) varied = true
            i++
        }
        return letters >= 4 && varied
    }
}
