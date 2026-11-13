package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfEvolutionEngineTest {
    @Test fun selfLearningDataChecksPass() {
        val checks = SelfEvolutionEngine.selfTests()
        assertEquals(8, checks.size)
        assertTrue(checks.all { it.second })
    }

    /**
     * v9.33.147: the nightly audit is shown to the user and re-read by
     * lastSummary() on the next run, so "saved" and "not saved" are different
     * outcomes. It used to answer the same way for both - the write sat inside
     * a try/catch that had been dead since v9.33.90 - so a report that never
     * reached the disk was presented as if it had.
     */
    @Test fun aSavedReportIsUnchanged() {
        val report = "SELF-EVOLUTION AUDIT\nself-tests: 8/8 passed"
        assertEquals(report, SelfEvolutionEngine.reportWithSaveState(report, true))
    }

    @Test fun anUnsavedReportSaysSo() {
        val report = "SELF-EVOLUTION AUDIT\nself-tests: 8/8 passed"
        val out = SelfEvolutionEngine.reportWithSaveState(report, false)
        assertTrue(out.startsWith(report))
        assertTrue(out.contains("WARNING: this report could not be saved"))
        assertTrue(out.contains("gone at the next launch"))
    }

    /** The warning must never replace the report it is warning about. */
    @Test fun theWarningKeepsTheWholeReport() {
        val report = "SELF-EVOLUTION AUDIT\n  PASS skill parse\n  FAIL gap ranking"
        val out = SelfEvolutionEngine.reportWithSaveState(report, false)
        assertTrue(out.contains("  PASS skill parse"))
        assertTrue(out.contains("  FAIL gap ranking"))
    }
}
