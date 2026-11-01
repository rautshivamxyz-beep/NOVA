package org.nova.ncie.android

/**
 * F3 "Outgoing-answer grounding check" (v9.33.81): a reply may not claim a
 * source that does not exist.
 *
 * Small models love to open with "According to your notes..." or "In your
 * document you wrote...". When the user has no notes at all, that sentence is
 * a fabrication about the user's own material - the worst kind, because it is
 * the one thing the user could check and cannot trust. The prompt-side rules
 * already tell the model not to invent; this is the deterministic check on the
 * text that actually leaves the app, which no prompt can guarantee.
 *
 * Narrow on purpose: it fires ONLY when the install holds no documents at all.
 * With material present NOVA cannot know which chunks the turn used, and
 * rewriting a correct citation would be its own bug - so nothing is touched
 * there. Pure string work, unit-tested, no model, no IO.
 */
object NcieClaim {

    /** Phrases that assert the answer came from the user's own material.
     *  Deliberately excludes "your message"/"you said" (the conversation is
     *  real material) and "the text" (which may be a fetched page). */
    private val CLAIM = Regex(
        "(?i)(?:" +
            "\\baccording to your (?:notes?|documents?|pdf|file)\\b" +
            "|\\bfrom your (?:notes?|documents?|pdf|file)\\b" +
            "|\\bin your (?:notes?|documents?|pdf|file)\\b" +
            "|\\byour (?:notes?|documents?|pdf|file)\\s+" +
            "(?:say|says|said|state|states|mention|mentions|show|shows|indicate|indicates|contain|contains)\\b" +
            "|\\byou wrote (?:in|on|about)\\b" +
            ")")

    /** The honest note appended when a claim had to be corrected. Short, and
     *  only ever shown on an install that holds nothing to quote. */
    const val NO_MATERIAL_NOTE =
        "(I don't have any of your notes or documents yet, so this is general knowledge.)"

    /** True when [text] asserts the user's own material as its source. */
    fun claimsSource(text: String): Boolean = CLAIM.containsMatchIn(text)

    /**
     * [reply] with any impossible source claim neutralised. A reply that makes
     * no claim, or an install that holds material, comes back unchanged - the
     * caller can pass every reply through here.
     */
    fun check(reply: String, hasMaterial: Boolean): String {
        if (hasMaterial) return reply
        if (!claimsSource(reply)) return reply
        val neutral = CLAIM.replace(reply, "in general")
        val body = neutral.trimEnd()
        if (body.contains(NO_MATERIAL_NOTE)) return body
        return body + "\n\n" + NO_MATERIAL_NOTE
    }
}
