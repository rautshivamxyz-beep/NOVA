package org.nova.ncie

/**
 * v9.33.126 "A count that could not be read is not zero articles".
 *
 * The offline Wikipedia store keeps its article count in `wiki/count`, one
 * integer, written by the downloader. [org.nova.WikiCore.articleCount] used to
 * do the whole job itself: `if (f.exists()) f.readText().trim().toIntOrNull()
 * ?: 0 else 0`. Two things were wrong with that one line.
 *
 *  - `readText()` can throw. It was not wrapped, so an unreadable count file
 *    took the caller down with it - and the caller is the Knowledge screen,
 *    drawing its status line. A store that cannot be read must not crash the
 *    screen that reports on it.
 *  - everything unreadable became 0, which is also the honest answer for
 *    "there is no count file yet". Those are different situations and the
 *    screen says different things about them.
 *
 * The parse is pure and lives here, outside the Android classes, so a plain
 * JVM unit test pins it. A negative count is not a count either - it is
 * garbage - so it reads as zero, never as a negative number of articles.
 */
object WikiCount {

    /** The article count in [raw], or 0 when [raw] is absent or not a count. */
    fun parse(raw: String?): Int {
        val n = raw?.trim()?.toIntOrNull() ?: return 0
        return if (n < 0) 0 else n
    }
}
