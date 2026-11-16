package org.nova.ncie.evaluate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegressionLabTest {
    @Test fun deterministicLearningRegressionSuitePasses() {
        val results = RegressionLab.run()
        assertEquals(RegressionLab.EXPECTED_CHECKS, results.size)
        assertTrue(results.filterNot { it.second }.joinToString { it.first }.let { it.isEmpty() })
    }
}
