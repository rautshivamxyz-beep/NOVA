package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfEvolutionEngineTest {
    @Test fun selfLearningDataChecksPass() {
        val checks = SelfEvolutionEngine.selfTests()
        assertEquals(2, checks.size)
        assertTrue(checks.all { it.second })
    }
}
