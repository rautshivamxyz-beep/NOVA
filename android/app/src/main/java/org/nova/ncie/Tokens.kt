package org.nova.ncie

/**
 * v9.33.19 "Hindi/Marathi retrieval": one Unicode-aware word tokenizer.
 *
 * Every tokenizer on the retrieval path was `split(Regex("[^a-z0-9]+"))`,
 * copied into a dozen files. That drops Devanagari, so a Hindi or Marathi
 * question produced ZERO keywords - notes and offline-Wikipedia search came
 * back empty and NOVA either answered from imagination or said "ask in
 * English for now". The same helper also ends the copy-paste: one definition,
 * so a fix here lands everywhere.
 *
 * Letters, combining marks and digits of ANY script are kept (the marks
 * matter: Devanagari vowel signs are `\p{M}`, so dropping them would chop
 * every Hindi word at its matras). The behaviour is unchanged
 * for English (after lowercasing, `[^a-z0-9]` and `[^\p{L}\p{N}]` agree on
 * ASCII) and now correct for Devanagari and every other script.
 */
object Tokens {

    private val SPLIT = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /** Lowercased words of any script. */
    fun split(s: String): List<String> =
        s.lowercase().split(SPLIT).filter { it.isNotEmpty() }

    /** Lowercased words of at least [minLen] characters.
     *  v9.33.25: a Devanagari word is short in CHARACTER count - the
     *  vowel signs (matras) are separate code points - so a 2-char word
     *  is a real content word ("घर" = house). With a minLen above 2 a
     *  2-char word now qualifies when it carries a non-ASCII letter, so
     *  a Hindi/Marathi question's content words reach the keyword set
     *  (the online fetch, the truth check, the grounding terms). Pure
     *  ASCII keeps the plain ">= minLen" rule, unchanged. */
    fun words(s: String, minLen: Int = 1): List<String> =
        split(s).filter {
            it.length >= minLen ||
                (minLen > 2 && it.length == 2 && it.any { c -> c.code > 127 })
        }

    /**
     * v9.33.76 "Every-script retrieval terms": the ONE definition of a
     * content word on the retrieval path - the words of a query or a page
     * that are worth matching on.
     *
     * v9.33.19 gave every script a tokenizer, but four retrieval call sites
     * kept the old hand-rolled rule `Tokens.split(x).filter { it.length > 2 }`.
     * That rule counts CHARACTERS, and a Devanagari content word is short in
     * characters because its vowel signs are separate code points: "घर"
     * (house) and "मन" (mind) are two characters and were silently dropped.
     * A Hindi question therefore lost its real content words before matching
     * - the tool scorer saw no terms at all, the search acceptance gate
     * counted zero keyword hits, and the document window had nothing to rank
     * on. Exactly the failure v9.33.19 set out to end, surviving in the four
     * places that were missed.
     *
     * [words] already carries the rule (ASCII keeps ">= minLen", a 2-character
     * word qualifies when it holds a non-ASCII letter), so this is a NAME for
     * it rather than a second implementation. English behaviour is identical
     * to the old filter; every other script now behaves like English.
     */
    fun contentWords(s: String, minLen: Int = 3): List<String> = words(s, minLen)

    /** Space-padded normalized form for whole-word matching (" term "). */
    fun norm(s: String): String = " " + split(s).joinToString(" ") + " "
}
