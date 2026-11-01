package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v9.33.67: the busy-reply line parser gets its first unit tests.
 *
 * [NcieStyle.parseLines] turns the model's raw reply into the short lines the
 * notification auto-responder rotates through while the user is away. It is
 * pure, deterministic string work, and it runs on EVERY generated busy line
 * (NcieStyle.generateBusyLines / busyReply). A silent regression here ships a
 * list marker ("1.", "-", "*", ")") or a stray quote straight into a message
 * the user's contacts receive, or drops a good line because the length window
 * moved - with nothing failing anywhere. The behaviour is pinned directly.
 *
 * Pure JVM, no Android calls - run with `gradle testDebugUnitTest`
 * (CI runs it on every pull request).
 */
class NcieStyleTest {

    // ---- a plain sentence passes through unchanged ----

    @Test
    fun aPlainLineIsKeptUnchanged() {
        assertEquals(
            listOf("Tell me fast, I am busy."),
            NcieStyle.parseLines("Tell me fast, I am busy."))
    }

    // ---- list markers the model likes to add are stripped ----

    @Test
    fun numberedListMarkersAreStripped() {
        assertEquals(
            listOf("Busy right now", "In a meeting"),
            NcieStyle.parseLines("1. Busy right now\n2. In a meeting"))
    }

    @Test
    fun dashAndBulletMarkersAreStripped() {
        assertEquals(
            listOf("Working now", "Almost done"),
            NcieStyle.parseLines("- Working now\n\u2022 Almost done"))
    }

    @Test
    fun aStarMarkerIsStripped() {
        assertEquals(
            listOf("Busy right now"),
            NcieStyle.parseLines("* Busy right now"))
    }

    // ---- surrounding quotes are trimmed ----

    @Test
    fun quotesAroundALineAreTrimmed() {
        assertEquals(
            listOf("Busy right now"),
            NcieStyle.parseLines("\"Busy right now\""))
    }

    // ---- the 3..80 length window ----

    @Test
    fun shortAndBlankLinesAreDropped() {
        assertEquals(
            listOf("A real reply"),
            NcieStyle.parseLines("ok\n\nhi\nA real reply"))
    }

    @Test
    fun anEightyCharLineIsKeptAndAnEightyOneIsDropped() {
        val ok = "x".repeat(80)
        val tooLong = "y".repeat(81)
        assertEquals(
            listOf(ok),
            NcieStyle.parseLines(ok + "\n" + tooLong))
    }

    // ---- duplicates collapse ----

    @Test
    fun duplicateLinesAreCollapsed() {
        assertEquals(
            listOf("Busy right now"),
            NcieStyle.parseLines("Busy right now\nBusy right now"))
    }
}
