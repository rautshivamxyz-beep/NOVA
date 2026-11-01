package org.nova.ncie.skill

/**
 * v0.9.4 (stage 3): skills as DATA. A skill is a trigger pattern and an
 * instruction - knowledge about HOW to answer, not what to say. The host
 * keeps the file (the NOVA app: skills.txt beside its other data files),
 * the kernel only parses and matches, so a new skill lands without a
 * single line of code changing.
 *
 * The file format, one section per skill:
 *
 *     [skill]
 *     name=Quiz me
 *     trigger=(?i)\bquiz me on\b|\btest me on\b
 *     instruction=Ask one question at a time about {q} from the notes below...
 *
 * - sections start with a line that is exactly "[skill]"
 * - "\n" inside an instruction becomes a real newline
 * - "{q}" in an instruction is replaced by the user's text at render time
 * - unknown keys are ignored (forward compatible), malformed sections
 *   (missing a key, or a trigger that does not compile) are skipped,
 *   never fatal
 */
class Skill(val name: String, val trigger: Regex, val instruction: String) {

    /** The instruction with {q} replaced by the user's text. */
    fun render(text: String): String = instruction.replace("{q}", text)
}

object SkillStore {

    /** Parse a skills file. Malformed sections are skipped, never fatal. */
    fun parse(text: String): List<Skill> {
        val out = ArrayList<Skill>()
        var name: String? = null
        var trigger: String? = null
        var instruction: String? = null
        fun flush() {
            val n = name
            val t = trigger
            val i = instruction
            if (n != null && !n.isBlank() && !t.isNullOrBlank() && !i.isNullOrBlank()) {
                try {
                    out.add(Skill(n.trim(), Regex(t.trim()), i))
                } catch (_: Exception) { /* bad trigger - skip the section */ }
            }
            name = null; trigger = null; instruction = null
        }
        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line == "[skill]") { flush(); continue }
            if (line.startsWith("name=") && name == null) name = line.substring(5)
            else if (line.startsWith("trigger=") && trigger == null) trigger = line.substring(8)
            else if (line.startsWith("instruction=") && instruction == null)
                instruction = line.substring(12).replace("\\n", "\n")
        }
        flush()
        return out
    }

    /** The first skill whose trigger matches the text, or null. */
    fun best(skills: List<Skill>, text: String): Skill? =
        skills.firstOrNull { it.trigger.containsMatchIn(text) }
}
