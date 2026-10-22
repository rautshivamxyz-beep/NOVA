package org.nova.ncie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.52: the "tiny" decision switches off one background source and the exam
 * line. It used to be an inline regex nobody could see; now it is pinned here.
 */
class ModelProfileTest {

    @Test
    fun theSizeIsReadFromTheLabel() {
        assertEquals(1.2, ModelProfile.billions("LFM 2.5 1.2B Instruct")!!, 0.001)
        assertEquals(8.0, ModelProfile.billions("Qwen 3 8B Instruct")!!, 0.001)
        assertEquals(0.3, ModelProfile.billions("Some 0.3B Model")!!, 0.001)
        // a label that only says "230M" has no B, so there is no size to read
        assertEquals(null, ModelProfile.billions("LFM2-230M"))
        assertEquals(null, ModelProfile.billions("Some Model"))
    }

    /**
     * The current rule, stated plainly so it is a decision rather than a
     * surprise: LFM 1.2B IS on the lean profile, so on that model one
     * background source and the exam line are already switched off.
     */
    @Test
    fun anythingAtOrUnderTwoBillionGetsTheLeanProfile() {
        assertTrue(ModelProfile.isTiny("LFM 2.5 1.2B Instruct"))
        assertTrue(ModelProfile.isTiny("Llama 3.2 1B Instruct"))
        assertTrue(ModelProfile.isTiny("Qwen 2.5 1.5B"))
        assertTrue(ModelProfile.isTiny("Some 2B Model"))
        assertFalse(ModelProfile.isTiny("Qwen 3 8B Instruct"))
        assertFalse(ModelProfile.isTiny("Some Model"))
    }

    @Test
    fun theHealthLineSaysWhatIsSwitchedOff() {
        assertTrue(ModelProfile.describe("LFM 2.5 1.2B Instruct").contains("lean profile"))
        assertTrue(ModelProfile.describe("LFM 2.5 1.2B Instruct").contains("no exam line"))
        assertTrue(ModelProfile.describe("Qwen 3 8B").contains("full profile"))
    }
}
