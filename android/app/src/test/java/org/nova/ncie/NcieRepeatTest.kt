package org.nova.ncie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.87: the repetition detector. Both failure directions are pinned here,
 * because either one is a bug the user feels immediately - a loop that is not
 * caught burns the whole generation budget, and a good answer cut short is
 * worse than no feature at all.
 */
class NcieRepeatTest {

    @Test
    fun aRepeatedSentenceIsALoop() {
        val unit = "The capital of France is Paris. "
        assertTrue(NcieRepeat.looped("Paris is in France. " + unit + unit + unit))
    }

    @Test
    fun twoCopiesAreNotYetALoop() {
        val unit = "The capital of France is Paris. "
        assertFalse(NcieRepeat.looped("Intro text here. " + unit + unit))
    }

    @Test
    fun aStuckShortFragmentIsALoop() {
        val text = "I think so, " + "and then ".repeat(10)
        assertTrue(NcieRepeat.looped(text))
    }

    @Test
    fun ordinaryProseIsNotALoop() {
        val prose = "Water boils at 100 degrees Celsius at sea level. " +
            "At higher altitude the pressure is lower, so the boiling point " +
            "falls. That is why cooking takes longer in the mountains, and " +
            "why a pressure cooker speeds it up at home."
        assertFalse(NcieRepeat.looped(prose))
    }

    @Test
    fun aMarkdownRuleIsFormattingNotALoop() {
        val table = "| Step | Time |\n|---|---|\n|---|---|\n|---|---|\n|---|---|\n|---|---|\n"
        assertFalse(NcieRepeat.looped(table))
    }

    @Test
    fun aRunOfOneCharacterIsNotALoop() {
        assertFalse(NcieRepeat.looped("Result: " + "-".repeat(60)))
        assertFalse(NcieRepeat.looped("Hmm" + ".".repeat(40)))
    }

    @Test
    fun aRepeatedCodeLineIsTolerated() {
        // A listing may legitimately repeat a short line a few times; three
        // copies of a 12-character line must not be called a loop.
        val code = "fun a() {\n    return null\n    return null\n    return null\n}\n"
        assertFalse(NcieRepeat.looped(code))
    }

    @Test
    fun theCycleLengthIsReported() {
        val unit = "NOVA keeps your chats on the phone. "
        val len = NcieRepeat.cycle(unit + unit + unit)
        assertNotNull(len)
        assertEquals(unit.length, len!!.toInt())
    }

    @Test
    fun anEmptyOrShortTextIsNeverALoop() {
        assertFalse(NcieRepeat.looped(""))
        assertFalse(NcieRepeat.looped("hi"))
        assertFalse(NcieRepeat.looped("ha ha ha ha"))
        assertNull(NcieRepeat.cycle("short"))
    }

    @Test
    fun onlyTheTailIsJudged() {
        // The same sentence twice at the start of a long answer is fine; the
        // detector judges the end of the stream, where a loop actually is.
        val unit = "This is a repeated opening line. "
        val body = "A pressure cooker traps steam, which raises the pressure " +
            "inside and therefore the boiling point of the water. Food cooks " +
            "faster because the liquid stays hotter before it turns to vapour. " +
            "The valve on top releases excess steam so the vessel does not " +
            "fail, and the gasket keeps the seal tight."
        assertFalse(NcieRepeat.looped(unit + unit + body))
    }
}
