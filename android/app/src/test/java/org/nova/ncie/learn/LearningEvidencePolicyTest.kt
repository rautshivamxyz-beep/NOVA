package org.nova.ncie.learn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nova.ncie.model.VerifyResult

class LearningEvidencePolicyTest {

    @Test
    fun sourceFreeQualityPassCannotBecomeDurableFact() {
        assertFalse(
            LearningEvidencePolicy.mayPersistFact(
                emptyList(),
                VerifyResult(true, 0.6, "quality checks passed (no ground truth needed)")
            )
        )
    }

    @Test
    fun blankSourcesCannotBecomeEvidence() {
        assertFalse(
            LearningEvidencePolicy.mayPersistFact(
                listOf("", "  "),
                VerifyResult(true, 0.95, "quality checks passed")
            )
        )
    }

    @Test
    fun groundedPassingAnswerCanEnterProbationaryMemory() {
        assertTrue(
            LearningEvidencePolicy.mayPersistFact(
                listOf("Photosynthesis converts light into glucose"),
                VerifyResult(true, 0.95, "quality checks passed (grounding 100%)")
            )
        )
    }

    @Test
    fun failedGroundingNeverEntersDurableMemory() {
        assertFalse(
            LearningEvidencePolicy.mayPersistFact(
                listOf("The Taj Mahal is in Agra"),
                VerifyResult(false, 0.3, "answer drifts from its sources")
            )
        )
    }

    @Test
    fun borderlineSourceFreeScoreIsNotEnough() {
        assertFalse(
            LearningEvidencePolicy.mayPersistFact(
                listOf("some text"),
                VerifyResult(true, 0.6, "quality checks passed")
            )
        )
    }
}
