package org.nova.ncie.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.56: the offline-Wikipedia ranking gets its first unit test.
 *
 * [WikiStore.search] decides which article is handed to the model as
 * background, so a silent regression shows up as wrong answers, not as a
 * crash. The whole-word title bonus is pinned here because it already bit
 * once (v7.8.1): raw substring containment made the title "HIV" match
 * inside the query word "shivam", and "my name is shivam" pulled the HIV
 * article in as background.
 *
 * Pure JVM, no Android imports - run with `gradle testDebugUnitTest`
 * (CI runs it on every pull request).
 */
class WikiStoreTest {

    private val store = WikiStore()

    // title -> byte offset, plus the article line each offset reads back
    private val index = listOf(
        "HIV" to 0L,
        "Nelson Mandela" to 100L,
        "Shivam Raut" to 200L,
    )
    private val lines = mapOf(
        0L to "HIV\u241FHuman immunodeficiency virus is a retrovirus.",
        100L to "Nelson Mandela\u241FSouth African anti-apartheid leader and president.",
        200L to "Shivam Raut\u241FA student.",
    )

    private fun search(q: String, max: Int = 3) = store.search(index, q, max) { off -> lines[off] }

    @Test
    fun aTitleNeverMatchesInsideALongerQueryWord() {
        // v7.8.1: "shivam" must NOT drag in the "HIV" article
        val hits = search("my name is shivam")
        assertEquals("Shivam Raut", hits.first().title)
        assertTrue("HIV must not be returned", hits.none { it.title == "HIV" })
    }

    @Test
    fun theWholeWordTitleBonusRanksTheNamedArticleFirst() {
        val hits = search("who is nelson mandela")
        assertEquals("Nelson Mandela", hits.first().title)
        assertTrue(hits.first().text.isNotEmpty())
    }

    @Test
    fun maxResultsCapsTheHits() {
        assertEquals(1, search("mandela", max = 1).size)
    }

    @Test
    fun aQueryWithNoSignificantTermsReturnsNothing() {
        assertTrue(search("who is the").isEmpty())
    }
}
