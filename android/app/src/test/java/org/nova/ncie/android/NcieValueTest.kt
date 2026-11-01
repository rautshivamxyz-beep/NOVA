package org.nova.ncie.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.95: the context-value tracker decides whether a context source may
 * be injected on a turn. A source that has been injected enough times and has
 * never contributed is skipped, and re-tested one turn in [NcieValue]'s
 * RETEST_EVERY so it can always earn its way back.
 *
 * That cadence used to ride a single shared counter advanced once per
 * eligible CALL, so with several tracked sources a skipped source came back
 * far more often than one turn in eight - and the prompt saving the tracker
 * exists for mostly evaporated. [NcieValue.decide] is pure, so the cadence is
 * pinned here rather than trusted.
 */
class NcieValueTest {

    @Test
    fun aSourceWithoutEnoughEvidenceIsAlwaysInjected() {
        // 14 injections is below the 15 the tracker requires before it may
        // skip anything - a source is never dropped on a hunch
        assertTrue(NcieValue.decide(14, 0, 0))
        assertTrue(NcieValue.decide(14, 0, 7))
    }

    @Test
    fun aSourceThatHasHelpedIsAlwaysInjected() {
        assertTrue(NcieValue.decide(40, 1, 0))
        assertTrue(NcieValue.decide(40, 1, 99))
    }

    @Test
    fun anUnhelpfulSourceIsSkippedForSevenTurns() {
        for (n in 0..7) assertFalse("skip $n", NcieValue.decide(40, 0, n))
    }

    @Test
    fun itIsRetestedOnTheEighthSkip() {
        assertTrue(NcieValue.decide(40, 0, 8))
        assertTrue(NcieValue.decide(40, 0, 16))
    }

    @Test
    fun theCadenceIsOneTurnInEightWhateverTheSourceCount() {
        // the bug: a shared counter advanced per call, so four sources made
        // the re-test fire on turn 2 instead of turn 8. The decision now
        // depends only on THIS source's own skip count.
        var allowed = 0
        for (n in 0 until 32) if (NcieValue.decide(40, 0, n)) allowed++
        // 8, 16, 24 -> three re-tests in 32 skipped turns
        org.junit.Assert.assertEquals(3, allowed)
    }

    @Test
    fun aZeroCadenceNeverRetests() {
        // a corrupt 0 must not mean "retest every turn"
        assertFalse(NcieValue.decide(40, 0, 0, 15, 0))
        assertFalse(NcieValue.decide(40, 0, 8, 15, 0))
    }

    @Test
    fun theThresholdsCanBeMovedWithoutChangingTheShape() {
        assertTrue(NcieValue.decide(3, 0, 0, 2, 4))
        assertFalse(NcieValue.decide(3, 0, 3, 2, 4))
        assertTrue(NcieValue.decide(3, 0, 4, 2, 4))
    }
}
