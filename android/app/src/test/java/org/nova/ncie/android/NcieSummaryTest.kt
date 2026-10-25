package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v9.33.68: the rolling-summary sanitizer gets its first unit tests.
 *
 * [NcieSummary.sanitize] filters the stored rolling summary before it is
 * injected into every turn's preamble. The summarizer sometimes preserves the
 * meta markers of the prompts it was fed ("New message:", "Answer:", "(Reply",
 * "— end", "Nova:"), and a small model then mimics them in its replies instead
 * of answering. The filter is pure, deterministic string work and it runs on
 * every long chat's preamble; a silent regression here puts the markers back
 * into the model's mouth. The behaviour is pinned directly.
 *
 * Pure JVM, no Android calls - run with `gradle testDebugUnitTest`
 * (CI runs it on every pull request).
 */
class NcieSummaryTest {

    // ---- null and blank collapse to the empty string ----

    @Test
    fun aNullSummaryBecomesEmpty() {
        assertEquals("", NcieSummary.sanitize(null))
    }

    @Test
    fun aBlankSummaryBecomesEmpty() {
        assertEquals("", NcieSummary.sanitize("   \n  "))
    }

    // ---- ordinary content survives untouched ----

    @Test
    fun anOrdinarySummaryIsKeptUnchanged() {
        assertEquals(
            "The user studies physics.\nThey like cricket.",
            NcieSummary.sanitize("The user studies physics.\nThey like cricket."))
    }

    // ---- each meta marker is dropped, whole line ----

    @Test
    fun aNewMessageMarkerLineIsDropped() {
        assertEquals("Real fact", NcieSummary.sanitize("New message: hello\nReal fact"))
    }

    @Test
    fun anAnswerMarkerLineIsDropped() {
        assertEquals("Real fact", NcieSummary.sanitize("Answer: 42\nReal fact"))
    }

    @Test
    fun aReplyMarkerLineIsDropped() {
        assertEquals("Real fact", NcieSummary.sanitize("(Reply to them)\nReal fact"))
    }

    @Test
    fun anEndMarkerLineIsDropped() {
        assertEquals("Real fact", NcieSummary.sanitize("stuff \u2014 end\nReal fact"))
    }

    @Test
    fun aNovaMarkerLineIsDropped() {
        assertEquals("Real fact", NcieSummary.sanitize("Nova: sure\nReal fact"))
    }
}
