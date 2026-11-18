package org.nova.ncie.learn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nova.ncie.model.VerifyResult

class LearningEvidencePolicyTest {

    @Test
    fun sourceFreeFactualAnswersAreNotCached() {
        assertFalse(LearningEvidencePolicy.mayCacheAnswer(true, emptyList()))
        assertFalse(LearningEvidencePolicy.mayCacheAnswer(true, listOf("", "  ")))
    }

    @Test
    fun factualAnswersWithRetrievedEvidenceMayBeCached() {
        assertTrue(LearningEvidencePolicy.mayCacheAnswer(
            true, listOf("A source excerpt that supports the answer.")
        ))
    }

    @Test
    fun ordinaryConversationAnswersCanStillBeCachedWithoutSources() {
        assertTrue(LearningEvidencePolicy.mayCacheAnswer(false, emptyList()))
    }

    @Test
    fun factualCacheEligibilityDoesNotReplaceTheDurableMemoryQualityGate() {
        val weak = VerifyResult(passed = true, qualityScore = 0.60, reason = "weak grounding")
        assertFalse(LearningEvidencePolicy.mayPersistFact(
            listOf("source text is present"), weak
        ))
    }
}
