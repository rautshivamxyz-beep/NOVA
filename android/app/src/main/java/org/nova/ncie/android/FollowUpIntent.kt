package org.nova.ncie.android

import org.nova.ncie.knowledge.EntityMatch

/** Narrow, context-bound follow-up routing. */
internal object FollowUpIntent {
    private val ANOTHER = Regex(
        "(?i)^(?:one more|another one|another|more|again|one more please|another please|more please)[.!?\\s]*$"
    )
    private val DEPTH_FEEDBACK = Regex(
        "(?i)\\b(?:too\\s+basic|basic\\s+facts|these\\s+are\\s+basic|that'?s\\s+basic|go\\s+deeper|more\\s+advanced|less\\s+obvious)\\b"
    )
    private val PERSON_RELATION = Regex(
        "(?i)^\\s*(?:(?:who|what|list|tell\\s+me)\\s+)?(?:(?:is|are|was|were)\\s+)?" +
            "(?:his|her|their)\\s+(brothers?|sisters?|parents?|father|mother|wife|husband|children|sons?|daughters?)\\s*[?.!]*\\s*$"
    )
    private val ONLINE_PREFIXES = listOf(
        "again wrong go and check online",
        "again wrong, check online",
        "wrong again, check online",
        "that's wrong, check online",
        "go and check online",
        "check again online",
        "check it online",
        "verify it online",
        "verify online",
        "check online",
        "check again",
        "look it up on the internet",
        "look it up online",
        "look up at online",
        "look up online",
        "search the internet",
        "search the web",
        "search online",
        "find it online",
        "look online",
        "google it"
    )

    private fun normalizeFollowUp(value: String): String =
        value.trim().replace(Regex("(?i)\\bmoee\\b"), "more")

    fun isAnotherJoke(input: String, previousUser: String?, previousAssistant: String?): Boolean {
        if (!ANOTHER.matches(normalizeFollowUp(input))) return false
        if (!Jokes.contains(previousAssistant)) return false
        val previousWasJokeRequest = previousUser?.let {
            JOKE_Q.containsMatchIn(it) || ANOTHER.matches(normalizeFollowUp(it))
        } == true
        return previousWasJokeRequest
    }

    fun isDepthFeedback(input: String): Boolean =
        input.trim().length <= 100 && DEPTH_FEEDBACK.containsMatchIn(input)

    /**
     * Expand a short relationship follow-up only when a recent, explicit
     * person lookup supplies its referent. No guess is made from a surname
     * or from arbitrary conversation text.
     */
    fun personFactLookup(input: String, previousUserMessages: List<String>): String? {
        val match = PERSON_RELATION.matchEntire(input.trim()) ?: return null
        val subject = previousUserMessages.asReversed()
            .firstNotNullOfOrNull { q ->
                if (EntityMatch.isPersonLookupQuestion(q)) EntityMatch.entityPhrase(q)
                else null
            } ?: return null
        val relation = match.groupValues[1].lowercase()
        return when {
            relation.startsWith("brother") -> "who are the brothers of $subject"
            relation.startsWith("sister") -> "who are the sisters of $subject"
            relation == "parents" -> "who are the parents of $subject"
            relation == "father" -> "who is the father of $subject"
            relation == "mother" -> "who is the mother of $subject"
            relation == "wife" -> "who is the wife of $subject"
            relation == "husband" -> "who is the husband of $subject"
            relation == "children" -> "who are the children of $subject"
            relation.startsWith("son") -> "who are the sons of $subject"
            relation.startsWith("daughter") -> "who are the daughters of $subject"
            else -> null
        }
    }

    /**
     * Parse explicit online-verification commands. Pronouns such as
     * "about him" are resolved only against a recent explicit person lookup.
     */
    fun onlineLookupQuery(input: String, previousUserMessages: List<String>): String? {
        val command = input.trim()
        val prefix = ONLINE_PREFIXES.firstOrNull { p ->
            command.startsWith(p, ignoreCase = true) &&
                (command.length == p.length || command[p.length].isWhitespace() ||
                    command[p.length] == ':' || command[p.length] == ',')
        } ?: return null

        var query = command.substring(prefix.length).trim().trimStart(':', ',').trim()
        if (query.startsWith("for ", ignoreCase = true)) query = query.substring(4).trim()
        if (query.isBlank()) {
            val subject = lastPersonSubject(previousUserMessages)
            return subject?.let { "who is $it" }
                ?: previousUserMessages.lastOrNull { it.isNotBlank() }?.trim()
                ?: ""
        }

        val relationText = query.replace(Regex("(?i)^about\\s+"), "").trim()
        val relation = personFactLookup(relationText, previousUserMessages)
        if (relation != null) return relation

        val aboutPerson = Regex(
            "(?i)^(?:about\\s+)?(?:him|her|them|that\\s+person|the\\s+same\\s+person)[.!?]*$"
        ).matches(query)
        if (aboutPerson) {
            val subject = lastPersonSubject(previousUserMessages) ?: return ""
            return "who is $subject"
        }
        return query
    }

    private fun lastPersonSubject(previousUserMessages: List<String>): String? =
        previousUserMessages.asReversed().firstNotNullOfOrNull { q ->
            if (EntityMatch.isPersonLookupQuestion(q)) EntityMatch.entityPhrase(q)
            else null
        }
}
