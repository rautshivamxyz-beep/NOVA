package org.nova.ncie.android

/**
 * v9.33.102 "The embedding index knows what it was built from".
 *
 * A source's semantic index (`filesDir/embeddings/<name>.emb`) is a list of
 * vectors, one per chunk, and the ONLY thing NOVA compared before deciding the
 * index was current was the chunk COUNT (`indexSize(name) != chunks.size`).
 *
 * Count is not identity. Editing one note into the same number of chunks,
 * re-saving a document with a corrected sentence, or removing one document
 * while adding another of equal size all leave the count unchanged - and the
 * index is then never rebuilt. Every later question is scored against the old
 * text's vectors but the scores are read as if they belonged to the new
 * chunks: a chunk that has nothing to do with the question can score high, the
 * chunk that does answer it can score low, and the answer NOVA grounds and
 * cites is built from the wrong material. It stays wrong for as long as the
 * count happens to match.
 *
 * [ofTexts] is the fix: a stable 64-bit fingerprint of the exact chunk list the
 * index was built from, written beside the index and compared on every update.
 * Any change to any chunk - a word, the order, the count - changes it, so a
 * stale index is rebuilt instead of being trusted.
 *
 * FNV-1a 64: no dependency, no allocation per character, identical on every
 * JVM and every phone. Pure arithmetic over a string list, so a unit test can
 * pin it.
 */
object NcieFingerprint {

    /** FNV-1a 64 offset basis (0xcbf29ce484222325 as a signed Long). */
    private const val OFFSET = -0x340d631b7bdddcdbL

    /** FNV-1a 64 prime. */
    private const val PRIME = 0x100000001b3L

    /** Separates one chunk from the next, so ("ab","c") and ("a","bc") differ. */
    private const val SEP = 0x1f

    /** The fingerprint of one text. */
    fun ofText(text: String): Long {
        var h = OFFSET
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xffL)
            h *= PRIME
        }
        return h
    }

    /**
     * The fingerprint of a whole chunk list, in order.
     *
     * Each chunk is folded in with its own bytes followed by [SEP], so the
     * chunk boundaries are part of the fingerprint - moving a character from
     * the end of one chunk to the start of the next is a different list and
     * gives a different value.
     */
    fun ofTexts(texts: List<String>): Long {
        var h = OFFSET
        for (t in texts) {
            for (b in t.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xffL)
                h *= PRIME
            }
            h = h xor SEP.toLong()
            h *= PRIME
        }
        return h
    }
}
