package org.nova.ncie.android

/**
 * v9.33.18 "Make me laugh": a small, deterministic set of jokes.
 *
 * "make me laugh" used to go to the model, which deflected ("humour often
 * comes from unexpected situations...") instead of telling a joke. A joke
 * needs no model and cannot be got wrong, so this answers it directly.
 * Pure data + one pure function - unit-tested in
 * android/app/src/test/java/org/nova/ncie/android.
 */
internal object Jokes {

    private val ALL = listOf(
        "Why did the student eat their homework? The teacher said it was a piece of cake.",
        "I told my computer I needed a break. It said: no problem, I'll go to sleep.",
        "Why don't scientists trust atoms? Because they make up everything.",
        "Parallel lines have so much in common. Shame they'll never meet.",
        "Why did the maths book look so sad? It had too many problems.",
        "I'd tell you a joke about the internet, but you might not get it.",
        "Why was the equal sign so humble? It knew it wasn't less than or greater than anyone.",
        "I wanted to be a doctor, but I didn't have the patients.",
    )

    /** The joke at [seed] (any int, wrapped). Deterministic and testable. */
    fun at(seed: Int): String = ALL[Math.floorMod(seed, ALL.size)]

    fun count(): Int = ALL.size
}
