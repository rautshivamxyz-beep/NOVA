package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.129 "A store that could not be read is not an empty store", applied to
 * the per-model tuning overrides.
 *
 * `setCommand` is a read-modify-write over one `key=value` line per knob: it
 * reads the file, sets one key, and writes the whole file back. The read used
 * to flatten every failure to an empty map, so a file that was there and
 * unreadable made the very next "set temperature 0.7" rewrite the file from
 * nothing and delete every other knob the user had tuned - while answering
 * "set to 0.7". The fix splits the two answers the read was conflating, and
 * [NcieTune.parseOverrides] is the pure half that can be pinned here.
 *
 * The other half - that an unreadable file answers null and setCommand refuses
 * to write - needs a Context and a filesystem, so it is pinned by
 * verify_patches reading the source instead.
 */
class NcieTuneOverridesTest {

    @Test
    fun aWellFormedLineIsRead() {
        val v = NcieTune.parseOverrides("temperature=0.7\n")
        assertEquals(0.7, v.getValue("temperature"), 0.0)
    }

    @Test
    fun everyKnobOnItsOwnLineIsRead() {
        val v = NcieTune.parseOverrides("temperature=0.7\ntop_k=40\ntop_p=0.9\n")
        assertEquals(3, v.size)
        assertEquals(40.0, v.getValue("top_k"), 0.0)
        assertEquals(0.9, v.getValue("top_p"), 0.0)
    }

    @Test
    fun aKeyTheAppDoesNotKnowIsIgnored() {
        // a stale or hand-edited key must not reach the sampler
        val v = NcieTune.parseOverrides("temperature=0.7\nmade_up=1.0\n")
        assertEquals(1, v.size)
        assertTrue(v.containsKey("temperature"))
    }

    @Test
    fun aValueThatIsNotANumberIsIgnored() {
        val v = NcieTune.parseOverrides("temperature=warm\ntop_k=40\n")
        assertEquals(1, v.size)
        assertTrue(v.containsKey("top_k"))
    }

    @Test
    fun aLineWithoutAnEqualsSignIsIgnored() {
        val v = NcieTune.parseOverrides("temperature\ntop_k=40\n")
        assertEquals(1, v.size)
    }

    @Test
    fun anEmptyKeyIsIgnored() {
        // indexOf('=') is 0, which is not a key
        val v = NcieTune.parseOverrides("=0.7\ntop_k=40\n")
        assertEquals(1, v.size)
    }

    @Test
    fun aSecondEqualsSignBelongsToTheValueAndIsNotANumber() {
        // "temperature=0.7=0.8" is not 0.7 - it is a damaged line, and a
        // damaged line is skipped rather than guessed at
        val v = NcieTune.parseOverrides("temperature=0.7=0.8\n")
        assertTrue(v.isEmpty())
    }

    @Test
    fun blankTextIsNoOverridesAtAll() {
        // "" is the file that is not there yet - a fresh model, nothing to lose
        assertTrue(NcieTune.parseOverrides("").isEmpty())
        assertTrue(NcieTune.parseOverrides("\n\n").isEmpty())
    }

    @Test
    fun aNumberWithSurroundingSpaceIsStillThatNumber() {
        val v = NcieTune.parseOverrides("  temperature = 0.7  \n")
        assertEquals(0.7, v.getValue("temperature"), 0.0)
    }

    @Test
    fun aNegativeOrFractionalValueIsReadAsWritten() {
        val v = NcieTune.parseOverrides("temperature=0.35\ntop_p=0.85\n")
        assertEquals(0.35, v.getValue("temperature"), 0.0)
        assertEquals(0.85, v.getValue("top_p"), 0.0)
    }
}
