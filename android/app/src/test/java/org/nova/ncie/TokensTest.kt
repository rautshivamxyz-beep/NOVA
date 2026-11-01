package org.nova.ncie

import org.junit.Assert.assertEquals
import org.junit.Test

/** v9.33.19: the tokenizer keeps every script, and English is unchanged. */
class TokensTest {

    @Test
    fun englishIsUnchanged() {
        assertEquals(
            listOf("who", "is", "physics", "wallah"),
            Tokens.split("Who is physics wallah?"))
        assertEquals(" who is physics wallah ", Tokens.norm("Who is physics wallah?"))
    }

    @Test
    fun devanagariIsKept() {
        // the whole point: a Hindi question must yield keywords, not zero
        assertEquals(listOf("ताजमहल", "कहाँ", "है"), Tokens.split("ताजमहल कहाँ है?"))
        assertEquals(3, Tokens.words("ताजमहल कहाँ है?", 2).size)
    }

    @Test
    fun mixedScript() {
        assertEquals(listOf("modi", "का", "जन्म"), Tokens.split("Modi का जन्म"))
    }

    @Test
    fun contentWordsKeepShortDevanagari() {
        // v9.33.76: "घर" (house) and "मन" (mind) are two CHARACTERS because
        // the vowel signs are separate code points. The old
        // `Tokens.split(x).filter { it.length > 2 }` rule dropped them, so a
        // Hindi query lost its content words before anything matched.
        assertEquals(listOf("घर", "कहाँ", "है"), Tokens.contentWords("घर कहाँ है?"))
        // English behaviour is exactly the old filter: two letters still go.
        assertEquals(listOf("the", "cat"), Tokens.contentWords("the cat is in"))
    }

    @Test
    fun punctuationAndDigitsSurviveAsWords() {
        assertEquals(listOf("chapter", "10"), Tokens.split("chapter 10!"))
    }
}
