package org.nova.ncie.android

import org.nova.ncie.ModelProfile
import org.nova.ncie.Tokens
import org.nova.NovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nova.CHIP_PROMPTS
import org.nova.ChatStore
import org.nova.docStop
import org.nova.Exams
import org.nova.FOLLOW_UP_Q
import org.nova.Knowledge
import org.nova.MainActivity
import org.nova.Msg
import org.nova.NovaEngine
import org.nova.NovaListener
import org.nova.Role
import org.nova.SMALLTALK_REGEX
import org.nova.WikiCore
import org.nova.ncie.verify.Coverage
import java.io.File


// v9.33.58 (improvement pass): extracted from NcieChat.kt - the question-routing
// extensions and their helpers. Top-level `private` became `internal`.

// v7.8.1: short self-introductions - "im Shivam", "hi i'm Shivam",
// "my name is Shivam Raut", "call me Shiv". Longer first-person
// statements ("i am confused about photosynthesis") do NOT match and
// keep taking the normal answer path.
internal val SELF_INTRO_REGEX = Regex(
    "(?i)^[\\s']*(?:(?:hi+|hello+|hey+|yo|namaste)[,!.\\s']+)*" +
        "(?:i'?m|i am|my name'?s|my name is|call me)\\s+" +
        "([a-z][a-z'-]*(?:\\s+[a-z][a-z'-]*){0,1})\\s*[.!\\s]*$"
)

/** v7.8.1: an introduction is worth keeping - offer to store the user's
 *  name in the persistent memory, like "remember that ..." but without
 *  needing the keyword. */
internal fun MainActivity.maybeRememberName(name: String) {
    if (name.length < 2 || name.split(" ").size > 2) return
    val first = name.substringBefore(" ")
    if (first.lowercase() in setOf("not", "no", "never", "just", "still", "also",
            "always", "already", "so", "too", "very", "really", "feeling", "trying")) return
    if (name.lowercase() in settings.memory.lowercase()) return
    android.app.AlertDialog.Builder(this)
        .setTitle("Add to NOVA's memory?")
        .setMessage("The user's name is $name")
        .setPositiveButton("Add") { _, _ ->
            settings.memory = if (settings.memory.isBlank()) "The user's name is $name"
            else settings.memory.trimEnd() + "\n- The user's name is $name"
            toast("Added to memory")
        }
        .setNegativeButton("No", null)
        .show()
}

// v8.5.3: self-description facts - class, city, school - offered like
// the name: an explicit dialog, stored only on Add, never re-offered
// once present in memory.
internal val FACT_CLASS = Regex("(?i)\\bclass\\s+(\\d{1,2})\\b")
internal val FACT_CITY = Regex("(?i)\\b(?:lives?|living)\\s+in\\s+([a-z]{2,}(?:\\s+[a-z]{2,}){0,2})")
internal val FACT_SCHOOL = Regex("(?i)\\b(?:stud(?:y|ies|ying)|reads?)\\s+(?:in|at)\\s+([a-z0-9' ]{4,60}?(?:school|college|academy|vidyalaya|institute))")
// v9.29.1: academic results - "my marks are 92", "I got 85% in maths",
// "I scored 450 in NEET". Previously a mark told in one chat lived only
// in that chat's history and was gone in a new one. Same opt-in dialog
// as class/city/school, so nothing is stored without the user tapping
// Add. Groups: 1 = score, 2 = unit, 3 = subject.
internal val FACT_MARKS = Regex(
    "(?i)\\b(?:my\\s+)?(?:marks?|score|scores|percentage)\\s*" +
        "(?:are|is|were|was|:)?\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*" +
        "((?:%|percent|marks?)?)" +
        "(?:\\s*(?:in|for|on)\\s+([a-z][a-z ]{2,30}))?")
internal val FACT_SCORED = Regex(
    "(?i)\\b(?:i\\s+)?(?:scored|got|secured|obtained|achieved)\\s+" +
        "(\\d{1,3}(?:\\.\\d+)?)\\s*" +
        "((?:%|percent|marks?|/\\s*\\d{1,3}|out\\s+of\\s+\\d{1,3})?)" +
        "(?:\\s*(?:in|for|on)\\s+([a-z][a-z ]{2,30}))?")

internal fun MainActivity.maybeRememberFacts(text: String) {
    // only self-descriptions, never third-person study text
    if (text.length > 220) return
    if (!Regex("(?i)(?:^|\\s)(?:i|im|i'm|i am|my|me)(?:\\s|'|,|\\.|$)").containsMatchIn(text)) return
    val facts = ArrayList<String>()
    FACT_CLASS.find(text)?.let { m ->
        val f = "The user is in class " + m.groupValues[1]
        if (!settings.memory.contains(f, ignoreCase = true)) facts.add(f)
    }
    FACT_CITY.find(text)?.let { m ->
        val words = m.groupValues[1].trim().split(" ")
        val stop = setOf("and", "i", "im", "am", "studies", "studying", "studing",
            "reads", "reading", "want", "wants", "with", "from", "my", "to", "who")
        val cut = words.indexOfFirst { it in stop }
        val city = (if (cut >= 0) words.subList(0, cut) else words)
            .joinToString(" ").trim()
        if (city.length > 2) {
            val f = "The user lives in " + city.replaceFirstChar { it.uppercase() }
            if (!settings.memory.contains(f, ignoreCase = true)) facts.add(f)
        }
    }
    FACT_SCHOOL.find(text)?.let { m ->
        val school = m.groupValues[1].trim().trim(',', '.')
        if (school.length > 5) {
            val f = "The user studies at " + school.replaceFirstChar { it.uppercase() }
            if (!settings.memory.contains(f, ignoreCase = true)) facts.add(f)
        }
    }
    // v9.29.1: a mark stated in this chat is offered to memory, so the next
    // chat (which has no history) can still answer about it.
    FACT_MARKS.find(text)?.let { m ->
        val score = m.groupValues[1].trim()
        val subject = m.groupValues.getOrNull(3)?.trim()?.trim(',', '.')?.trim() ?: ""
        val f = if (subject.isNotEmpty()) "The user scored $score in $subject"
                else "The user scored $score"
        if (!settings.memory.contains(f, ignoreCase = true)) facts.add(f)
    }
    FACT_SCORED.find(text)?.let { m ->
        val score = m.groupValues[1].trim()
        val unit = m.groupValues.getOrNull(2)?.trim() ?: ""
        val subject = m.groupValues.getOrNull(3)?.trim()?.trim(',', '.')?.trim() ?: ""
        // a bare number is not a mark - require a unit (%, marks, /N) or a
        // subject ("in maths"), so "I got 5 apples" is never stored
        if (unit.isEmpty() && subject.isEmpty()) return@let
        val f = if (subject.isNotEmpty()) "The user scored $score in $subject"
                else "The user scored $score$unit"
        if (!settings.memory.contains(f, ignoreCase = true)) facts.add(f)
    }
    if (facts.isEmpty()) return
    android.app.AlertDialog.Builder(this)
        .setTitle("Add to NOVA's memory?")
        .setMessage(facts.joinToString("\n"))
        .setPositiveButton("Add") { _, _ ->
            settings.memory = if (settings.memory.isBlank()) facts.joinToString("\n")
            else settings.memory.trimEnd() + "\n" + facts.joinToString("\n")
            toast("Added to memory")
        }
        .setNegativeButton("No", null)
        .show()
}

// v8.8.0: "what did I miss" / "any notifications" / "read my notifications"
// - a summary of the last 24h from NovaListener's local log, grouped
// by app. Deterministic like PROFILE_Q: no model call, no network,
// nothing leaves the phone.
internal val MISSED_Q = Regex(
    "(?i)\\bwhat\\s+did\\s+i\\s+miss\\b|\\bany\\s+notifications\\b" +
        "|\\b(?:read|check)\\s+my\\s+notifications\\b|\\bnotification\\s+summary\\b")

internal fun MainActivity.answerMissed(text: String): Boolean {
    // OCR'd or long text is a study question that merely contains the
    // phrase - the missed question is always short and typed
    if (text.length > 80 || !MISSED_Q.containsMatchIn(text)) return false
    val activity = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    val body: String
    if (!NovaListener.isEnabled(this)) {
        body = "Notification access is off - open the drawer and tap " +
            "Notifications to enable it. Nothing ever leaves this phone."
    } else {
        val lines = NovaListener.readLog(this)
        if (lines.isEmpty()) {
            body = "Nothing recorded yet — notifications get logged from " +
                "the moment you enable access."
        } else {
            val now = System.currentTimeMillis()
            val cutoff = now - 24L * 60 * 60 * 1000
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            // one pass over the log: per app, the count and the most
            // recent entry (newest line wins - the log is oldest first)
            val countBy = HashMap<String, Int>()
            val lastTBy = HashMap<String, Long>()
            val lastBy = HashMap<String, String>()
            var total = 0
            for (l in lines) {
                val p = l.split('\t', limit = 4)
                if (p.size < 3) continue
                val t = p[0].toLongOrNull() ?: continue
                if (t < cutoff) continue
                val app = p[1].substringAfterLast('.')
                val title = p[2]
                val txt = if (p.size > 3) p[3] else ""
                total++
                countBy[app] = (countBy[app] ?: 0) + 1
                lastTBy[app] = t
                lastBy[app] = fmt.format(java.util.Date(t)) + "  " + title +
                    (if (txt.isBlank()) "" else " - " + txt)
            }
            if (total == 0) {
                body = "No notifications in the last 24 hours - the log " +
                    "has older entries only."
            } else {
                // deterministic order: busiest-activity app first (most
                // recent entry), ties broken alphabetically
                val sb = StringBuilder("```\nWhat you missed (last 24 hours):\n\n")
                for (app in countBy.keys.sortedWith(
                        compareByDescending<String> { lastTBy[it] ?: 0L }.thenBy { it })) {
                    sb.append(app).append(": ").append(countBy[app]).append('\n')
                    sb.append("  ").append(lastBy[app]).append('\n')
                }
                sb.append("\nTotal: ").append(total).append(" notifications from ")
                    .append(countBy.size).append(" apps")
                sb.append("\n```")
                body = sb.toString()
            }
        }
    }
    val reply = Msg(Role.ASSISTANT, body)
    currentChat.messages.add(reply); adapter.add(reply); scrollToEnd()
    activity.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
    return true
}

// v9.3.0 "Audit Fixes I": notification log privacy commands - "clear my
// notifications" / "pause notifications" / "resume notifications",
// whole-message and short like the MISSED_Q commands above. All three
// are deterministic: a file wipe, a flag create, a flag delete - no
// model, no network, nothing leaves the phone. While paused, NovaListener
// writes nothing at all; the 7-day purge runs inside append.
internal val NOTIF_CLEAR_Q = Regex(
    "(?i)^\\s*clear\\s+my\\s+notifications?\\s*[.!?]*\\s*$")
internal val NOTIF_PAUSE_Q = Regex(
    "(?i)^\\s*pause\\s+notifications?\\s*[.!?]*\\s*$")
internal val NOTIF_RESUME_Q = Regex(
    "(?i)^\\s*resume\\s+notifications?\\s*[.!?]*\\s*$")

internal fun MainActivity.answerNotifCmd(text: String): Boolean {
    // long/OCR'd text is a study question that merely contains the
    // phrase - these commands are always short and typed
    if (text.length > 80) return false
    val clear = NOTIF_CLEAR_Q.containsMatchIn(text)
    val pause = NOTIF_PAUSE_Q.containsMatchIn(text)
    val resume = NOTIF_RESUME_Q.containsMatchIn(text)
    if (!clear && !pause && !resume) return false
    val activity = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    val body = when {
        clear -> { NovaListener.clearLog(this); "Notification log cleared." }
        pause -> { NovaListener.pause(this); "Notification logging paused." }
        else -> { NovaListener.resume(this); "Notification logging resumed." }
    }
    val reply = Msg(Role.ASSISTANT, body)
    currentChat.messages.add(reply); adapter.add(reply); scrollToEnd()
    activity.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
    return true
}

// v9.10.0 "Revision Planner": the exam commands. EXAM_SET is the open
// capture (length-gated in the routing above); the rest are anchored
// short commands like the tutor/routine ones, so OCR'd chapter pastes
// never fall into them.
internal val EXAM_SET = Regex(
    "(?i)\\b(?:my\\s+exam\\s+is\\s+on|set\\s+exam\\s+date(?:\\s+(?:to|for))?|my\\s+boards\\s+start\\s+on|boards\\s+on)\\s+(.{2,40})")
internal val EXAM_PLAN = Regex(
    "(?i)^\\s*plan\\s+my\\s+revision\\s*[.!?]*\\s*$")
internal val EXAM_SHOW = Regex(
    "(?i)^\\s*show\\s+(?:my\\s+)?revision\\s+plan\\s*[.!?]*\\s*$")
internal val EXAM_CLEAR = Regex(
    "(?i)^\\s*(?:clear\\s+exam\\s+date|exam\\s+done|exam\\s+is\\s+over)\\s*[.!?]*\\s*$")
internal val EXAM_COUNT = Regex(
    "(?i)^\\s*(?:exam\\s+countdown|days\\s+to\\s+my\\s+exam)\\s*[.!?]*\\s*$")

// v9.11.0 "Inference Quality": the tuning commands. TUNE_SET_Q is the
// open capture pair (knob, value); the show/reset ones are anchored
// short commands like the exam ones, so OCR'd chapter pastes never
// fall into them.
internal val TUNE_SET_Q = Regex(
    "(?i)^\\s*set\\s+(temperature|temp|top[\\s-]?p|top[\\s-]?k|min[\\s-]?p|repeat(?:[\\s-]?penalty)?)\\s+([0-9]*\\.?\\d+)\\s*[.!?]*\\s*$")
internal val TUNE_SHOW_Q = Regex("(?i)^\\s*model\\s+settings\\s*[.!?]*\\s*$")
internal val TUNE_RESET_Q = Regex("(?i)^\\s*reset\\s+model\\s+settings\\s*[.!?]*\\s*$")

// v8.9.0: Tutor Mode's entry phrases. Anchored short commands (study,
// weak areas, flashcards) so OCR'd chapter pastes never fall into them;
// quiz-me-on keeps the open topic capture but is length-gated in the
// routing above.
internal val TUTOR_QUIZ = Regex("(?i)\\b(?:quiz|test)\\s+me\\s+on\\s+(.{2,80})")
// v9.16.4: bare "quiz me" / "test me" - the command with no topic tail.
// Routes to the tutor, which quizzes on the current document / notes.
internal val TUTOR_QUIZ_BARE = Regex("(?i)\\s*(?:quiz|test)\\s+me\\s*[.!?]*\\s*")
internal val TUTOR_STUDY = Regex("(?i)^\\s*study(?:\\s+my\\s+weak\\s+areas)?\\s*[.!?]*\\s*$")
internal val TUTOR_WEAK = Regex(
    "(?i)^\\s*(?:my\\s+weak\\s+areas|what\\s+am\\s+i\\s+weak\\s+in|weak\\s+areas)\\s*[.!?]*\\s*$")
internal val FLASH_ADD = Regex("(?i)^\\s*add\\s+flashcards?\\b\\s*(.*)$")
internal val FLASH_LIST = Regex("(?i)^\\s*my\\s+flashcards\\s*[.!?]*\\s*$")
internal val FLASH_QUIZ = Regex("(?i)^\\s*(?:quiz\\s+my\\s+flashcards|flashcards)\\s*[.!?]*\\s*$")

// v9.11.0 "Inference Quality": the tuning command intercept - "set
// temperature 0.7" (variants: temp, top p / top-p, top k / topk, min p,
// repeat penalty), "model settings" (the current model's effective
// profile, monospace, with each value's origin) and "reset model
// settings" (delete the override file, back to family defaults).
// Invalid values get an honest usage reply. NcieTune owns the logic.
internal fun MainActivity.answerTune(text: String): Boolean {
    // long/OCR'd text is a study question that merely contains the
    // phrase - these commands are always short and typed
    if (text.length > 80) return false
    val m = TUNE_SET_Q.find(text)
    val show = TUNE_SHOW_Q.containsMatchIn(text)
    val reset = TUNE_RESET_Q.containsMatchIn(text)
    if (m == null && !show && !reset) return false
    val activity = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    val body: String = when {
        m != null -> {
            val knob = m.groupValues[1].lowercase().replace("-", " ")
            val key = when (knob) {
                "temperature", "temp" -> "temperature"
                "top p", "topp" -> "top_p"
                "top k", "topk" -> "top_k"
                "min p", "minp" -> "min_p"
                else -> "repeat_penalty"
            }
            NcieTune.setCommand(activity, key, m.groupValues[2])
        }
        reset -> NcieTune.resetCommand(activity)
        else -> NcieTune.showCommand(activity)
    }
    val reply = Msg(Role.ASSISTANT, body)
    currentChat.messages.add(reply); adapter.add(reply); scrollToEnd()
    activity.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
    return true
}

// v8.8.0: "any messages from X" / "did X message me" / "anything from
// X" - a deterministic search of NovaListener's log for X (the one to
// four words after the pattern) in every title and text.
internal val FROM_Q = Regex(
    "(?i)\\bany\\s+messages?\\s+from\\s+([a-z0-9']+(?:\\s+[a-z0-9']+){0,3})" +
        "|\\bdid\\s+([a-z0-9']+(?:\\s+[a-z0-9']+){0,3})\\s+message\\s+me\\b" +
        "|\\bmessage\\s+from\\s+([a-z0-9']+(?:\\s+[a-z0-9']+){0,3})" +
        "|\\banything\\s+from\\s+([a-z0-9']+(?:\\s+[a-z0-9']+){0,3})")

internal fun MainActivity.answerFrom(text: String): Boolean {
    val m = FROM_Q.find(text) ?: return false
    var who = ""
    for (g in 1..4) if (m.groupValues[g].isNotBlank()) {
        who = m.groupValues[g].trim(); break
    }
    if (who.isEmpty()) return false
    val activity = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    val body: String
    if (!NovaListener.isEnabled(this)) {
        body = "Notification access is off - open the drawer and tap " +
            "Notifications to enable it. Nothing ever leaves this phone."
    } else {
        val needle = who.lowercase()
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val hits = ArrayList<String>()
        for (l in NovaListener.readLog(this)) {
            val p = l.split('\t', limit = 4)
            if (p.size < 3) continue
            val title = p[2]
            val txt = if (p.size > 3) p[3] else ""
            if (!title.lowercase().contains(needle) &&
                !txt.lowercase().contains(needle)) continue
            val t = p[0].toLongOrNull() ?: continue
            val time = if (now - t < 24L * 60 * 60 * 1000) fmt.format(java.util.Date(t))
                else java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US)
                    .format(java.util.Date(t))
            hits.add(time + "  " + p[1].substringAfterLast('.') + " - " + title +
                (if (txt.isBlank()) "" else " — " + txt))
        }
        if (hits.isEmpty()) {
            body = "No notifications from $who in the log."
        } else {
            // the log is oldest first - take the latest 3, newest first
            val sb = StringBuilder("```\nMessages from $who:\n\n")
            for (h in hits.takeLast(3).asReversed()) sb.append(h).append('\n')
            sb.append("```")
            body = sb.toString()
        }
    }
    val reply = Msg(Role.ASSISTANT, body)
    currentChat.messages.add(reply); adapter.add(reply); scrollToEnd()
    activity.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
    return true
}

// v9.1.0 "Automation I": "good morning" / "morning briefing" / "briefing"
// / "my briefing" - the deterministic morning briefing from local state
// (date, battery, notifications since midnight, weak areas due,
// flashcards). Like MISSED_Q: no model call, no network, nothing leaves
// the phone, and it works with no model loaded at all.
internal val BRIEFING_Q = Regex(
    "(?i)^\\s*(?:good\\s+morning|morning\\s+briefing|my\\s+briefing|briefing)\\s*[.!?]*\\s*$")

internal fun MainActivity.answerBriefing(text: String): Boolean {
    if (text.length > 80 || !BRIEFING_Q.containsMatchIn(text)) return false
    val activity = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    val briefText = NcieRoutines.morningBriefing(this)
    val routReply = Msg(Role.ASSISTANT, briefText)
    currentChat.messages.add(routReply); adapter.add(routReply); scrollToEnd()
    // v9.16.15 "Voice briefing": speak it aloud when TTS is ready.
    speakNow(briefText)
    activity.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
    return true
}

// v9.1.0: "start study" / "start studying" / "study time" - a 45-minute
// timer through the SAME clock-app path the phone commands use
// (MainActivity.startClockTimer, the ACTION_SET_TIMER route).
internal val STUDY_Q = Regex(
    "(?i)^\\s*(?:start\\s+study(?:ing)?|study\\s+time)\\s*[.!?]*\\s*$")

internal fun MainActivity.answerStudy(text: String): Boolean {
    if (text.length > 80 || !STUDY_Q.containsMatchIn(text)) return false
    val activity = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    val routReply = Msg(Role.ASSISTANT, NcieRoutines.startStudy(this, this))
    currentChat.messages.add(routReply); adapter.add(routReply); scrollToEnd()
    activity.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
    return true
}

// v9.2.0 "Rolling Chat Summary": "summarize our conversation" / "what
// were we talking about" / "conversation summary" - the rolling summary
// read straight from filesDir/chat_summary.txt, deterministically.
// Like BRIEFING_Q: no model call, no network, works with no model
// loaded at all.
internal val SUMMARY_Q = Regex(
    "(?i)^\\s*(?:summarize|summarise)\\s+our\\s+conversation\\s*[.!?]*\\s*$" +
        "|\\bwhat\\s+were\\s+we\\s+talking\\s+about\\b" +
        "|\\bconversation\\s+summary\\b")

internal fun MainActivity.answerSummary(text: String): Boolean {
    // long/OCR'd text is a study question that merely contains the
    // phrase - these commands are always short and typed
    if (text.length > 80 || !SUMMARY_Q.containsMatchIn(text)) return false
    val activity = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    val sumBody = NcieSummary.read(this, currentChat.id)
    val sumReply = Msg(Role.ASSISTANT,
        if (sumBody != null) "Here's what I remember of our conversation so far:\n\n$sumBody"
        else "No summary yet — it builds automatically as we talk.")
    currentChat.messages.add(sumReply); adapter.add(sumReply); scrollToEnd()
    activity.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
    return true
}

// v9.2.0: "forget our conversation" / "forget what we talked about" -
// deletes filesDir/chat_summary.txt and its counter and clears the
// history the way the app's own new-conversation action does. Like
// BRIEFING_Q: no model call, no network.
internal val FORGET_Q = Regex(
    "(?i)^\\s*forget\\s+(?:our\\s+conversation|what\\s+we\\s+talked\\s+about)\\s*[.!?]*\\s*$")

internal fun MainActivity.answerForget(text: String): Boolean {
    if (text.length > 80 || !FORGET_Q.containsMatchIn(text)) return false
    val activity = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    NcieSummary.clear(this, currentChat.id)
    currentChat.messages.clear()
    adapter.clear()
    needsContextCarry = false
    compactSummary = null
    if (NovaEngine.isModelLoaded)
        NovaEngine.resetConversationAsync(this, settings.systemPrompt)
    val forgReply = Msg(Role.ASSISTANT, "Done — conversation cleared.")
    currentChat.messages.add(forgReply); adapter.add(forgReply); scrollToEnd()
    activity.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
    return true
}

// v9.5.0 "Document Grounding": "from my notes: <question>" (also "from
// my documents", with or without the colon) - the remainder is the
// question, answered STRICTLY from the user's own material. Retrieval
// is deterministic (NcieGround.retrieve: ~1500-char chunks, keyword
// overlap, top 2 per source, top 4 overall); the model only words the
// final answer from those chunks (NovaEngineAdapter.generate on
// Dispatchers.IO, the NcieTutor pattern), and the reply ends with a
// monospace source list. No network, no new dependency.
internal val GROUND_Q = Regex(
    "(?i)^\\s*from\\s+my\\s+(?:notes?|documents?)\\b\\s*:?\\s*(.*)$")

internal fun postGroundReply(act: MainActivity, body: String) {
    val groundMsg = Msg(Role.ASSISTANT, body)
    act.currentChat.messages.add(groundMsg); act.adapter.add(groundMsg); act.scrollToEnd()
    act.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
}

internal fun MainActivity.answerGrounded(text: String): Boolean {
    val m = GROUND_Q.find(text) ?: return false
    val question = m.groupValues[1].trim()
    if (question.length < 2) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    // no material at all - honest, never a guess
    if (!NcieKnowledge.hasDocs(this) && !WikiCore.isReady(this)) {
        postGroundReply(activity,
            "You have no documents yet — paste a chapter into Knowledge or fetch one online.")
        return true
    }
    if (compacting) {
        postGroundReply(activity, "Compressing older messages — one moment")
        return true
    }
    if (!ensureModelReady()) return true
    scope.launch(Dispatchers.IO) {
        val chunks = NcieGround.retrieve(activity, question)
        // zero keyword-matching chunks - nothing in the material is
        // about this question, so say so instead of guessing
        if (chunks.isEmpty()) {
            withContext(Dispatchers.Main) {
                postGroundReply(activity, "I couldn't find anything about that in your " +
                    "documents. Try different words, or fetch it online.")
            }
            return@launch
        }
        // v9.12.1 "Context Diet": signal the generation so the embedder's
        // background index workers (spawned by retrieve above) stand down
        // while the LLM runs - the same static flag MainActivity mirrors
        // at its generation sites.
        NcieChat.generating = true
        val groundReply = try {
            NovaEngineAdapter.generate(
                NcieGround.groundPrompt(question, chunks), activity.settings.predictLength)
        } finally { NcieChat.generating = false }
        // v9.6.0 "Engine Pack": the truth check - deterministic answer-
        // vs-source keyword verification. A grounded answer whose content
        // words do not overlap the retrieved chunks is flagged below,
        // never blocked.
        val verified = NcieGround.truthCheck(groundReply, chunks)
        // v9.6.0: the Knowledge Graph - the other sources sharing at
        // least 3 key terms with the best-matching source.
        val relatedNames = try { NcieGround.related(activity, chunks[0].first) }
            catch (e: Exception) { emptyList() }
        // monospace source list: "<name (chunk N)> ..." - N counts the
        // chunks this answer used from each source
        val seen = HashMap<String, Int>()
        val src = StringBuilder("Sources:")
        for (c in chunks) {
            val n = (seen[c.first] ?: 0) + 1
            seen[c.first] = n
            src.append(" ").append(c.first).append(" (chunk ").append(n).append(")")
        }
        withContext(Dispatchers.Main) {
            var groundBody = groundReply + "\n\n```\n" + src + "\n```"
            if (relatedNames.isNotEmpty())
                groundBody += "\nRelated: " + relatedNames.joinToString(", ")
            if (!verified)
                groundBody += "\n\nWarning: this answer may not have come from your notes — double-check."
            postGroundReply(activity, groundBody)
        }
    }
    return true
}

// v9.5.0: "what documents do i have" / "my documents" / "list my
// documents" - the deterministic inventory of the user's own material
// (Knowledge documents + offline wiki articles), monospace with sizes.
// Like DOCS listing in Knowledge: no model call, no network, works with
// no model loaded at all.
internal val DOCS_Q = Regex(
    "(?i)^\\s*(?:what\\s+documents\\s+do\\s+i\\s+have|my\\s+documents|" +
        "list\\s+my\\s+documents)\\s*[.!?]*\\s*$")

/** v9.32.8: "health" / "what's not working" - the setup checklist in the chat. */
internal val HEALTH_Q = Regex(
    "(?i)^\\s*(health|nova health|what(?:'s| is) not working|what is broken|" +
        "fix nova|nova setup|setup nova|diagnose)\\b")

/** v9.33.3: "proxy" / "test proxy" - is the SOCKS proxy actually carrying traffic? */
internal val PROXY_Q = Regex(
    "(?i)^\\s*(proxy|test proxy|check proxy|is the proxy working|socks)\\s*$")

internal fun MainActivity.answerProxy(text: String): Boolean {
    if (!PROXY_Q.containsMatchIn(text)) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val body = org.nova.NovaNet.testProxy(activity)
        withContext(Dispatchers.Main) { postGroundReply(activity, body) }
    }
    return true
}

internal fun MainActivity.answerHealth(text: String): Boolean {
    if (text.length > 60 || !HEALTH_Q.containsMatchIn(text)) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val body = org.nova.NovaHealth.report(activity)
        withContext(Dispatchers.Main) { postGroundReply(activity, body) }
    }
    return true
}

internal fun MainActivity.answerDocsList(text: String): Boolean {
    // long/OCR'd text is a study question that merely contains the
    // phrase - this command is always short and typed
    if (text.length > 80 || !DOCS_Q.containsMatchIn(text)) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val body = groundDocsBody(activity)
        withContext(Dispatchers.Main) { postGroundReply(activity, body) }
    }
    return true
}

/** The monospace document inventory: Knowledge documents first, then
 *  wiki articles, each with its size in characters. */
internal fun groundDocsBody(ctx: android.content.Context): String {
    val docs = NcieKnowledge.docs(ctx)
    val wiki = NcieGround.wikiNames(ctx)
    if (docs.isEmpty() && wiki.isEmpty()) return "No documents yet."
    val sb = StringBuilder("```\nYour documents:\n")
    if (docs.isNotEmpty()) {
        sb.append("\nKnowledge:\n")
        for (d in docs) sb.append("  ").append(d.first).append(" — ")
            .append(NcieKnowledge.docText(ctx, d.first).length).append(" chars\n")
    }
    if (wiki.isNotEmpty()) {
        sb.append("\nWiki:\n")
        try { WikiCore.warmUp(ctx) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
        for (t in wiki) {
            val len = try { WikiCore.articleText(ctx, t)?.length ?: 0 } catch (e: Exception) { 0 }
            sb.append("  ").append(t).append(" — ").append(len).append(" chars\n")
        }
    }
    sb.append("```")
    return sb.toString()
}

// A2 "Deadline radar" (v9.33.9): "what's due" / "deadlines" / "what's
// coming up" - the offline radar over the user's own material. Short,
// typed commands only (long text is a study question).
internal val DEADLINES_Q = Regex(
    "(?i)^\\s*(?:what(?:'s| is)\\s+(?:due|coming\\s+up)|deadlines?|" +
        "my\\s+deadlines?|upcoming\\s+deadlines?|what\\s+deadlines\\s+do\\s+i\\s+have)\\s*[.!?]*\\s*$")

internal fun MainActivity.answerDeadlines(text: String): Boolean {
    if (text.length > 80 || !DEADLINES_Q.containsMatchIn(text)) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val body = try { NcieDeadlines.radar(activity) } catch (e: Exception) { "Deadline radar unavailable." }
        withContext(Dispatchers.Main) { postGroundReply(activity, body) }
    }
    return true
}

// A3 "Weekly review" (v9.33.10): "weekly review" / "week ahead" /
// "sunday review" - the offline digest of the week ahead + material.
internal val WEEKLY_Q = Regex(
    "(?i)^\\s*(?:weekly\\s+review|week\\s+in\\s+review|week\\s+ahead|sunday\\s+review|" +
        "how(?:'s| is)\\s+my\\s+week)\\s*[.!?]*\\s*$")

internal fun MainActivity.answerWeekly(text: String): Boolean {
    if (text.length > 80 || !WEEKLY_Q.containsMatchIn(text)) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val body = try { NcieWeekly.review(activity) } catch (e: Exception) { "Weekly review unavailable." }
        withContext(Dispatchers.Main) { postGroundReply(activity, body) }
    }
    return true
}

// A4 "Watchdog" (v9.33.11): "watchdog" / "system check" / "keep nova
// alive" - the offline self-heal: re-arm the reminders, confirm the
// nightly alarm, and report the notification listener.
internal val WATCHDOG_Q = Regex(
    "(?i)^\\s*(?:watchdog|system\\s+check|keep\\s+nova\\s+alive|is\\s+nova\\s+alive|" +
        "check\\s+nova|nova\\s+watchdog)\\s*[.!?]*\\s*$")

internal fun MainActivity.answerWatchdog(text: String): Boolean {
    if (text.length > 80 || !WATCHDOG_Q.containsMatchIn(text)) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val body = try { NcieWatchdog.run(activity) } catch (e: Exception) { "Watchdog unavailable." }
        withContext(Dispatchers.Main) { postGroundReply(activity, body) }
    }
    return true
}

// A5 "Focus mode" (v9.33.12): "focus 25" / "focus mode" / "stop focus".
// The start form needs a number; the bare form is the status.
internal val FOCUS_START_Q = Regex(
    "(?i)^\\s*(?:focus(?:\\s+mode)?(?:\\s+for)?\\s+(\\d{1,3})\\s*(?:min|mins|minutes)?|" +
        "deep\\s+work\\s+(\\d{1,3})\\s*(?:min|mins|minutes)?|" +
        "start\\s+focus\\s+(\\d{1,3}))\\s*[.!?]*\\s*$")
internal val FOCUS_OFF_Q = Regex(
    "(?i)^\\s*(?:stop|end|cancel|exit)\\s+focus(?:\\s+mode)?\\s*[.!?]*\\s*$")
internal val FOCUS_STATUS_Q = Regex(
    "(?i)^\\s*(?:focus|focus\\s+mode|focus\\s+status|am\\s+i\\s+focused)\\s*[.!?]*\\s*$")

internal fun MainActivity.answerFocus(text: String): Boolean {
    if (text.length > 60) return false
    val start = FOCUS_START_Q.find(text)
    val off = start == null && FOCUS_OFF_Q.containsMatchIn(text)
    val status = start == null && !off && FOCUS_STATUS_Q.containsMatchIn(text)
    if (start == null && !off && !status) return false
    val activity = this
    var n = 0
    if (start != null)
        n = (start.groupValues[1].ifBlank { start.groupValues[2] })
            .ifBlank { start.groupValues[3] }.toIntOrNull() ?: 25
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val body = try {
            if (start != null) NcieFocus.start(activity, n)
            else if (off) NcieFocus.stop(activity)
            else NcieFocus.status(activity)
        } catch (e: Exception) { "Focus mode unavailable." }
        withContext(Dispatchers.Main) { postGroundReply(activity, body) }
    }
    return true
}

// v9.6.0 "Engine Pack": "what have you learned" - the Experience Engine's
// deterministic answer: the last 20 questions whose answers the user
// regenerated (filesDir/experience.txt), monospace. Like DOCS_Q above: no
// model call, no network, works with no model loaded at all.
internal val LEARNED_Q = Regex("(?i)^\\s*what\\s+have\\s+you\\s+learned\\s*[.!?]*\\s*$")

// v9.30.0 "Growth": the self-evolution commands - what NOVA has taught
// itself. Short, typed commands only (long text is a study question).
internal val SKILLS_Q = Regex(
    "(?i)^\\s*(?:(?:your|my|nova'?s)\\s+)?skills?\\s*[.!?]*\\s*$" +
        "|^\\s*what\\s+skills\\b|^\\s*growth\\s*[.!?]*\\s*$" +
        "|^\\s*how\\s+have\\s+you\\s+grown\\b|^\\s*what\\s+have\\s+you\\s+taught\\s+yourself\\b"
)

internal fun MainActivity.answerSkills(text: String): Boolean {
    if (text.length > 80 || !SKILLS_Q.containsMatchIn(text)) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val body = try { NcieEvolve.report(activity) } catch (e: Exception) { "Growth report unavailable." }
        withContext(Dispatchers.Main) { postGroundReply(activity, "```\n" + body + "```") }
    }
    return true
}

internal fun MainActivity.answerJarvis(text: String): Boolean {
    if (text.length > 80) return false
    // hands-free on / off / toggle
    if (HANDSFREE_Q.containsMatchIn(text)) {
        // v9.31.2: hands-free needs the mic - ask for it the first time
        if (!NovaHandsfree.isOn() && !NovaHandsfree.hasMic(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4260)
            input.setText("")
            val um0 = Msg(Role.USER, text); currentChat.messages.add(um0); adapter.add(um0)
            val r0 = Msg(Role.ASSISTANT, "I need the microphone for hands-free. Allow it, then say \"hands free\" again.")
            currentChat.messages.add(r0); adapter.add(r0); scrollToEnd()
            return true
        }
        val on = NovaHandsfree.toggle(this)
        input.setText("")
        val um = Msg(Role.USER, text)
        currentChat.messages.add(um); adapter.add(um); scrollToEnd()
        val reply = Msg(Role.ASSISTANT, if (on)
            "Hands-free is on. I am listening - just talk. Say \"hands free off\" when you are done."
            else "Hands-free is off.")
        currentChat.messages.add(reply); adapter.add(reply); scrollToEnd()
        try { if (on) org.nova.NcieVoice.init(this) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
        return true
    }
    // v9.31.2: "remember this place as <name>" - the place trigger needs a spot
    val placeQ = Regex("(?i)^\\s*remember this place as\\s+(.{2,20})\\s*[.!?]*\\s*$").find(text)
    if (placeQ != null) {
        if (!NovaTriggers.hasLocation(this)) requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 4261)
        input.setText("")
        val um = Msg(Role.USER, text); currentChat.messages.add(um); adapter.add(um); scrollToEnd()
        val msg = if (!NovaTriggers.hasLocation(this))
            "Allow location, then say \"remember this place as home\" again."
            else NovaTriggers.rememberPlace(this, placeQ.groupValues[1])
        val rp = Msg(Role.ASSISTANT, msg); currentChat.messages.add(rp); adapter.add(rp); scrollToEnd()
        return true
    }
    // what triggers are set
    if (TRIGGERS_Q.containsMatchIn(text)) {
        val activity = this
        input.setText("")
        val um = Msg(Role.USER, text)
        currentChat.messages.add(um); adapter.add(um); scrollToEnd()
        scope.launch(Dispatchers.IO) {
            val list = try { NovaTriggers.all(activity) } catch (e: Exception) { emptyList() }
            val body = if (list.isEmpty()) "No triggers set yet."
                else "Your triggers:\n" + list.mapIndexed { i, t -> (i + 1).toString() + ". when " + t.cond + " -> " + t.what }.joinToString("\n")
            withContext(Dispatchers.Main) { postGroundReply(activity, "```\n" + body + "\n```") }
        }
        return true
    }
    return false
}

internal val HANDSFREE_Q = Regex(
    "(?i)^\\s*(?:hands[\\s-]?free|jarvis)(?:\\s+(?:mode|on|off))?\\s*[.!?]*\\s*$"
)

internal val TRIGGERS_Q = Regex(
    "(?i)^\\s*(?:my\\s+)?triggers?\\s*[.!?]*\\s*$"
)

internal fun MainActivity.answerLearned(text: String): Boolean {
    // long/OCR'd text is a study question that merely contains the
    // phrase - this command is always short and typed
    if (text.length > 80 || !LEARNED_Q.containsMatchIn(text)) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val learned = NcieEngines.lastLearned(activity)
        val body = if (learned.isEmpty())
            "Nothing logged yet — it fills when you regenerate a reply."
        else "```\nWhat I've learned from your regenerations:\n\n" +
            learned.joinToString("\n") { "- " + it } + "\n```"
        withContext(Dispatchers.Main) { postGroundReply(activity, body) }
    }
    return true
}

// v9.6.0: "connections" / "related documents" - the Knowledge Graph's
// deterministic cross-document map: every pair of the user's sources
// (Knowledge documents + offline wiki articles) sharing at least 3 key
// terms, monospace, with the shared terms. No model call, no network.
internal val CONNECT_Q = Regex("(?i)^\\s*(?:connections?|related\\s+documents?)\\s*[.!?]*\\s*$")

internal fun MainActivity.answerConnections(text: String): Boolean {
    // long/OCR'd text is a study question that merely contains the
    // phrase - this command is always short and typed
    if (text.length > 80 || !CONNECT_Q.containsMatchIn(text)) return false
    val activity = this
    input.setText("")
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    scope.launch(Dispatchers.IO) {
        val conns = NcieGround.connections(activity)
        val body = if (conns.isEmpty()) "No strong connections found."
        else "```\n" + conns.joinToString("\n") {
                it.first + " ↔ " + it.second + ": " + it.third.joinToString(", ")
            } + "\n```"
        withContext(Dispatchers.Main) { postGroundReply(activity, body) }
    }
    return true
}

// ---------------------------------------------------------------------
// v9.7.0 "Quiet Fetch"
// ---------------------------------------------------------------------

// v9.12.1 "Context Diet": biographical questions - "who is <Name>",
// "who was", "when did", "how did" (+ a capitalized name token, or a
// name-like word when the user types lowercase, e.g. "who is rahul
// gandhi"). These are exactly the questions whose wrong local answers
// went unchallenged, so the online offer returns for them even when
// weak local material exists.
internal val BIO_Q = Regex(
    "(?i)\\b(?:who\\s+(?:is|was|are|were)|when\\s+(?:did|was|were|is|will)|how\\s+(?:did|do))\\b")

/** v9.13.0 "Audit Fixes" (HIGH 7): the words that are NEVER a name -
 *  question words, app words and study words. The v9.12.1 fallback
 *  treated ANY 4-letter word as a name ("when did the exam start" ->
 *  "exam"). */
internal val BIO_STOPWORDS = setOf(
    "you", "this", "that", "when", "how", "what", "who", "why", "where",
    "nova", "chat", "exam", "exams", "notes", "note", "the", "and", "was",
    "were", "is", "are", "did", "does", "do", "from", "with", "about",
    "tell", "explain", "describe", "define", "start", "startt", "begin",
    "first", "last", "next", "then", "there", "here", "your", "my")

internal fun bioQuestion(text: String): Boolean {
    if (!BIO_Q.containsMatchIn(text)) return false
    // v9.13.0 "Audit Fixes" (HIGH 7): a name is a capitalized token of
    // length >= 3 that is not a stopword (or two consecutive capitalized
    // tokens - "Rahul Gandhi" typed with capitals). The old lowercase
    // fallback treated any 4-letter word as a name; it is gone. This
    // helper no longer overrides a STRONG wiki match (the offer gate
    // above checks wikiStrong first) - it only shapes what counts as a
    // biographical question where it is still consulted.
    val toks = text.split(Regex("\\s+"))
        .map { it.trim('\'', '\"', ',', '.', '?', '!', ':', ';') }
    var prevCap = false
    for (t in toks) {
        val cap2 = t.length >= 2 && t[0].isUpperCase() &&
            t.none { it.isDigit() } &&
            t.lowercase() !in BIO_STOPWORDS
        if (cap2 && (t.length >= 3 || prevCap)) return true
        prevCap = cap2
    }
    return false
}

/** v9.7.0 "Quiet Fetch": the single entry into the online fetch flow.
 *  Both the quiet offer (only when NO local material backs the question)
 *  and the manual "look it up online" command route through here, so the
 *  fetch stays ask-first (OnlineFetch's dialog) and the call site stays
 *  single. */
internal fun offerOnlineFetch(act: MainActivity, text: String) {
    OnlineFetch.offer(act, text, false)
}

/** v9.7.0 "Quiet Fetch": parse the manual online-lookup command.
 *  v9.13.2 "Small Model Honesty": the phrase list is broad now - the
 *  old two-form regex silently missed how the user actually types it
 *  ("look up at online: compare bmw vs buggati" fell through to the
 *  model and hallucinated). Accepted, case-insensitive, each with an
 *  optional trailing colon: "look it up online", "look up online",
 *  "look up at online", "look it up on the internet", "look online",
 *  "search online", "search the web", "find it online", "google it",
 *  "search the internet". The rest of the message is the query; a
 *  bare form (or an empty rest) re-runs the last question in this
 *  chat ("" when there is none, so the caller can explain itself).
 *  Null = not the command at all. */
internal val LOOKUP_PREFIXES = listOf(
    "look it up online", "look up online", "look up at online",
    "look it up on the internet", "look online", "search online",
    "search the web", "find it online", "google it", "search the internet"
)

internal fun MainActivity.onlineLookupQ(text: String): String? {
    val lower = text.lowercase()
    for (p in LOOKUP_PREFIXES) {
        if (!lower.startsWith(p)) continue
        var q = text.substring(p.length).trim(' ', ':')
        if (q.lowercase().startsWith("for ")) q = q.substring(4)
        q = q.trim()
        if (q.isEmpty()) {
            val last = currentChat.messages.lastOrNull { it.role == Role.USER }?.text
            return last?.trim() ?: ""
        }
        return q
    }
    return null
}

// v9.29.3 "Tool recipes": "build a tool to ..." authors a recipe from
// NOVA's fixed OFFLINE primitives; "run tool ..." replays a saved one.
// A recipe is data, never code - nothing the model writes is executed.
internal val TOOL_BUILD_Q = Regex(
    "(?i)^\\s*(?:build|make|create)\\s+(?:me\\s+)?(?:a\\s+)?tool\\s+(?:to|that|which)\\s+(.+?)\\s*$")
internal val TOOL_RUN_Q = Regex(
    "(?i)^\\s*(?:run|use)\\s+(?:the\\s+)?tool\\s+(.+?)\\s*$")

fun MainActivity.answerToolRequest(text: String): Boolean {
    val build = TOOL_BUILD_Q.find(text)?.groupValues?.get(1)?.trim()
    val run = TOOL_RUN_Q.find(text)?.groupValues?.get(1)?.trim()
    if (build == null && run == null) return false
    val self = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()

    fun reply(s: String) {
        val r = Msg(Role.ASSISTANT, s)
        self.currentChat.messages.add(r); self.adapter.add(r); self.scrollToEnd()
        self.scope.launch(Dispatchers.IO) {
            try { ChatStore.save(self, self.currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
        }
    }

    if (NcieTools.grantedFolder(this) == null) {
        reply("I need a folder to look in first. Open the menu (the three lines), " +
            "tap \"Files\", pick a folder, then ask me again.")
        return true
    }

    if (run != null) {
        val r = NcieRecipes.all(this).firstOrNull {
            it.name.equals(run, true) || it.name.contains(run, true)
        }
        if (r == null) {
            reply("I don't have a tool called \"$run\" yet. Say \"build a tool to ...\" and I'll make one.")
            return true
        }
        reply("Running \"${r.name}\"...")
        scope.launch(Dispatchers.IO) {
            val out = NcieTools.run(self, r.steps)
            withContext(Dispatchers.Main) { reply(out.ifBlank { "(the tool returned nothing)" }) }
        }
        return true
    }

    val req = build!!
    reply("Building a tool for: $req")
    scope.launch(Dispatchers.IO) {
        val json = try { NovaEngineAdapter.generate(NcieRecipes.authorPrompt(req), 400) }
            catch (e: Exception) { "" }
        val recipe = NcieRecipes.parse(json)
        withContext(Dispatchers.Main) {
            if (recipe == null || recipe.steps.isEmpty()) {
                reply("I couldn't build a tool for that with what I have. I can look at " +
                    "files, read them, search, summarise, and answer questions about them.")
                return@withContext
            }
            android.app.AlertDialog.Builder(self)
                .setTitle("New tool")
                .setMessage(NcieRecipes.preview(recipe) + "\n\nRun it?")
                .setPositiveButton("Run") { _, _ ->
                    reply("Running...")
                    self.scope.launch(Dispatchers.IO) {
                        val out = NcieTools.run(self, recipe.steps)
                        withContext(Dispatchers.Main) {
                            reply(out.ifBlank { "(the tool returned nothing)" })
                            android.app.AlertDialog.Builder(self)
                                .setTitle("Save this tool?")
                                .setMessage("Save \"" + recipe.name + "\" so you can say " +
                                    "\"run tool " + recipe.name + "\" next time.")
                                .setPositiveButton("Save") { _, _ ->
                                    self.scope.launch(Dispatchers.IO) { NcieRecipes.save(self, recipe) }
                                    reply("Saved. Say \"run tool ${recipe.name}\" any time.")
                                }
                                .setNegativeButton("No", null)
                                .show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    return true
}

/**
 * v9.33.14 "No invented lyrics": a request for song lyrics is answered
 * honestly instead of being sent to a model that has no lyrics to read and
 * therefore invents them - and invents the artist and album too ("Love Me
 * Not" came back as Elvis Presley, from Sgt. Pepper's). Lyrics are
 * copyrighted besides, so NOVA offers facts about the song instead.
 *
 * Narrow on purpose: only an explicit ask for lyrics, so "explain the
 * lyrics of the poem" still reaches the model.
 */
internal val LYRICS_Q = Regex(
    "(?i)\\b(?:give|send|tell|show|sing|write|get|what\\s+are|whats|what's)\\b[^.]{0,40}\\blyrics?\\b" +
        "|\\blyrics?\\b\\s+(?:of|for|to)\\b")

internal fun MainActivity.answerLyrics(text: String): Boolean {
    if (!LYRICS_Q.containsMatchIn(text)) return false
    val act = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um)
    adapter.add(um)
    val reply = Msg(Role.ASSISTANT,
        "I can't give you song lyrics - they're copyrighted, and I don't have " +
            "them to read, so anything I produced would be invented. I can tell " +
            "you about the song instead: the artist, the album, when it came out, " +
            "or what it is about.")
    currentChat.messages.add(reply)
    adapter.add(reply)
    scrollToEnd()
    scope.launch(Dispatchers.IO) {
        try { ChatStore.save(act, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
    return true
}

/** v9.33.18: "make me laugh" / "tell me a joke" - a deterministic joke,
 *  no model involved. Narrow, so ordinary chat still reaches the model. */
internal val JOKE_Q = Regex(
    "(?i)\\b(?:make me laugh|tell me (?:a |another )?joke|say something funny|" +
        "got any jokes?|joke please|know any jokes?)\\b")

internal fun MainActivity.answerJoke(text: String): Boolean {
    if (!JOKE_Q.containsMatchIn(text)) return false
    val act = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um)
    adapter.add(um)
    val reply = Msg(Role.ASSISTANT, Jokes.at(System.currentTimeMillis().toInt()))
    currentChat.messages.add(reply)
    adapter.add(reply)
    scrollToEnd()
    scope.launch(Dispatchers.IO) {
        try { ChatStore.save(act, currentChat) } catch (e: Exception) { NovaLog.quiet("NcieChat", e) }
    }
    return true
}
