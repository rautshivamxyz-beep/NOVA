package org.nova.ncie.android

/**
 * v9.33.109 "One flashcard count".
 *
 * `flashcards.txt` is one card per line: the front, the unit-separator
 * symbol, the back. Two screens read that file and counted it two
 * different ways:
 *
 *  - the morning briefing counted real cards - a separator with something
 *    on both sides;
 *  - the dream report, printed a few lines below it on the SAME screen,
 *    counted every non-blank line.
 *
 * So one blank line, a stray newline, or a line the tutor wrote without its
 * separator made the same deck read "flashcards: 3" in one block and
 * "flashcards: 5" in the next - two numbers, one file, no explanation, on a
 * screen whose whole job is to tell the user where they stand. The count
 * lives here now, once, and both reports ask it.
 *
 * Pure string work, so a JVM unit test can pin it.
 */
object NcieDeck {

    /** The separator NcieTutor writes between a card's front and back. */
    const val SEP = '\u241F'

    /** True when [line] really is a card: a front, [SEP], a back, both filled. */
    fun isCard(line: String): Boolean {
        val i = line.indexOf(SEP)
        if (i <= 0) return false
        return line.substring(0, i).isNotBlank() && line.substring(i + 1).isNotBlank()
    }

    /** How many real cards [lines] holds. */
    fun count(lines: List<String>): Int = lines.count { isCard(it) }
}
