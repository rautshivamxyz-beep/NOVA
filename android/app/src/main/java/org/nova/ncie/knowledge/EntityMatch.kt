package org.nova.ncie.knowledge

import org.nova.ncie.Tokens

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
        Tokens.split(question)
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
        val norm = Tokens.norm(title).trim()
        val spaced = " " + norm + " "
        // v9.33.15: a title may run the entity together ("PhysicsWallah") -
        // a term is also accepted when it is contained in the de-spaced title.
        val deSpaced = norm.replace(" ", "")
        return terms.all { spaced.contains(" " + it + " ") || deSpaced.contains(it) }
    }

    /** Requests to locate another person's private/current position. */
    fun isPrivateLocationTrackingRequest(question: String): Boolean {
        val q = question.trim()
        return Regex(
            "(?i)\\b(?:find|track|locate|show|tell\\s+me|what\\s+is)\\b.{0,40}" +
                "\\b(?:his|her|their|someone'?s|that\\s+person'?s)\\s+" +
                "(?:(?:current|live|real[- ]time|exact)\\s+)?location\\b"
        ).containsMatchIn(q) ||
            Regex("(?i)\\bwhere\\s+is\\s+(?:he|she|they)\\s+(?:now|right\\s+now)\\b")
                .containsMatchIn(q)
    }

    /** Common surnames that are not enough to identify one person reliably. */
    private val AMBIGUOUS_SINGLE_NAMES = setOf(
        "modi", "singh", "kumar", "sharma", "gupta", "patel", "khan",
        "gandhi", "yadav", "verma", "das", "roy", "mahato", "mahto",
        "jha", "mishra", "sinha", "reddy", "rao", "choudhary", "chowdhury",
        "mehta", "kapoor", "malhotra", "joshi", "thakur", "bose"
    )

    /** Resolve common surname-only lookups before any local or online search. */
    fun clarificationFor(question: String): String? {
        if (!isEntityQuestion(question)) return null
        val terms = Tokens.split(entityPhrase(question)).filter { it.length > 1 }
        if (terms.size != 1 || terms[0] !in AMBIGUOUS_SINGLE_NAMES) return null
        val name = terms[0].replaceFirstChar { it.uppercase() }
        return "Which $name do you mean? Please give the person's first name or a little context so I don't identify the wrong person."
    }

    /**
     * v9.33.38: the SHAPE of question a source lookup is for - a short
     * question naming one thing ("who is X", "what is X").
     *
     * This rule lived in OnlineFetch only, so the ONLINE Wikipedia path
     * refused an article that did not name the entity while the OFFLINE path
     * served whatever scored highest. It lives here now, so both paths apply
     * exactly the same test.
     *
     * v9.33.0: it used to ask "does it start with what/who", which sent "what
     * things you can do for me" to Wikipedia and answered with the plot of a
     * film called "You Can Count on Me". A source answer is for a SHORT
     * question naming one thing, so the shape AND the length are required.
     */
    fun isEntityQuestion(q: String): Boolean {
        val t = q.trim()
        if (t.length > 60) return false
        // a question about NOVA itself ("what can you do") is not a lookup
        val low = " " + t.lowercase()
        if (low.contains(" you") || low.contains("your")) return false
        return Regex("(?i)^(who|what)\\s+(is|was|are|were)\\b").containsMatchIn(t) ||
            Regex("(?i)^(kaun|kya|kahan|kab)\\b.*\\b(hai|hain|tha|thi)\\b").containsMatchIn(t)
    }

    /**
     * v9.33.15: the entity phrase a "who is X" question names - the question
     * with its lead-in words stripped, so it can be looked up as a page TITLE
     * ("who is physics wallah" -> "physics wallah").
     */
    fun entityPhrase(question: String): String =
        question.lowercase()
            .replace(Regex("^(who|what|which|whose|where|when|why|how)\\s+(is|was|are|were|am)\\s+"), "")
            .replace(Regex("^(kaun|kya|kahan|kab)\\s+"), "")
            .replace(Regex("[?.!]+\\s*$"), "")
            .trim()
}
