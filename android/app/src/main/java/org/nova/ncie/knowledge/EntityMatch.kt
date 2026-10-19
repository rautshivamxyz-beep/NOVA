package org.nova.ncie.knowledge

/**
 * v9.33.14 "The right entity or nothing".
 *
 * A "who is X" question was answered with whatever article Wikipedia ranked
 * first, even when that article was about somebody else who merely MENTIONED
 * the words: "who is physics wallah" returned the Radha Bhatt intro (her page
 * lists a web series called Physics Wallah), "who is mia ham" returned Mia
 * Tomlinson. The coverage gate only asked "does the text contain the query
 * words", never "is the article ABOUT the query".
 *
 * This is the missing half: does the article's TITLE name the entity the user
 * asked about? Pure string logic, no Android - unit-tested in
 * android/app/src/test/java.
 */
object EntityMatch {

    /** Question words that are never part of the entity being asked about. */
    private val STOP = setOf(
        "who", "what", "when", "where", "why", "how", "which", "whose",
        "is", "was", "are", "were", "am", "be", "been", "being",
        "the", "a", "an", "of", "in", "on", "at", "to", "for", "about", "with",
        "tell", "me", "please", "give", "show", "explain", "describe", "define",
        "kaun", "kya", "kahan", "kab", "hai", "hain", "tha", "thi",
    )

    /** The significant words of the question - the entity it names. */
    fun entityTerms(question: String): List<String> =
        question.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in STOP }
            .distinct()

    /**
     * True when [title] names the entity in [question]. EVERY term of 3+
     * characters in the question must appear, whole-word, in the title; 1-2
     * char tokens are ignored, because they are initials or noise and
     * requiring them would reject the very titles that spell initials out
     * ("jk rowling" must accept "J. K. Rowling").
     *
     * Requiring ALL of them is deliberate: it rejects "physics wallah" for
     * "Radha Bhatt" and "mia ham" for "Mia Tomlinson", and abstaining on a
     * doubtful match is always better than serving the wrong person. A
     * question with nothing to look up never matches.
     */
    fun titleMatchesEntity(title: String, question: String): Boolean {
        val terms = entityTerms(question).filter { it.length >= 3 }
        if (terms.isEmpty()) return false
        val t = " " + title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        return terms.all { t.contains(" " + it + " ") }
    }
}
