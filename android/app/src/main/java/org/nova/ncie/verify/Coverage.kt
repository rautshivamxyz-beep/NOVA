package org.nova.ncie.verify

import org.nova.ncie.knowledge.WikiStore

/**
 * v0.9.4 (stage 1): the fetched-knowledge gate. When the host reaches
 * ONLINE to fill a knowledge gap, this decides whether what came back
 * is actually about what was asked - the same whole-word term logic the
 * Verifier uses to ground answers in their sources, pointed the other
 * way: does the FETCHED text cover the QUESTION?
 *
 * The host's rule: accept at ratio >= 0.3 (the Verifier's own drift
 * bar), reject and discard below it. A rejected fetch never touches
 * the knowledge base - garbage never gets in.
 */
object Coverage {

    /** The fraction of the query's significant terms (WikiStore's own
     *  tokenizer: lowercase, >2 chars, stopwords dropped) that appear
     *  as whole words in the text. 0.0 when the query carries no
     *  significant terms at all. */
    fun ratio(query: String, text: String): Double {
        val q = WikiStore().words(query)
        if (q.isEmpty()) return 0.0
        val words = WikiStore().words(text)
        val covered = q.count { it in words }
        return covered.toDouble() / q.size
    }
}
