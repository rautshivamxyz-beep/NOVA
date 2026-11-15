package org.nova.ncie.verify

import org.nova.ncie.Tokens
import org.nova.ncie.model.VerifyResult

/**
 * Shared evidence decision layer for fetched research and durable memory.
 *
 * This deliberately keeps two different questions separate:
 * - fetched-source relevance: does the page cover enough of the query?
 * - durable-memory eligibility: did a passing answer have real source text
 *   and clear the stricter grounding score?
 *
 * Lexical coverage is a relevance signal, not proof that a claim is true.
 */
object EvidencePipeline {

    /** Same acceptance rule previously duplicated in OnlineFetch:
     * sufficient query coverage OR at least two whole-word content hits. */
    fun acceptsFetchedSource(
        query: String,
        sourceText: String,
        minCoverage: Double = 0.15,
        minWholeWordHits: Int = 2,
    ): Boolean {
        if (query.isBlank() || sourceText.isBlank()) return false
        val coverage = Coverage.ratio(query, sourceText)
        val terms = Tokens.contentWords(query).distinct()
        val normalizedSource = Tokens.norm(sourceText)
        val hits = terms.count { normalizedSource.contains(" $it ") }
        return coverage >= minCoverage || hits >= minWholeWordHits
    }

    /** Source-free quality passes may be cached briefly, but never become
     * durable factual memory. The caller supplies the verifier's verdict. */
    fun mayPersistMemory(
        sources: List<String>,
        verdict: VerifyResult,
        minScore: Double,
    ): Boolean {
        if (!verdict.passed || verdict.qualityScore < minScore) return false
        return sources.any { it.isNotBlank() }
    }
}
