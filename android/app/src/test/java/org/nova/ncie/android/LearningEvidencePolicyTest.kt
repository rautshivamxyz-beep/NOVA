package org.nova.ncie.learn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nova.ncie.model.VerifyResult

class FactualAnswerCachePolicyTest {

    @Test
    fun sourceFreeFactualAnswersAreNotCached() {
        val strong = VerifyResult(true, 0.90, "strong score without a source")
        assertFalse(LearningEvidencePolicy.mayCacheAnswer(true, emptyList(), strong))
    }

    @Test
    fun merelyNonEmptyButWeakEvidenceDoesNotAuthorizeFactualCaching() {
        val weak = VerifyResult(true, 0.60, "weak grounding")
        assertFalse(LearningEvidencePolicy.mayCacheAnswer(
            true, listOf("An unrelated source excerpt."), weak
        ))
    }

    @Test
    fun stronglyGroundedFactualAnswersMayBeCached() {
        val strong = VerifyResult(true, 0.80, "strong grounding")
        assertTrue(LearningEvidencePolicy.mayCacheAnswer(
            true, listOf("A source excerpt that supports the answer."), strong
        ))
    }

    @Test
    fun ordinaryConversationAnswersCanStillBeCachedWithoutSources() {
        val ordinary = VerifyResult(true, 0.60, "source-free conversation")
        assertTrue(LearningEvidencePolicy.mayCacheAnswer(false, emptyList(), ordinary))
    }

    @Test
    fun failedGroundingNeverAuthorizesFactualCaching() {
        val failed = VerifyResult(false, 0.95, "failed verifier")
        assertFalse(LearningEvidencePolicy.mayCacheAnswer(
            true, listOf("A source excerpt."), failed
        ))
    }
}
