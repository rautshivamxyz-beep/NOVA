package org.nova.ncie.android

import org.nova.NovaLog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.nova.ChatStore
import org.nova.MainActivity
import org.nova.Msg
import org.nova.Role

/**
 * v8.5.0: the profile tool. "What do you know about me" was the 1.5B
 * model's worst answer: no grounded facts in context, so it invented a
 * personality from Wikipedia background noise ("you are interested in
 * community spaces and resource management"). This question never
 * reaches the model anymore - it is answered DETERMINISTICALLY from the
 * graded memory: what the user asked NOVA to remember, plus the study
 * answers that passed the quality gate. If it is not on that list, the
 * answer says so.
 */
private val PROFILE_Q = Regex(
    "(?i)\\bwhat\\s+(?:do|did|have)\\s+you\\s+(?:know|remember|learned)\\b" +
        "|\\bwhat\\s+you\\s+know\\s+(?:about\\s+me|so\\s+far)\\b" +
        "|\\bwho\\s+am\\s+i\\b|\\bmy\\s+profile\\b" +
        "|\\btell\\s+me\\s+(?:about\\s+(?:myself|me)|who\\s+i\\s+am)\\b")

fun MainActivity.answerProfile(text: String): Boolean {
    // OCR'd or long text is a study question that merely contains the
    // phrase - the profile question is always short and typed
    if (text.length > 60 || !PROFILE_Q.containsMatchIn(text)) return false
    val act = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    NcieLearn.memorySnapshot { facts, _ ->
        val told = settings.memory.trim().lines().map { it.trim() }.filter { it.isNotBlank() }
        // v9.33.38 "Earned, not dumped": this listed EVERY one-off answer the
        // quality gate let through, so "who am i" answered with a trivia list -
        // jokes, and half-sentences cut mid-word ("It can", "maintaining a b").
        // A fact belongs under "what NOVA knows about you" only once it has
        // earned confidence: confirmed more than once, or scored above the
        // neutral 0.5 floor. Truncation now happens at a word boundary too.
        val graded = facts.filter { it.interactions >= 2 || it.score >= 0.6 }
        val sb = StringBuilder("(from NOVA's memory - verified facts only, no guessing)\n\n")
        if (told.isEmpty() && graded.isEmpty()) {
            sb.append("Honestly? Nothing yet. I only keep what you ask me to " +
                "remember and the answers that passed my quality gate.\n\n" +
                "Tell me something with 'remember that ...' and it will stick.")
        } else {
            if (told.isNotEmpty()) {
                sb.append("What you've told me:\n")
                for (l in told.take(10)) sb.append("- ").append(l).append('\n')
            }
            if (graded.isNotEmpty()) {
                if (told.isNotEmpty()) sb.append('\n')
                sb.append("From our study chats (answers confirmed more than once):\n")
                for (f in graded.take(6)) {
                    sb.append("- ").append(clip(f.question.trim(), 90))
                        .append(" -> ").append(clip(f.answer.trim(), 110)).append('\n')
                }
            }
            sb.append("\nNothing else. If it is not on this list, I do not know it.")
        }
        val reply = Msg(Role.ASSISTANT, sb.toString().trim())
        currentChat.messages.add(reply); adapter.add(reply); scrollToEnd()
        act.scope.launch(Dispatchers.IO) {
            try { ChatStore.save(act, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieProfile", e) }
        }
    }
    return true
}

/** v9.33.38: cut at a word boundary, so a listed fact never ends mid-word. */
private fun clip(s: String, max: Int): String {
    if (s.length <= max) return s
    val cut = s.lastIndexOf(' ', max)
    val head = if (cut > max / 2) s.substring(0, cut) else s.substring(0, max)
    return head.trimEnd() + "…"
}
