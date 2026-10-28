package org.nova.ncie

/**
 * v9.33.52 "Know which profile you are on".
 *
 * NOVA gives a small model a leaner prompt: ONE background source instead of
 * two, no exam line, and tighter injections - because a 230M-class model drowns
 * in stacked instructions. That decision was made by an inline regex inside the
 * prompt builder, which meant nobody could see it, and nothing tested it.
 *
 * It also disagreed with the reply-length cap, which looks at the FILE name
 * (230m/135m/360m/0.2b/0.3b) and does NOT count LFM 1.2B as tiny. So LFM 1.2B
 * gets the lean prompt but a full-length reply - two different answers to the
 * same question, in two places.
 *
 * This is the label rule, in one place, unit-tested, and reported by the health
 * screen so it can be SEEN rather than assumed. Note what it currently says:
 * LFM 2.5 1.2B IS treated as tiny, so on that model one background source and
 * the exam line are already switched off. Whether 1.2B should be in that bucket
 * is a fair question - this makes it visible so it can be answered with
 * evidence instead of a guess.
 *
 * Pure string logic, no Android.
 */
object ModelProfile {

    /** A size in the model's label, e.g. the "1.2" of "LFM 2.5 1.2B Instruct". */
    private val MODEL_PARAMS = Regex("(\\d+(?:\\.\\d+)?)\\s*b\\b")

    /** The parameter count in billions, or null when the label does not say. */
    fun billions(label: String): Double? =
        MODEL_PARAMS.find(label.lowercase())?.groupValues?.get(1)?.toDoubleOrNull()

    /**
     * Is this model small enough to get the lean prompt? Unchanged behaviour
     * from v8.3.1: anything at or under 2B, which is why 1.5B, 1.7B and 2B are
     * covered - and 1.2B with them.
     */
    fun isTiny(label: String): Boolean = (billions(label) ?: return false) <= 2.0

    /**
     * v9.33.79 "GGUF card": the same rule, but the model's own size label from
     * its GGUF header is consulted when the LABEL does not state a size. A
     * model the user renamed (or copied in by hand) carries no size in its
     * name, so a 0.6B used to be treated as "unknown, therefore full profile"
     * - two background sources and the exam line for a model that drowns in
     * them. The label always wins when it does say a size: nothing changes for
     * a catalog model.
     */
    fun isTiny(label: String, sizeLabel: String?): Boolean {
        billions(label)?.let { return it <= 2.0 }
        if (!sizeLabel.isNullOrBlank()) billions(sizeLabel)?.let { return it <= 2.0 }
        return false
    }

    /** One line for the health screen. */
    fun describe(label: String): String {
        val b = billions(label)
        val size = if (b == null) "size unknown" else "${b}B"
        return if (isTiny(label))
            "$size - lean profile: one background source, no exam line"
        else
            "$size - full profile: notes and wiki, exam line, wider context"
    }
}
