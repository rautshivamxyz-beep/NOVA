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

    /** Space-padded normalized form for whole-word matching (" term "). */
    fun norm(s: String): String = " " + split(s).joinToString(" ") + " "
}
