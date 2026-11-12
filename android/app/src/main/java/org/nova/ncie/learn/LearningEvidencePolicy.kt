package org.nova.ncie.learn

import org.nova.ncie.model.VerifyResult

/**
 * Decides whether a chat answer is eligible for durable factual memory.
 *
 * The general quality gate is intentionally permissive for ordinary chat:
 * without sources it can reject malformed output, but it cannot establish
 * factual correctness. A source-free pass receives the default 0.6 score.
 * Such replies may remain in the short-lived answer cache, but must not be
 * promoted by repetition into the permanent knowledge store.
 */
object LearningEvidencePolicy {

    /**
     * Long-term factual memory requires a passing verdict, non-blank source
     * material, and a score above the source-free default. The score check
     * also rejects answers for which sources were supplied but no meaningful
     * source-grounding comparison could be performed.
     */
    fun mayPersistFact(sources: List<String>, verdict: VerifyResult): Boolean {
        if (!verdict.passed || verdict.qualityScore < MIN_GROUNDED_SCORE) return false
        return sources.any { it.isNotBlank() }
    }

    const val SOURCE_FREE_SCORE = 0.6

    // quality() computes 0.5 + 0.45 * lexical source coverage. A 0.725
    // floor requires at least 50% of significant answer terms to occur in
    // the supplied source, rather than accepting the 30% drift boundary.
    const val MIN_GROUNDED_SCORE = 0.725
}
