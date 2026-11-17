package org.nova.ncie.android

/**
 * Narrow, context-bound follow-up routing.
 *
 * A short phrase such as "one more" is not an intent on its own. It becomes
 * actionable only when the previous user turn requested a joke and NOVA's
 * last answer is one of the deterministic jokes. This avoids hijacking
 * "one more" after maths, code, or ordinary conversation.
 */
internal object FollowUpIntent {
    private val ANOTHER = Regex(
        "(?i)^(?:one more|another one|another|more|again|one more please|another please|more please)[.!?\\s]*$"
    )

    fun isAnotherJoke(input: String, previousUser: String?, previousAssistant: String?): Boolean {
        if (!ANOTHER.matches(input.trim())) return false
        if (!Jokes.contains(previousAssistant)) return false
        val previousWasJokeRequest = previousUser?.let { JOKE_Q.containsMatchIn(it) } == true
        val previousWasJokeContinuation = previousUser?.let { ANOTHER.matches(it.trim()) } == true
        return previousWasJokeRequest || previousWasJokeContinuation
    }
}
