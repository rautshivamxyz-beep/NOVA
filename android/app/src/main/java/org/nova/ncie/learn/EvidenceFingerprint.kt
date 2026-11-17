package org.nova.ncie.learn

import java.security.MessageDigest

/**
 * Privacy-preserving identifiers for the local note chunks that grounded a
 * durable learned fact. Only hashes are persisted; source text is not copied
 * into the memory ledger.
 */
object EvidenceFingerprint {
    const val MAX_EVIDENCE = 4

    /** Normalize whitespace, hash the full chunk, deduplicate, and cap work. */
    fun fromSources(sources: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (source in sources) {
            val normalized = source.trim().replace(Regex("\\s+"), " ")
            if (normalized.isEmpty()) continue
            seen.add(sha256(normalized))
            if (seen.size >= MAX_EVIDENCE) break
        }
        return seen.toList()
    }

    fun isValid(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
