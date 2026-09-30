package org.nova.ncie.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nova.ChatStore
import org.nova.MainActivity
import org.nova.Msg
import org.nova.Role
import org.nova.WikiCore
import java.io.File

/**
 * v8.9.0: Tutor Mode. Three study tools on top of the material the app
 * already has - Knowledge documents and offline wiki articles:
 *
 *  - "quiz me on X" / "test me on X": the source text is found locally
 *    (case-insensitive substring on the doc name, then on the wiki
 *    article title), the in-app model writes exactly 3 Q/A pairs from
 *    it, and every answer is graded by a SECOND model pass against the
 *    reference text (two-pass checking) instead of substring matching.
 *  - weak areas: every wrong answer is logged to tutor_miss.txt with a
 *    timestamp; "my weak areas" lists them deterministically (most
 *    missed first) and "study" re-quizzes the first topic whose misses
 *    are at least a day old (1-day spaced repetition). Entries are
 *    pruned only when re-answered correctly, so nothing is forgotten by
 *    accident.
 *  - flashcards: "add flashcard front = back" / "my flashcards" /
 *    "quiz my flashcards" - a flip-and-self-grade drill whose wrongs
 *    join the same weak-areas log.
 *
 * Fully local: the LLM (already loaded in-app) is used only to write
 * questions and grade answers; there is no network for this feature
 * and no new dependency. Deterministic paths never touch the model.
 * Session state is single-session mutable fields, like the rest of the
 * app's chat routing; the chat UI helpers follow the PROFILE_Q /
 * MISSED_Q pattern (user bubble, reply bubble, chat save off-thread).
 */
object NcieTutor {

    // ---------------------------------------------------- session state

    /** The source text the running quiz was built from (null = no quiz). */
    var quizSource: String? = null
    /** The topic the quiz is on - the key used in the weak-areas log. */
    var quizTopic: String = ""
    /** Parsed (question, answer) pairs from the model's quiz reply. */
    var quizQuestions: List<Pair<String, String>> = emptyList()
    /** The pair the user is currently answering. */
    var quizIndex: Int = 0
    /** v9.4.0 "Audit Fixes II" (audit: quizzes stopped at 3000 chars):
     *  the 3000-char chunk of [quizSource] the running quiz is on. */
    var quizChunk: Int = 0
    /** The card the flashcard drill is on. */
    var flashIndex: Int = 0
    /** The front of the card awaiting a flip (null = no drill running). */
    var flashFront: String? = null
    /** v9.3.0 (audit: quiz state was global): the chat id the running
     *  quiz/flashcard session was started in. A message from any other
     *  chat is NOT an answer - it never enters the quiz flow at all
     *  (see pendingAnswer). */
    private var sessionChatId: String? = null

    /** v9.3.0 "Audit Fixes I": the whole-message escape words - "stop",
     *  "skip", "end", "cancel" - which end a running session cleanly. */
    private val ESCAPE_WORDS = setOf("stop", "skip", "end", "cancel")

    /** The back was shown - waiting for the user's right/wrong verdict. */
    private var flashVerdictPending = false
    /** This quiz came from "study": correct answers prune the log. */
    private var studySession = false
    private var flashTotal = 0
    private var flashRight = 0

    // ------------------------------------------------------- storage

    /** WikiCore's line format separator (unit-separator symbol (Kotlin escape \u241F, like WikiCore.kt)). */
    private const val SEP = '\u241F'
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun missFile(ctx: Context) = File(ctx.filesDir, "tutor_miss.txt")
    private fun flashFile(ctx: Context) = File(ctx.filesDir, "flashcards.txt")
    /** v9.3.0 (audit: flashcard wrongs blocked "study"): flashcard misses
     *  get their OWN log, never tutor_miss.txt - the dead "flashcards"
     *  topic used to be picked by spaced repetition with no source to
     *  re-quiz against. */
    private fun flashMissFile(ctx: Context) = File(ctx.filesDir, "flash_miss.txt")

    private fun readMiss(ctx: Context): List<String> = try {
        val f = missFile(ctx)
        if (f.exists()) f.readLines() else emptyList()
    } catch (e: Exception) { emptyList() }

    private fun readCards(ctx: Context): List<Pair<String, String>> = try {
        val f = flashFile(ctx)
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull { l ->
            val i = l.indexOf(SEP)
            if (i < 1) null
            else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
    } catch (e: Exception) { emptyList() }

    private fun oneLine(s: String) =
        s.replace("\t", " ").replace("\n", " ").replace(SEP, ' ')

    /** millis<TAB>topic<TAB>question - one line per wrong answer. */
    private fun appendMiss(ctx: Context, topic: String, question: String) {
        try {
            missFile(ctx).apply { parentFile?.mkdirs() }.appendText(
                System.currentTimeMillis().toString() + "\t" +
                    topic.replace("\t", " ").replace("\n", " ") + "\t" +
                    question.replace("\t", " ").replace("\n", " ") + "\n")
        } catch (e: Exception) { }
    }

    /** v9.3.0: millis<TAB>front<TAB>back - one line per wrong flashcard,
     *  in flash_miss.txt (NOT the weak-areas log). */
    private fun appendFlashMiss(ctx: Context, front: String, back: String) {
        try {
            flashMissFile(ctx).apply { parentFile?.mkdirs() }.appendText(
                System.currentTimeMillis().toString() + "\t" +
                    front.replace("\t", " ").replace("\n", " ") + "\t" +
                    back.replace("\t", " ").replace("\n", " ") + "\n")
        } catch (e: Exception) { }
    }

    /** Drop the (topic, question) entry the user just re-answered
     *  correctly - only called on a "study" session.
     *  v9.4.0: the topic matches case-insensitively, so every spelling
     *  of the topic is pruned at once. */
    private fun pruneMissed(ctx: Context, topic: String, question: String) {
        try {
            val f = missFile(ctx)
            if (!f.exists()) return
            val key = topic.trim().lowercase()
            val kept = f.readLines().filter { l ->
                val p = l.split("\t", limit = 3)
                !(p.size == 3 && p[1].trim().lowercase() == key && p[2] == question)
            }
            f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------- entry points

    /** True while the user's next message belongs to a running session:
     *  a quiz answer, a flashcard flip, or a right/wrong verdict. The
     *  chat turn routes to [continueSession] before anything else.
     *  v9.3.0 (audit: quiz state was global): when [chat] is given and
     *  the session was started in a different chat, this is false - the
     *  message never lands in the quiz flow at all. */
    fun pendingAnswer(chat: String? = null): Boolean {
        val active = (quizSource != null && quizIndex < quizQuestions.size) ||
            flashFront != null || flashVerdictPending
        if (!active) return false
        if (chat != null && sessionChatId != null && sessionChatId != chat) return false
        return true
    }

    /** The stateful continuation - the user's message while a session
     *  is pending. Returns true when handled (always, when pending).
     *  v9.3.0 "Audit Fixes I" (audit: no way out of a session): the
     *  whole-message words "stop", "skip", "end", "cancel" end it
     *  cleanly - stop/end/cancel cancel everything, skip shows the
     *  current answer and moves on. */
    fun continueSession(act: MainActivity, text: String): Boolean {
        if (!pendingAnswer()) return false
        val w = text.trim().lowercase()
        if (w in ESCAPE_WORDS) {
            showUser(act, text)
            val flash = flashFront != null || flashVerdictPending
            if (w == "skip") {
                if (flash) skipFlash(act) else skipQuiz(act)
            } else {
                if (flash) { resetFlash(); postReply(act, "Flashcards stopped.") }
                else { resetQuiz(); postReply(act, "Quiz stopped.") }
            }
            return true
        }
        showUser(act, text)
        if (flashFront != null) { continueFlash(act, text); return true }
        if (quizSource != null && quizIndex < quizQuestions.size) {
            if (!act.ensureModelReady()) return true
            checkAnswer(act, text)
        }
        return true
    }

    /** "quiz me on X" - find the source locally, have the model write
     *  the questions, ask the first one. */
    fun startQuiz(act: MainActivity, text: String, topic: String) {
        showUser(act, text)
        beginQuiz(act, topic)
    }

    /** "study" / "study my weak areas" - 1-day spaced repetition: the
     *  first distinct topic whose misses are at least a day old.
     *  v9.3.0 (audit: a dead "flashcards" topic blocked study): legacy
     *  "flashcards" entries in tutor_miss.txt are ignored - they have no
     *  retrievable source and can never be re-quit - and a due topic
     *  whose source is gone is skipped with a note, not a dead end. */
    fun startStudy(act: MainActivity, text: String) {
        showUser(act, text)
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        val due = ArrayList<String>()
        for (l in readMiss(act)) {
            val p = l.split("\t", limit = 3)
            if (p.size < 3) continue
            val t = p[0].toLongOrNull() ?: continue
            val topic = p[1].trim()
            // v9.3.0: dead "flashcards" entries never come back
            if (topic.isEmpty() || topic.equals("flashcards", ignoreCase = true)) continue
            // v9.4.0 "Audit Fixes II" (audit: "Federalism" and
            // "federalism" counted as two topics): dedupe on the
            // normalized key, keep the first-seen spelling to quiz
            if (!seen.add(topic.lowercase())) continue
            if (now - t >= DAY_MS) due.add(topic)
        }
        if (due.isEmpty()) {
            postReply(act, if (readMiss(act).isEmpty())
                "No weak areas recorded yet — quiz yourself on something."
            else "Nothing is due yet — weak areas come back for review after a day.")
            return
        }
        act.scope.launch(Dispatchers.IO) {
            var pick: String? = null
            val skipped = ArrayList<String>()
            for (topic in due) {
                if (findSource(act, topic) != null) { pick = topic; break }
                skipped.add(topic)
            }
            withContext(Dispatchers.Main) {
                if (pick == null) {
                    val sb = StringBuilder()
                    for (s in skipped) sb.append("no notes found for topic ").append(s)
                        .append(" — skipped\n")
                    sb.append("Nothing due is studyable right now — paste the " +
                        "chapter into Knowledge or fetch it online first.")
                    postReply(act, sb.toString().trim())
                } else {
                    studySession = true
                    beginQuiz(act, pick!!, viaStudy = true)
                }
            }
        }
    }

    /** "my weak areas" - deterministic, no model. */
    fun weakAreas(act: MainActivity, text: String) {
        showUser(act, text)
        postReply(act, weakAreasList(act))
    }

    fun weakAreasList(ctx: Context): String {
        val lines = readMiss(ctx)
        if (lines.isEmpty())
            return "No weak areas recorded yet — quiz yourself on something."
        val now = System.currentTimeMillis()
        val counts = HashMap<String, Int>()
        val lastAt = HashMap<String, Long>()
        // v9.4.0 "Audit Fixes II" (audit: case/whitespace variants of the
        // same topic were listed as separate weak areas): group on the
        // normalized key, display the first-seen original spelling
        val display = HashMap<String, String>()
        for (l in lines) {
            val p = l.split("\t", limit = 3)
            if (p.size < 3) continue
            val t = p[0].toLongOrNull() ?: continue
            val topic = p[1].trim()
            if (topic.isEmpty()) continue
            val key = topic.lowercase()
            counts[key] = (counts[key] ?: 0) + 1
            if (t > (lastAt[key] ?: 0L)) lastAt[key] = t
            if (!display.containsKey(key)) display[key] = topic
        }
        if (counts.isEmpty())
            return "No weak areas recorded yet — quiz yourself on something."
        val sb = StringBuilder("```\nWeak areas (most missed first):\n\n")
        for (key in counts.keys.sortedWith(
                compareByDescending<String> { counts[it] ?: 0 }.thenBy { it })) {
            val days = ((now - (lastAt[key] ?: 0L)) / DAY_MS).toInt()
            sb.append(display[key]).append(" (").append(counts[key] ?: 0)
                .append("), last missed ").append(days)
                .append(if (days == 1) " day ago" else " days ago").append('\n')
        }
        sb.append("\nSay 'study' to revise the ones a day old or more.```")
        return sb.toString()
    }

    /** "add flashcard front = back". */
    fun addFlashcard(act: MainActivity, text: String, rest: String) {
        showUser(act, text)
        val i = rest.indexOf('=')
        val front = if (i > 0) rest.substring(0, i).trim() else ""
        val back = if (i > 0) rest.substring(i + 1).trim() else ""
        if (front.isEmpty() || back.isEmpty()) {
            postReply(act, "Add one like this: add flashcard capital of France = Paris")
            return
        }
        try {
            flashFile(act).apply { parentFile?.mkdirs() }
                .appendText(oneLine(front) + SEP + oneLine(back) + "\n")
        } catch (e: Exception) {
            postReply(act, "Could not save the flashcard.")
            return
        }
        postReply(act, "Saved. You now have " + readCards(act).size + " flashcards.")
    }

    /** "my flashcards" - deterministic, no model. */
    fun flashcardsList(act: MainActivity, text: String) {
        showUser(act, text)
        postReply(act, flashcardsReply(act))
    }

    fun flashcardsReply(ctx: Context): String {
        val cards = readCards(ctx)
        if (cards.isEmpty())
            return "No flashcards yet - add one with 'add flashcard front = back'."
        val sb = StringBuilder("```\nYour flashcards (").append(cards.size).append("):\n\n")
        for ((n, c) in cards.withIndex()) sb.append(n + 1).append(". ").append(c.first).append('\n')
        sb.append("```")
        return sb.toString()
    }

    /** "quiz my flashcards" / "flashcards" - the flip drill. */
    fun startFlashQuiz(act: MainActivity, text: String) {
        showUser(act, text)
        val cards = readCards(act)
        if (cards.isEmpty()) {
            postReply(act, "No flashcards yet - add one with 'add flashcard front = back'.")
            return
        }
        flashIndex = 0
        flashRight = 0
        flashTotal = cards.size
        flashVerdictPending = false
        flashFront = cards[0].first
        sessionChatId = act.currentChat.id
        postReply(act, "Flashcards — " + cards.size +
            " cards. Say 'flip' when you want the answer.\n\n1. " + cards[0].first)
    }

    // ------------------------------------------------------ the quiz

    private fun beginQuiz(act: MainActivity, topic: String, viaStudy: Boolean = false) {
        val t = topic.trim()
        if (t.length < 2) {
            postReply(act, "Name a topic - e.g. 'quiz me on federalism'.")
            return
        }
        if (act.compacting) {
            postReply(act, "Compressing older messages — one moment")
            return
        }
        if (!act.ensureModelReady()) return
        // v9.3.0: bind the session to the chat it started in, on the
        // main thread, before the source hunt goes to IO
        sessionChatId = act.currentChat.id
        act.scope.launch(Dispatchers.IO) {
            val found = findSource(act, t)
            if (found == null) {
                postReplyOnMain(act, "I don't have notes on $t. Paste the chapter " +
                    "into Knowledge or fetch it online first.")
                return@launch
            }
            val (srcName, srcText) = found
            // v9.4.0: chunk 0 - a source longer than 3000 chars is
            // quizzed window by window until the chapter is covered
            // v9.13.0 "Audit Fixes" (HIGH 6): question writing is a real
            // generation - flag it, so the embedder's pause logic is accurate.
            NcieChat.generating = true
            val reply = try { NovaEngineAdapter.generate(
                quizPrompt(t, chunkOf(srcText, 0)), act.settings.predictLength)
            } finally { NcieChat.generating = false }
            val pairs = parseQuiz(reply)
            if (pairs.isEmpty()) {
                postReplyOnMain(act, "Could not build questions from that text.")
                return@launch
            }
            quizSource = srcText
            quizTopic = t
            quizQuestions = pairs
            quizIndex = 0
            quizChunk = 0
            studySession = viaStudy
            val parts = chunkCount(srcText)
            val head = if (viaStudy)
                "Revision time — this one gave you trouble before.\n\n"
            else "Quiz on $t (from $srcName) — ${pairs.size} questions" +
                (if (parts > 1) ", part 1 of $parts" else "") + ". " +
                "Answer each one in your own words.\n\n"
            postReplyOnMain(act, head + "Q1: " + pairs[0].first)
        }
    }

    /** The fixed question-generation prompt (first 3000 chars of source). */
    fun quizPrompt(topic: String, text: String): String =
        "You are a quiz maker. From the TEXT below, write exactly 3 short " +
            "questions with answers, each on its own lines as 'Q: question' " +
            "then 'A: answer'. Nothing else. TEXT: " + text.take(3000)

    /** v9.4.0 "Audit Fixes II" (audit: grading saw a different window
     *  than generation): the check prompt now uses the SAME 3000 chars
     *  the questions came from. */
    private fun checkPrompt(text: String, q: String, a: String, userAnswer: String): String =
        "Reference text: " + text.take(3000) + ". Question: " + q + ". Expected answer: " +
            a + ". Student's answer: " + userAnswer + ". Does the student's answer " +
            "show correct understanding? Reply with only YES or NO."

    /** Q:/A: lines out of the model's quiz reply. */
    private fun parseQuiz(reply: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var q: String? = null
        for (line in reply.lines()) {
            val l = line.trim()
            if (l.startsWith("Q:", ignoreCase = true)) q = l.substring(2).trim()
            else if (l.startsWith("A:", ignoreCase = true) && q != null) {
                val a = l.substring(2).trim()
                if (q.isNotEmpty() && a.isNotEmpty()) out.add(q to a)
                q = null
            }
        }
        return out
    }

    /** Pass 2: grade the user's answer with the model, log the miss,
     *  then present the next question (or Quiz complete).
     *  v9.3.0 "Audit Fixes I" (audit: "Yes." marked wrong; engine error
     *  graded wrong): the verdict's first word is stripped to letters
     *  and lowercased before the yes/y check, and an engine failure
     *  (blank reply or "[engine ...]") is NOT a wrong answer - no miss
     *  is logged, the question is not advanced, the user just tries
     *  again. */
    private fun checkAnswer(act: MainActivity, userAnswer: String) {
        if (act.compacting) {
            postReply(act, "Compressing older messages — one moment")
            return
        }
        val q = quizQuestions[quizIndex].first
        val a = quizQuestions[quizIndex].second
        // v9.4.0: grade against the chunk the question came from
        val src = quizSource?.let { chunkOf(it, quizChunk) } ?: ""
        act.scope.launch(Dispatchers.IO) {
            // v9.13.0 "Audit Fixes" (HIGH 6): grading is a real generation
            // too - same process-wide flag as the question writing above.
            NcieChat.generating = true
            val verdict = try { NovaEngineAdapter.generate(
                checkPrompt(src, q, a, userAnswer), 16)
            } finally { NcieChat.generating = false }
            val trimmed = verdict.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("[engine")) {
                postReplyOnMain(act, "Couldn't grade that — say your answer again.")
                return@launch
            }
            val firstWord = trimmed.split(Regex("\\s+")).firstOrNull() ?: ""
            val norm = firstWord.filter { it.isLetter() }.lowercase()
            val yes = norm == "y" || norm.startsWith("yes")
            val sb = StringBuilder()
            if (yes) {
                sb.append("Correct.")
                if (studySession) pruneMissed(act, quizTopic, q)
            } else {
                sb.append("Not quite — the answer is: ").append(a)
                appendMiss(act, quizTopic, q)
            }
            quizIndex = quizIndex + 1
            if (quizIndex < quizQuestions.size) {
                sb.append("\n\nQ").append(quizIndex + 1).append(": ")
                    .append(quizQuestions[quizIndex].first)
            } else {
                appendChunkEnd(act, sb)
                return@launch
            }
            postReplyOnMain(act, sb.toString())
        }
    }

    // -------------------------------------------------- the flashcards

    /** v9.4.0 "Audit Fixes II" (audit: a long chapter was quizzed only
     *  up to char 3000): how many 3000-char windows the source has. */
    private fun chunkCount(src: String): Int =
        if (src.isEmpty()) 1 else (src.length + 2999) / 3000

    /** The i-th 3000-char window of the source. */
    private fun chunkOf(src: String, i: Int): String =
        src.substring(i * 3000, minOf((i + 1) * 3000, src.length))

    /** v9.4.0: a chunk's questions just ran out. Either keep going with
     *  the next 3000-char window, or finish with the part count. The
     *  message is built into [sb]; the reply is posted from here on the
     *  main dispatcher (safe from any thread). */
    private fun appendChunkEnd(act: MainActivity, sb: StringBuilder) {
        val src = quizSource
        val total = if (src != null) chunkCount(src) else 1
        if (src != null && quizChunk + 1 < total) {
            sb.append("\n\nOn to the next part.")
            act.scope.launch { postReply(act, sb.toString()) }
            advanceChunk(act)
        } else {
            sb.append("\n\nQuiz complete — covered the whole chapter in ")
                .append(total).append(" part")
            if (total != 1) sb.append("s")
            sb.append(".")
            resetQuiz()
            act.scope.launch { postReply(act, sb.toString()) }
        }
    }

    /** v9.4.0: generate the questions for the next 3000-char window. */
    private fun advanceChunk(act: MainActivity) {
        val src = quizSource ?: return
        val next = quizChunk + 1
        val total = chunkCount(src)
        if (next >= total) return
        act.scope.launch(Dispatchers.IO) {
            if (act.compacting) {
                postReplyOnMain(act, "Compressing older messages — one moment")
                return@launch
            }
            // v9.13.0 "Audit Fixes" (HIGH 6): the next window's questions
            // are a real generation too - flag it like the first one.
            NcieChat.generating = true
            val reply = try { NovaEngineAdapter.generate(
                quizPrompt(quizTopic, chunkOf(src, next)), act.settings.predictLength)
            } finally { NcieChat.generating = false }
            val pairs = parseQuiz(reply)
            if (pairs.isEmpty()) {
                postReplyOnMain(act, "Could not build questions from that text.")
                resetQuiz()
                return@launch
            }
            quizChunk = next
            quizQuestions = pairs
            quizIndex = 0
            postReplyOnMain(act, "Part " + (next + 1) + " of " + total +
                " — ${pairs.size} questions.\n\nQ1: " + pairs[0].first)
        }
    }

    /** v9.3.0: quiz state cleared, nothing kept. */
    private fun resetQuiz() {
        quizSource = null
        quizQuestions = emptyList()
        quizIndex = 0
        quizChunk = 0
        studySession = false
    }

    /** v9.3.0 "Audit Fixes I" ("skip" during a quiz): show the current
     *  answer and move to the next question - no miss is logged, the
     *  skipped question is not counted as wrong. */
    private fun skipQuiz(act: MainActivity) {
        val sb = StringBuilder("Skipped — the answer is: ")
            .append(quizQuestions[quizIndex].second)
        quizIndex = quizIndex + 1
        if (quizIndex < quizQuestions.size) {
            sb.append("\n\nQ").append(quizIndex + 1).append(": ")
                .append(quizQuestions[quizIndex].first)
        } else {
            // v9.4.0 "Audit Fixes II": the next 3000-char window, or the
            // whole-chapter finish with the part count
            appendChunkEnd(act, sb)
            return
        }
        postReply(act, sb.toString())
    }

    /** v9.3.0 ("skip" during the flip drill): show the current card's
     *  back, then the next card's front - no verdict, no miss logged. */
    private fun skipFlash(act: MainActivity) {
        val card = readCards(act).getOrNull(flashIndex)
        flashVerdictPending = false
        flashIndex = flashIndex + 1
        val next = readCards(act).getOrNull(flashIndex)
        if (card == null || next == null) {
            val done = "Flashcards complete — " + flashRight + " of " +
                flashTotal + " correct."
            resetFlash()
            postReply(act, done)
        } else {
            flashFront = next.first
            postReply(act, card.second + "\\n\\n" + (flashIndex + 1) + ". " +
                next.first + "\\n\\n(type flip when you want the answer)")
        }
    }

    private fun continueFlash(act: MainActivity, text: String) {
        val t = text.trim().lowercase()
        if (flashVerdictPending) {
            val card = readCards(act).getOrNull(flashIndex)
            when (t) {
                "right" -> flashRight++
                "wrong" -> if (card != null)
                    // v9.3.0: flashcard misses go to flash_miss.txt, NOT
                    // the weak-areas log (the dead "flashcards" topic)
                    appendFlashMiss(act, card.first, card.second)
                else -> { postReply(act, "Did you get it right? (right/wrong)"); return }
            }
            flashVerdictPending = false
            flashIndex = flashIndex + 1
            val next = readCards(act).getOrNull(flashIndex)
            if (next == null) {
                val done = "Flashcards complete — " + flashRight + " of " +
                    flashTotal + " correct."
                resetFlash()
                postReply(act, done)
            } else {
                flashFront = next.first
                postReply(act, (flashIndex + 1).toString() + ". " + next.first +
                    "\n\n(type flip when you want the answer)")
            }
            return
        }
        if (t == "flip") {
            val card = readCards(act).getOrNull(flashIndex)
            if (card == null) {
                resetFlash()
                postReply(act, "That card is gone - start over with 'quiz my flashcards'.")
                return
            }
            flashVerdictPending = true
            postReply(act, card.second + "\n\ndid you get it right? (right/wrong)")
            return
        }
        postReply(act, "Type flip to see the answer.")
    }

    private fun resetFlash() {
        flashIndex = 0
        flashFront = null
        flashVerdictPending = false
        flashTotal = 0
        flashRight = 0
    }

    // ------------------------------------------------- source discovery

    /** The topic against the app's stores: Knowledge documents first
     *  (case-insensitive substring on the doc name), then offline wiki
     *  articles (substring on the title, text via WikiCore). */
    private fun findSource(ctx: Context, topic: String): Pair<String, String>? {
        val t = topic.trim().lowercase()
        if (t.isEmpty()) return null
        for (d in NcieKnowledge.docs(ctx)) {
            if (d.first.lowercase().contains(t)) {
                val txt = NcieKnowledge.docText(ctx, d.first)
                if (txt.isNotBlank()) return d.first to txt
            }
        }
        try {
            val done = File(ctx.filesDir, "wiki").resolve("done.txt")
            if (done.exists()) {
                WikiCore.warmUp(ctx)
                for (title in done.readLines()) {
                    val s = title.trim()
                    if (s.isNotEmpty() && s.lowercase().contains(t)) {
                        val txt = WikiCore.articleText(ctx, s)
                        if (!txt.isNullOrBlank()) return s to txt
                    }
                }
            }
        } catch (e: Exception) { }
        return null
    }

    // ------------------------------------------------------ chat plumbing

    private fun showUser(act: MainActivity, text: String) {
        val um = Msg(Role.USER, text)
        act.currentChat.messages.add(um)
        act.adapter.add(um)
        act.scrollToEnd()
    }

    private fun postReply(act: MainActivity, body: String) {
        val reply = Msg(Role.ASSISTANT, body)
        act.currentChat.messages.add(reply)
        act.adapter.add(reply)
        act.scrollToEnd()
        act.scope.launch(Dispatchers.IO) {
            try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { }
        }
    }

    private suspend fun postReplyOnMain(act: MainActivity, body: String) {
        withContext(Dispatchers.Main) { postReply(act, body) }
    }
}
