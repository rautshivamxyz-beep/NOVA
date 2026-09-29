package org.nova.ncie.android

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

/** v8.3.1: the model's parameter count, parsed from its label - the
 *  substring list kept missing new sizes ("1b" missed "1.2b" in v7.5.1,
 *  and a 1.5B model matched nothing at all), so every new download was
 *  a silent regression to the fat prompt stack. Anything up to 2B is a
 *  tiny model that drowns in stacked instructions; a label with no
 *  parseable size (a custom file name) stays a big model, as before. */
private val MODEL_PARAMS = Regex("(\\d+(?:\\.\\d+)?)\\s*b\\b")

/**
 * NCIE Stage 5 (#1): the collapsed send(). The chat turn's entire routing
 * body - pre-model gates (calculator, phone commands, model-ready), the
 * notes branches (raw document paste, stored-notes paste, whole-chapter
 * summary, quiz cards), transcript-echo recovery, and the full prompt
 * assembly (document window, compaction carry, memory, exam line, notes
 * RAG with the tiny-model caps, offline wiki) - moved VERBATIM from
 * MainActivity into the kernel's android layer as an extension on
 * MainActivity.
 *
 * What changed and what did not:
 *  - the body is the same code, statement for statement; the only edit is
 *    the three `this@MainActivity` labels, which became the captured
 *    `act` (an extension function reaches its receiver as `this`, and
 *    the coroutines lambdas inside capture it here)
 *  - MainActivity keeps a thin `send()` that calls [ncieSend];
 *    startGeneration (with the NovaEngineAdapter streaming turn), the
 *    summarize functions and everything else stay where they were
 *  - the members this routing reads and writes are `internal` instead
 *    of `private` - same Gradle module, zero behavior change
 *  - the app's tuned caps and template strings are untouched: this is a
 *    relocation, not a redesign. send() went from 391 lines to a stub;
 *    the intelligence now lives behind the NCIE boundary.
 */
fun MainActivity.ncieSend(raw: String? = null, offered: Boolean = false) {
    val act = this
    // v8.2.0: per-turn grounding sources — filled when the notes are
    // assembled below, consumed by the LEARN record at turn completion
    lastAnswerSources = emptyList()

        pendingCitation = null
        pendingQaKey = null
        pendingAnswerQ = null
        if (generationJob?.isActive == true) {
            generationJob?.cancel()
            return
        }
        val text = (raw ?: input.text.toString()).trim()
        if (text.isEmpty()) return
        val isChip = CHIP_PROMPTS.contains(text)
        // v8.9.0: Tutor Mode's stateful continuation - while a quiz
        // answer, a flashcard flip or a right/wrong verdict is pending,
        // the user's next message belongs to that session BEFORE any
        // other route (this is the one intercept that outranks all the
        // others, including the calculator below).
        if (NcieTutor.pendingAnswer(currentChat.id)) {
            input.setText("")
            if (NcieTutor.continueSession(this, text)) return
        }
        // v7.4: no-model tools FIRST - calculator and phone commands work
        // even before any model is downloaded
        if (solveArithmetic(text)) { input.setText(""); return }
        if (tryPhoneCommand(text)) { input.setText(""); return }
        // v8.5.0: the profile tool - "what do you know about me" is
        // answered from the graded memory, deterministically. The 1.5B
        // model used to hallucinate a personality from wiki background;
        // this question never reaches the model now.
        if (answerProfile(text)) { input.setText(""); return }
        // v8.8.0: the notification questions - "what did I miss" and
        // "any messages from X" are answered from NovaListener's local
        // log, deterministically, without the model. Nothing leaves the
        // phone, and they work with no model loaded at all.
        if (answerMissed(text)) { input.setText(""); return }
        if (answerFrom(text)) { input.setText(""); return }
        // v9.3.0 "Audit Fixes I": notification log privacy commands -
        // clear / pause / resume, deterministic like MISSED_Q
        if (answerNotifCmd(text)) { input.setText(""); return }
        // v8.9.0: Tutor Mode - quiz me on X with two-pass LLM answer
        // checking, weak areas with 1-day spaced repetition, flashcards.
        // The deterministic paths (weak-area list, flashcard storage and
        // the flip drill) never touch the model; question writing and
        // answer grading run through the in-app engine - nothing leaves
        // the phone for this feature.
        val tutorTopic = TUTOR_QUIZ.find(text)
        if (tutorTopic != null && text.length <= 120) {
            input.setText("")
            NcieTutor.startQuiz(this, text, tutorTopic.groupValues[1].trim())
            return
        }
        if (TUTOR_STUDY.containsMatchIn(text)) {
            input.setText("")
            NcieTutor.startStudy(this, text)
            return
        }
        if (TUTOR_WEAK.containsMatchIn(text)) {
            input.setText("")
            NcieTutor.weakAreas(this, text)
            return
        }
        val flashRest = FLASH_ADD.find(text)
        if (flashRest != null && text.length <= 120) {
            input.setText("")
            NcieTutor.addFlashcard(this, text, flashRest.groupValues[1].trim())
            return
        }
        if (FLASH_LIST.containsMatchIn(text)) {
            input.setText("")
            NcieTutor.flashcardsList(this, text)
            return
        }
        if (FLASH_QUIZ.containsMatchIn(text)) {
            input.setText("")
            NcieTutor.startFlashQuiz(this, text)
            return
        }
        // v9.1.0 "Automation I": deterministic routines - the morning
        // briefing (date, battery, notifications, weak areas, flashcards)
        // and the 45-minute study timer. No model call, no network, and
        // they work before any model is loaded.
        if (answerBriefing(text)) { input.setText(""); return }
        if (answerStudy(text)) { input.setText(""); return }
        // v9.2.0 "Rolling Chat Summary": two deterministic commands over
        // filesDir/chat_summary.txt - "summarize our conversation" reads
        // it, "forget our conversation" deletes it and clears the
        // history. Like the routines above: no model call, no network,
        // they work before any model is loaded at all.
        if (answerSummary(text)) { input.setText(""); return }
        if (answerForget(text)) { input.setText(""); return }
        // v9.5.0 "Document Grounding": "from my notes: <question>" -
        // strict answers from the user's own material, and "my
        // documents" - the inventory of it. Both run before the
        // seek/fetch offer below; the normal docPart injection logic
        // further down stays untouched - this is a separate explicit
        // mode the user invokes.
        if (answerGrounded(text)) { input.setText(""); return }
        if (answerDocsList(text)) { input.setText(""); return }
        // v9.6.0 "Engine Pack": "what have you learned" - the Experience
        // Engine's deterministic view of the questions whose answers the
        // user regenerated; "connections" / "related documents" - the
        // Knowledge Graph's deterministic cross-document map. Both are
        // file work only, like the commands above.
        if (answerLearned(text)) { input.setText(""); return }
        if (answerConnections(text)) { input.setText(""); return }
        // v9.7.0 "Quiet Fetch": the manual online lookup - "look it up
        // online: <q>" (also "look it up online <q>" / "search online for
        // <q>"; the bare form re-runs the last question). The quiet
        // offer below only fires when NOTHING local backs the question
        // now, so this command is the always-available way online.
        val onlineQ = onlineLookupQ(text)
        if (onlineQ != null) {
            input.setText("")
            if (onlineQ.isEmpty()) {
                toast("Add your question - look it up online: <question>")
            } else {
                offerOnlineFetch(act, onlineQ)
            }
            return
        }
        // v7.6: keep the typed text when we are NOT proceeding - it was
        // cleared here before, losing messages during compaction or when
        // no model is loaded yet
        if (!ensureModelReady()) return
        if (compacting) {
            toast("Compressing older messages — one moment")
            return
        }
        input.setText("")
        // v7.3: greetings get a clean tiny prompt - no notes/wiki/maths
        // wrapper, so the model chats instead of summarizing
        if (SMALLTALK_REGEX.containsMatchIn(text)) {
            val greetPrompt =
                "(The user said: '" + text + "' - greet them warmly in one or two " +
                    "short sentences and offer to help. Do not mention notes, documents, " +
                    "Wikipedia or summaries.)"
            // v7.6: a greeting sent into a dirty/stale context made the model
            // echo old strict-mode boilerplate ("From general knowledge...")
            // instead of saying hi. Reset first, then greet on a clean engine.
            if (NovaEngine.contextDirty || needsContextCarry) {
                needsContextCarry = false
                scope.launch {
                    NovaEngine.resetConversation(act, settings.systemPrompt)
                    startGeneration(greetPrompt, text, plain = true)
                }
            } else {
                startGeneration(greetPrompt, text, plain = true)
            }
            return
        }
        // v7.8.1: short self-introductions are chat, not study questions.
        // "im shivam" / "my name is shivam" used to fall through to the
        // notes + wiki RAG path, which dragged in junk background (an
        // article title could even match inside a word: "hiv" inside
        // "shivam") and the model answered with "From general knowledge..."
        // boilerplate instead of just saying hello.
        val intro = SELF_INTRO_REGEX.find(text)
        if (intro != null) {
            maybeRememberName(intro.groupValues[1].trim())
            val introPrompt =
                "(The user is introducing themselves: '" + text + "' - acknowledge it naturally " +
                    "in one or two short sentences, use their name, and offer to help. Do not " +
                    "mention notes, documents, Wikipedia or summaries.)"
            if (NovaEngine.contextDirty || needsContextCarry) {
                needsContextCarry = false
                scope.launch {
                    NovaEngine.resetConversation(act, settings.systemPrompt)
                    startGeneration(introPrompt, text, plain = true)
                }
            } else {
                startGeneration(introPrompt, text, plain = true)
            }
            return
        }
        // while reading aloud: "explain that sentence" asks about the last spoken one
        if (readIdx > 0 && Regex("(?i)explain (that|this|the last) (sentence|part|line)")
                .containsMatchIn(text)) {
            tts?.stop()
            runTool("Explain this sentence from the document in simple words, " +
                "with an example if helpful:\n\"${readSents[readIdx - 1]}\"")
            return
        }
        // "show/gimme the notes" - paste the raw document text, no model needed
        if (docContext != null) {
            val wantsRaw = Regex("(?i)\\b(show|gimme|give|send|paste|display|want)\\b[^.]*\\b(notes?|document|text|pdf)\\b")
                .containsMatchIn(text)
            val asksSummary = Regex("(?i)\\bsummar").containsMatchIn(text) &&
                !Regex("(?i)\\b(don'?t|do not|stop|no)\\b[^.]*\\bsummar").containsMatchIn(text)
            if (wantsRaw && !asksSummary &&
                !Regex("(?i)simpl|explain|quiz|points").containsMatchIn(text)) {
                val part = docSearch(text, 6000)
                val um = Msg(Role.USER, text)
                currentChat.messages.add(um)
                adapter.add(um)
                val reply = Msg(Role.ASSISTANT, "(from $docName)\n\n$part")
                currentChat.messages.add(reply)
                adapter.add(reply)
                scrollToEnd()
                scope.launch(Dispatchers.IO) {
                    try { ChatStore.save(act, currentChat) } catch (e: Exception) { }
                }
                return
            }
            // "summarise this" -> the full section-by-section summary with
            // live progress (one-shot only covered the first pages)
            if (asksSummary && !isChip) {
                summarizeDoc()
                return
            }
        }
        // "gimme the notes of federalism" - paste stored Knowledge notes,
        // even when no document is attached in this chat
        if (docContext == null && settings.knowledgeEnabled && NcieKnowledge.hasDocs(this)) {
            val wantsNotes = Regex("(?i)\\b(show|gimme|give|send|paste|display|want|read)\\b[^.]*\\b(notes?|material|answers?)\\b")
                .containsMatchIn(text) &&
                !Regex("(?i)\\bsummar|explain|simpl|quiz|points").containsMatchIn(text)
            if (wantsNotes) {
                // the chapter window around the best match - scattered
                // top-4 fragments used to mix chapters ("money and credit"
                // returned Great Depression text)
                val ndoc = NcieKnowledge.bestDocName(this, text)
                val parts = if (ndoc != null) NcieKnowledge.bestChunks(this, text, 10) else emptyList()
                if (parts.isNotEmpty()) {
                    val um = Msg(Role.USER, text)
                    currentChat.messages.add(um)
                    adapter.add(um)
                    var body = parts.joinToString("\n\n")
                    if (body.length > 6000) body = body.substring(0, 6000) + "\n[...more]"
                    val reply = Msg(Role.ASSISTANT, "(from $ndoc)\n\n$body")
                    currentChat.messages.add(reply)
                    adapter.add(reply)
                    scrollToEnd()
                    scope.launch(Dispatchers.IO) {
                        try { ChatStore.save(act, currentChat) } catch (e: Exception) { }
                    }
                    return
                }
                // nothing matched - list what notes exist so the user can name one
                val names = NcieKnowledge.docs(this).joinToString(", ") { it.first }
                if (names.isNotEmpty()) {
                    val um = Msg(Role.USER, text)
                    currentChat.messages.add(um)
                    adapter.add(um)
                    val reply = Msg(Role.ASSISTANT,
                        "I couldn't find notes on that. You have notes on: $names")
                    currentChat.messages.add(reply)
                    adapter.add(reply)
                    scrollToEnd()
                    return
                }
            }
        }
        // "summarise sst notes" / "gimme the whole summary" - summarize the
        // saved notes over the WHOLE chapter (map-reduce), clean engine
        if (docContext == null && settings.knowledgeEnabled && NcieKnowledge.hasDocs(this)) {
            val wantsSumm = Regex("(?i)\\bsummaris|\\bsummariz").containsMatchIn(text)
            val summNoun = text.lowercase().contains("summary")
            // v5.4.1: "teach me whole power sharing chapter" - the user wants
            // the WHOLE chapter as a study summary, not a 2400-char answer
            val wholeTeach = text.lowercase().contains("whole") &&
                (text.lowercase().contains("chapter") || text.lowercase().contains("notes"))
            val followUp = lastNotesDoc != null &&
                Regex("(?i)\\b(whole|full|complete|entire|detailed)\\s+summar").containsMatchIn(text)
            if (wantsSumm || followUp || summNoun || wholeTeach) {
                // relaxed match: ANY query term can point at the document -
                // requiring every word in one chunk made "summarise power
                // sharing" silently fall through to chat (and hallucinate)
                // "summarise it notes" - "it" means the IT notes here,
                // not the pronoun the tokenizer throws away
                val qtext = text.replace(" it notes", " IT Revision notes", ignoreCase = true)
                val doc = if (wantsSumm || summNoun || wholeTeach) NcieKnowledge.bestDocName(this, qtext)
                          else lastNotesDoc
                if (doc != null) {
                    lastNotesDoc = doc
                    // "summarise sst notes" NAMES the document -> the user
                    // wants the whole doc, not just the first 18 chunks
                    val whole = wholeTeach || !wantsSumm || NcieKnowledge.nameOnlyQuery(qtext, doc)
                    summarizeNotes(doc, text, fullDoc = whole)
                    return
                }
                // nothing matched - NEVER fall back to guessing from chat:
                // list what notes exist so the user can name one
                val names = NcieKnowledge.docs(this).joinToString(", ") { it.first }
                if (names.isNotEmpty()) {
                    val um = Msg(Role.USER, text)
                    currentChat.messages.add(um)
                    adapter.add(um)
                    val reply = Msg(Role.ASSISTANT,
                        "I couldn't find notes on that. You have notes on: $names")
                    currentChat.messages.add(reply)
                    adapter.add(reply)
                    scrollToEnd()
                    return
                }
            }
        }
        // v5.4.7: "quiz me on power sharing" - study flashcards straight
        // from the notes, reusing the study-card machinery and its Q:/A:
        // parser, so a quiz is graded material you already verified
        if (docContext == null && settings.knowledgeEnabled && NcieKnowledge.hasDocs(this)) {
            val quizMe = Regex("(?i)\\b(?:quiz|test) me on\\b").find(text)
            if (quizMe != null) {
                val topic = text.substringAfter(quizMe.value).trim()
                val parts = NcieKnowledge.bestChunks(this, if (topic.length > 2) topic else text, 10)
                if (parts.isNotEmpty()) {
                    val um = Msg(Role.USER, text)
                    currentChat.messages.add(um); adapter.add(um)
                    pendingCards = true
                    val mat = parts.joinToString("\n")
                    startGeneration("(Create 8 study flashcards from this material. " +
                        "Format each card EXACTLY as:\nQ: <question>\nA: <answer>\n" +
                        "No numbering, no text before or after.\n-----\n$mat\n-----)", null)
                    scrollToEnd()
                    return
                }
            }
        }
        // v8.5.3: "im in class 10, lives in ..., studies at ..." - offer
        // to keep the self-description before answering the request
        maybeRememberFacts(text)
        maybeAutoRemember(text)
        maybeSetReminder(text)

        // recover from transcript-echo poisoning: if NOVA's last reply came
        // out as a transcript ("NOVA: ..."), reset the engine so it answers fresh
        val lastReply = currentChat.messages.lastOrNull { it.role == Role.ASSISTANT }
        if (lastReply != null && Regex("(?m)^\\s*(?:NOVA|You)\\s*:").containsMatchIn(lastReply.text)) {
            needsContextCarry = true
            if (NovaEngine.isModelLoaded) NovaEngine.resetConversationAsync(this, settings.systemPrompt)
        }

        val docPart = if (docContext != null) {
            val win = docSearch(text)
            val qWords = text.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length > 2 && it !in docStop }
            val overlap = qWords.count { it in win.lowercase() }
            // user explicitly off the document ("don't search the notes")
            val offDoc = Regex("(?i)\\b(?:don'?t|do not|stop)\\b[^.]*\\b(?:use|search|look)\\b[^.]*\\b(?:notes?|document|pdf|it)\\b|\\bfrom your own knowledge\\b|\\bwithout the (?:notes?|document)\\b")
                .containsMatchIn(text)
            when {
                offDoc -> {
                    docInjected = false
                    "(The document restriction from earlier is lifted - answer from your own knowledge.)\n\n"
                }
                overlap == 0 -> {
                    // question has nothing to do with the document: don't
                    // re-inject it, and lift any earlier restriction so
                    // general questions ("who is X?") still get answered
                    if (docInjected) {
                        docInjected = false
                        "(The document restriction from earlier is lifted - answer from your own knowledge.)\n\n"
                    } else ""
                }
                else -> {
                    docInjected = true
                    docInjectedText = win
                    "(The user shared a document titled \"$docName\". Its content is between the lines. Answer ONLY using this document; if the answer is not in it, say so honestly.\n-----\n$win\n-----\nEnd of document.)\n\n"
                }
            }
        } else ""
        val basePrompt: String = docPart + when {
            needsContextCarry && compactSummary != null && currentChat.messages.isNotEmpty() -> {
                val recent = currentChat.messages.takeLast(6).joinToString("\n") { m ->
                    (if (m.role == Role.USER) "You: " else "NOVA: ") + m.text.take(250)
                }
                "(Summary of earlier conversation: $compactSummary)\n\n(Recent messages:\n$recent\n— end)\n\nNew message: $text\n(Reply to the new message directly, even if it starts a completely new topic. Do not repeat the transcript.)"
            }
            needsContextCarry && currentChat.messages.isNotEmpty() -> {
                val recent = currentChat.messages.takeLast(6).joinToString("\n") { m ->
                    (if (m.role == Role.USER) "You: " else "NOVA: ") + m.text.take(250)
                }
                "(Earlier conversation for context:\n$recent\n— end of earlier conversation)\n\nNew message: $text\n(Reply to the new message directly, even if it starts a completely new topic. Do not repeat the transcript.)"
            }
            else -> text
        }

        var prompt = basePrompt
        // Memory rides along in the engine's context, so it only needs to be
        // injected once per conversation (or when its text changes).
        val mem = settings.memory.trim().take(500)
        if (mem.isNotEmpty() && (
                    currentChat.messages.isEmpty() || needsContextCarry || mem != lastInjectedMemory
                    )) {
            prompt = "(Facts about the user, always remember: $mem)\n\n$basePrompt"
            lastInjectedMemory = mem
        }
        // tiny models (Llama 3.2 1B) drown in stacked instructions - they
        // get ONE background source, no exam line, and short injections
        val mlabel = NovaEngine.activeModelLabel.lowercase()
        // v8.3.1: parse the size out of the label instead of matching
        // strings by hand - covers 1.5B, 1.7B and 2B the list never knew
        val tiny = MODEL_PARAMS.find(mlabel)?.let { it.groupValues[1].toDouble() <= 2.0 } ?: false

        // NCIE Stage 6 (#1): the kernel's context budget now picks the
        // injection profile. Plain short chat gets the lean caps (the
        // same profile tiny models get); knowledge-seeking turns keep
        // the full ones. The numbers themselves are unchanged - only who
        // decides. Tiny models always stay lean regardless.
        val lean = tiny || NcieKnowledge.leanContext(text)

        if (!tiny) {
            // exam countdown awareness - injected once per conversation,
            // re-injected only when the day changes the countdown text
            val examLine = Exams.promptLine(this)
            if (examLine != null && (currentChat.messages.isEmpty() || needsContextCarry ||
                    examLine != lastInjectedExamKey)) {
                prompt = "(The user's upcoming exams: $examLine.)\n\n$prompt"
                lastInjectedExamKey = examLine
            }
        }

        // knowledge base (offline RAG): relevant notes from the user's documents
        var knowledgePart = ""
        var hits: List<Knowledge.Chunk> = emptyList()
        // v5.4: study questions get STRICT grounding + citation + cache
        val qLow = text.lowercase()
        val studyQ = qLow.startsWith("explain ") || qLow.startsWith("teach me ") ||
            qLow.startsWith("what is ") || qLow.startsWith("what are ") ||
            qLow.startsWith("who is ") || qLow.startsWith("who was ") ||
            qLow.startsWith("define ") || qLow.startsWith("describe ") ||
            qLow.startsWith("tell me about ") || qLow.contains(" explain ") ||
            qLow.contains(" teach me ") || qLow.contains(" what is ")
        if (settings.knowledgeEnabled && NcieKnowledge.hasDocs(this)) {
            // v5.4: cached answer from last time? -> instant, no model run
            if (studyQ && !isChip && docPart.isEmpty()) {
                val qaKey = "qa_" + Integer.toHexString(qLow.hashCode()) + "_" +
                    Integer.toHexString(NovaEngine.activeModelLabel.hashCode())
                val qaFile = File(File(filesDir, "summary_cache").apply { mkdirs() }, qaKey)
                val qaCached = if (qaFile.exists())
                    try { qaFile.readText() } catch (e: Exception) { "" } else ""
                if (qaCached.length > 30) {
                    val um = Msg(Role.USER, text)
                    currentChat.messages.add(um); adapter.add(um)
                    val cachedReply = Msg(Role.ASSISTANT, qaCached)
                    currentChat.messages.add(cachedReply); adapter.add(cachedReply)
                    scrollToEnd()
                    toast("Answer (cached from last time)")
                    try { ChatStore.save(this, currentChat) } catch (e: Exception) { }
                    // v5.4.3 fix: the engine never saw this exchange - make
                    // the next real question carry the transcript, so
                    // follow-ups ("explain that again") aren't answered cold
                    // v5.4.6: a cached reply skipped the note search above,
                    // so a follow-up ("explain it more") had no notes to
                    // carry. Remember them now, like a fresh answer would.
                    val h2 = NcieKnowledge.search(this, text)
                    if (h2.isNotEmpty()) { lastNotesHit = h2; lastNotesChatId = currentChat.id }
                    needsContextCarry = true
                    return
                }
                pendingQaKey = qaKey
            }
            hits = NcieKnowledge.search(this, text)
            // v7.6: relevance gate - one shared word (e.g. just "bose")
            // matched junk notes and the model answered from them with a
            // confident-looking citation. The gate (significant query
            // terms must appear in the matched chunks) now runs inside
            // the kernel - see NcieKnowledge.search.
            // v5.4.5: follow-up questions ("explain it in more detail",
            // "explain that again") carry no keywords of their own, so the
            // search comes back empty and the model answered from memory -
            // mixing subjects (SST facts inside an English answer). Carry
            // the notes that fed the previous answer in this chat instead.
            if (hits.isEmpty() && docPart.isEmpty() && lastNotesHit.isNotEmpty() &&
                lastNotesChatId == currentChat.id && FOLLOW_UP_Q.containsMatchIn(text)) {
                hits = lastNotesHit
            }
            if (hits.isNotEmpty()) {
                lastNotesHit = hits
                lastNotesChatId = currentChat.id
                var notes = hits.joinToString("\n---\n") { "[${it.doc}] ${it.text}" }
                if (notes.length > (if (lean) 1200 else 2400))
                    notes = notes.substring(0, if (lean) 1200 else 2400) + "\n[...more omitted]"
                knowledgePart = "(Relevant notes from the user's documents - use them ONLY if they clearly help answer this exact request; if they do not, ignore them completely and answer normally:\n$notes)\n\n"
            }
        }
        // offline Wikipedia: matching articles as background facts
        var wikiPart = ""
        // v8.5.0: wiki background only on knowledge-seeking turns. Chat
        // and personal questions used to pull in junk articles ("what you
        // know about me" matched random titles) and the model answered
        // with that noise - the screenshot bug.
        val seek = studyQ || text.contains("?") ||
            Regex("(?i)^(?:how|why|when|where|which|who|what|does|do|is|are|can|define|describe|compare)\\b")
                .containsMatchIn(text)
        if (settings.wikiEnabled && seek && WikiCore.isReady(this)) {
            val wikiHits = WikiCore.search(this, text, if (tiny) 1 else 2)
            // v8.5.3: the same Coverage gate that guards fetched articles
            // now guards wiki background. "Teach me footprints without feet
            // chapter 1" matched a FOOTPRINT article, the model invented a
            // lesson from it, and the junk match even blocked the online
            // fetch offer. Background that does not cover the question is
            // dropped, not injected.
            if (wikiHits.isNotEmpty() &&
                Coverage.ratio(text, wikiHits.joinToString(" ") { it.text }) >= 0.3) {
                var facts = wikiHits.joinToString("\n---\n") { "${it.title}: ${it.text}" }
                // v7.5: wiki is background only - halve it so the model reads
                // less before the first word; notes (the quality driver) stay
                val cap = if (lean) 900 else 1200
                if (facts.length > cap) facts = facts.substring(0, cap) + "…"
                wikiPart = "(Wikipedia background - use it to answer, ignore if not relevant:\n$facts)\n\n"
            }
        }
        // v5.4: study questions - rewrap the notes as STRICT instructions,
        // record the source pages, and let the notes be the only background
        if (studyQ && docPart.isEmpty() && knowledgePart.isNotEmpty()) {
            var notes2 = hits.joinToString(NL + "---" + NL) { "[" + it.doc + "] " + it.text }
            if (notes2.length > (if (lean) 1200 else 2400))
                notes2 = notes2.substring(0, if (lean) 1200 else 2400)
            knowledgePart = "(Study notes from the user's documents follow. " +
                "Answer ONLY using these notes. If the answer is not in the " +
                "notes, say plainly that the notes do not cover it. Copy key " +
                "terms and facts exactly as written. Be direct and complete, " +
                "never pad: no filler like 'the story is often seen as', no " +
                "repeating the question, no repeating the same idea twice." +
                (if (qLow.startsWith("teach me "))
                    " Teach the topic fully from the notes, definition first."
                 else " Answer in at most 120 words unless the user asks for detail.") +
                NL + notes2 + ")" + NL + NL
            wikiPart = ""
            var pages = ""
            for (l in notes2.lines()) {
                val t2 = l.trim()
                if (t2 == "---") break
                if (t2.length < 22 && t2.contains("page ")) {
                    val d = t2.filter { it.isDigit() }
                    if (d.isNotEmpty() && !pages.contains(d)) {
                        if (pages.isNotEmpty()) pages += ", "
                        pages += d
                    }
                }
            }
            pendingCitation = if (pages.isEmpty()) "" else
                "Source: " + hits.first().doc + ", " +
                (if (pages.contains(",")) "pages " else "page ") + pages
        }
        // v8.4.0 (stage 3): skills as data - a matched skill rewraps the
        // prompt with its instruction; the notes above stay the grounding
        var skillPart = ""
        val skill = NcieSkills.match(text)
        if (skill != null) {
            lastSkillMatched = skill.name
            skillPart = "(" + skill.render(text) + ")\n\n"
        } else lastSkillMatched = null
        // v9.2.0 "Rolling Chat Summary": the folded start of the
        // conversation rides along as a preamble on every normal turn,
        // so a long chat never loses its beginning. Skills keep their
        // own instruction wrapper; the docPart logic above is untouched.
        if (skill == null) {
            val chatSumm = NcieSummary.read(this, currentChat.id)
            if (chatSumm != null) prompt = "Conversation so far (summary): $chatSumm\n\n" + prompt
        }
        // v8.5.3: a study question with NOTHING local behind it must not be
        // answered from imagination - the model invented chapter contents
        // and even a TV-series biography for the user's own name. Be
        // honest, answer only what is certain, point at the real options.
        if (studyQ && docPart.isEmpty() && knowledgePart.isEmpty() &&
            wikiPart.isEmpty() && !offered) {
            prompt = "(The user asks a study question that is not in their notes, " +
                "and your background does not cover it. Say plainly that you do " +
                "not have their notes on this. If you actually know the topic, " +
                "give a short overview and say it is from general knowledge. " +
                "Never invent chapter contents, page details or facts. Never " +
                "write 'From general knowledge' as a prefix - just answer. " +
                "Suggest adding the chapter to Knowledge, or looking it up " +
                "online if that is allowed.)\n\n" + prompt
        }
        // one background source for tiny models, both for bigger ones
        prompt = skillPart + (if (tiny) (if (knowledgePart.isNotEmpty()) knowledgePart else wikiPart)
                  else knowledgePart + wikiPart) + prompt

        // v8.2.0: the notes that grounded THIS answer — handed to LEARN
        // so the quality gate scores the answer against what it was built
        // from. Wiki stays out: its prompt says "ignore if not relevant",
        // so a chatty reply that ignored background facts is CORRECT and
        // must not be punished as drift.
        lastAnswerSources = hits.map { it.text }
        // v8.6.0: online discovery for ANY information question - the
        // user asked for it. Local sources are no longer a blocker, they
        // are the alternative: the offer says what is covered and what
        // looking it up would add. Skills and document mode stay
        // offline-only, and ask-first stays the law.
        // v9.7.0 "Quiet Fetch" (user request: "for online fetching
        // don't show everytime"): the offer dialog now appears ONLY when
        // NO local material backs the question - docPart empty, no notes
        // hits, no wiki coverage. When notes or wiki already cover it,
        // NOVA answers locally with NO dialog at all; "look it up
        // online" is the manual way in.
        if (settings.onlineLearning && !offered && seek && docPart.isEmpty() &&
            lastSkillMatched == null && WikiCore.isReady(this) &&
            knowledgePart.isEmpty() && wikiPart.isEmpty()
        ) {
            offerOnlineFetch(act, text)
            return
        }
        // v5.4.7: show when the answer is grounded in the user's notes
        if (knowledgePart.isNotEmpty()) toast("Using your notes")
        // v9.6.0 "Engine Pack": the Experience Engine speaks first - a
        // question whose answer the user regenerated gets the
        // better-answer instruction, and never touches the answer cache
        // while it stays logged (so the engine keeps informing the next
        // answer instead of the cache replaying the old one).
        val experienced = NcieEngines.isExperienced(this, text)
        if (experienced) {
            prompt = "(The user previously asked this and was not satisfied " +
                "with the answer. Try a better, clearer answer this time.)\n\n" + prompt
        } else if (skill == null) {
            // v9.6.0: the Predictive Cache - an exact repeat of a normal
            // chat question is answered instantly, verbatim, before any
            // generation. Regenerating bypasses this and refreshes the
            // entry; commands, skills and grounded answers never reach
            // this point.
            val hit = NcieEngines.cachedAnswer(this, text)
            if (hit != null) {
                val um = Msg(Role.USER, text)
                currentChat.messages.add(um); adapter.add(um)
                val reply = Msg(Role.ASSISTANT, hit)
                currentChat.messages.add(reply); adapter.add(reply)
                scrollToEnd()
                toast("Answer (cached - regenerate for a fresh one)")
                scope.launch(Dispatchers.IO) {
                    try { ChatStore.save(act, currentChat) } catch (e: Exception) { }
                }
                needsContextCarry = true
                return
            }
        }
        // NCIE v0.7.0 (#1): Smart Skip - the kernel's LEARN phase now has a
        // disk-backed cache. An exact repeat of an already-answered model
        // turn is served instantly, zero tokens - the study-Q cache's
        // idea, generalized to every turn by the kernel.
        if (NcieLearn.recall(this, text)) return
        autoContinueCount = 0
        replyRetried = false
        // v9.6.0: the Predictive Cache write happens at turn completion
        // (MainActivity) under this exact question. Experienced questions
        // and skills never enter the cache.
        pendingAnswerQ = if (experienced || skill != null) null else text
        // v9.2.0 "Rolling Chat Summary": once 12 user messages (and
        // their replies) have completed since the stored summary, fold
        // the conversation into filesDir/chat_summary.txt through the
        // local engine - Dispatchers.IO, NcieTutor's generate pattern.
        // The reply to the 12th message is complete the moment the
        // user sends the next one, which is exactly when this fires.
        // The roll finishes BEFORE this turn's generation starts (they
        // share the native engine), and the `compacting` gate keeps a
        // second send out while it runs, exactly like auto-compaction.
        if (NcieSummary.due(this, currentChat.id)) {
            val turnPrompt = prompt
            val chatAtRoll = currentChat
            val snap = ArrayList(chatAtRoll.messages)
            compacting = true
            scope.launch {
                val rolled = withContext(Dispatchers.IO) { NcieSummary.roll(act, chatAtRoll.id, snap) }
                if (rolled) NcieSummary.trimHistory(act, chatAtRoll)
                NcieSummary.reset(act, chatAtRoll.id)
                NcieSummary.bump(act, chatAtRoll.id)
                compacting = false
                startGeneration(turnPrompt, text)
            }
        } else {
            NcieSummary.bump(this, currentChat.id)
            startGeneration(prompt, text)
        }
    }

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
private fun MainActivity.maybeRememberName(name: String) {
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
private val FACT_CLASS = Regex("(?i)\\bclass\\s+(\\d{1,2})\\b")
private val FACT_CITY = Regex("(?i)\\b(?:lives?|living)\\s+in\\s+([a-z]{2,}(?:\\s+[a-z]{2,}){0,2})")
private val FACT_SCHOOL = Regex("(?i)\\b(?:stud(?:y|ies|ying)|reads?)\\s+(?:in|at)\\s+([a-z0-9' ]{4,60}?(?:school|college|academy|vidyalaya|institute))")

private fun MainActivity.maybeRememberFacts(text: String) {
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
private val MISSED_Q = Regex(
    "(?i)\\bwhat\\s+did\\s+i\\s+miss\\b|\\bany\\s+notifications\\b" +
        "|\\b(?:read|check)\\s+my\\s+notifications\\b|\\bnotification\\s+summary\\b")

private fun MainActivity.answerMissed(text: String): Boolean {
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
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { }
    }
    return true
}

// v9.3.0 "Audit Fixes I": notification log privacy commands - "clear my
// notifications" / "pause notifications" / "resume notifications",
// whole-message and short like the MISSED_Q commands above. All three
// are deterministic: a file wipe, a flag create, a flag delete - no
// model, no network, nothing leaves the phone. While paused, NovaListener
// writes nothing at all; the 7-day purge runs inside append.
private val NOTIF_CLEAR_Q = Regex(
    "(?i)^\\s*clear\\s+my\\s+notifications?\\s*[.!?]*\\s*$")
private val NOTIF_PAUSE_Q = Regex(
    "(?i)^\\s*pause\\s+notifications?\\s*[.!?]*\\s*$")
private val NOTIF_RESUME_Q = Regex(
    "(?i)^\\s*resume\\s+notifications?\\s*[.!?]*\\s*$")

private fun MainActivity.answerNotifCmd(text: String): Boolean {
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
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { }
    }
    return true
}

// v8.9.0: Tutor Mode's entry phrases. Anchored short commands (study,
// weak areas, flashcards) so OCR'd chapter pastes never fall into them;
// quiz-me-on keeps the open topic capture but is length-gated in the
// routing above.
private val TUTOR_QUIZ = Regex("(?i)\\b(?:quiz|test)\\s+me\\s+on\\s+(.{2,80})")
private val TUTOR_STUDY = Regex("(?i)^\\s*study(?:\\s+my\\s+weak\\s+areas)?\\s*[.!?]*\\s*$")
private val TUTOR_WEAK = Regex(
    "(?i)^\\s*(?:my\\s+weak\\s+areas|what\\s+am\\s+i\\s+weak\\s+in|weak\\s+areas)\\s*[.!?]*\\s*$")
private val FLASH_ADD = Regex("(?i)^\\s*add\\s+flashcards?\\b\\s*(.*)$")
private val FLASH_LIST = Regex("(?i)^\\s*my\\s+flashcards\\s*[.!?]*\\s*$")
private val FLASH_QUIZ = Regex("(?i)^\\s*(?:quiz\\s+my\\s+flashcards|flashcards)\\s*[.!?]*\\s*$")

// v8.8.0: "any messages from X" / "did X message me" / "anything from
// X" - a deterministic search of NovaListener's log for X (the one to
// four words after the pattern) in every title and text.
private val FROM_Q = Regex(
    "(?i)\\bany\\s+messages?\\s+from\\s+([a-z0-9']+(?:\\s+[a-z0-9']+){0,3})" +
        "|\\bdid\\s+([a-z0-9']+(?:\\s+[a-z0-9']+){0,3})\\s+message\\s+me\\b" +
        "|\\bmessage\\s+from\\s+([a-z0-9']+(?:\\s+[a-z0-9']+){0,3})" +
        "|\\banything\\s+from\\s+([a-z0-9']+(?:\\s+[a-z0-9']+){0,3})")

private fun MainActivity.answerFrom(text: String): Boolean {
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
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { }
    }
    return true
}

// v9.1.0 "Automation I": "good morning" / "morning briefing" / "briefing"
// / "my briefing" - the deterministic morning briefing from local state
// (date, battery, notifications since midnight, weak areas due,
// flashcards). Like MISSED_Q: no model call, no network, nothing leaves
// the phone, and it works with no model loaded at all.
private val BRIEFING_Q = Regex(
    "(?i)^\\s*(?:good\\s+morning|morning\\s+briefing|my\\s+briefing|briefing)\\s*[.!?]*\\s*$")

private fun MainActivity.answerBriefing(text: String): Boolean {
    if (text.length > 80 || !BRIEFING_Q.containsMatchIn(text)) return false
    val activity = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    val routReply = Msg(Role.ASSISTANT, NcieRoutines.morningBriefing(this))
    currentChat.messages.add(routReply); adapter.add(routReply); scrollToEnd()
    activity.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { }
    }
    return true
}

// v9.1.0: "start study" / "start studying" / "study time" - a 45-minute
// timer through the SAME clock-app path the phone commands use
// (MainActivity.startClockTimer, the ACTION_SET_TIMER route).
private val STUDY_Q = Regex(
    "(?i)^\\s*(?:start\\s+study(?:ing)?|study\\s+time)\\s*[.!?]*\\s*$")

private fun MainActivity.answerStudy(text: String): Boolean {
    if (text.length > 80 || !STUDY_Q.containsMatchIn(text)) return false
    val activity = this
    val um = Msg(Role.USER, text)
    currentChat.messages.add(um); adapter.add(um); scrollToEnd()
    val routReply = Msg(Role.ASSISTANT, NcieRoutines.startStudy(this, this))
    currentChat.messages.add(routReply); adapter.add(routReply); scrollToEnd()
    activity.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { }
    }
    return true
}

// v9.2.0 "Rolling Chat Summary": "summarize our conversation" / "what
// were we talking about" / "conversation summary" - the rolling summary
// read straight from filesDir/chat_summary.txt, deterministically.
// Like BRIEFING_Q: no model call, no network, works with no model
// loaded at all.
private val SUMMARY_Q = Regex(
    "(?i)^\\s*(?:summarize|summarise)\\s+our\\s+conversation\\s*[.!?]*\\s*$" +
        "|\\bwhat\\s+were\\s+we\\s+talking\\s+about\\b" +
        "|\\bconversation\\s+summary\\b")

private fun MainActivity.answerSummary(text: String): Boolean {
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
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { }
    }
    return true
}

// v9.2.0: "forget our conversation" / "forget what we talked about" -
// deletes filesDir/chat_summary.txt and its counter and clears the
// history the way the app's own new-conversation action does. Like
// BRIEFING_Q: no model call, no network.
private val FORGET_Q = Regex(
    "(?i)^\\s*forget\\s+(?:our\\s+conversation|what\\s+we\\s+talked\\s+about)\\s*[.!?]*\\s*$")

private fun MainActivity.answerForget(text: String): Boolean {
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
        try { ChatStore.save(activity, currentChat) } catch (e: Exception) { }
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
private val GROUND_Q = Regex(
    "(?i)^\\s*from\\s+my\\s+(?:notes?|documents?)\\b\\s*:?\\s*(.*)$")

private fun postGroundReply(act: MainActivity, body: String) {
    val groundMsg = Msg(Role.ASSISTANT, body)
    act.currentChat.messages.add(groundMsg); act.adapter.add(groundMsg); act.scrollToEnd()
    act.scope.launch(Dispatchers.IO) {
        try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { }
    }
}

private fun MainActivity.answerGrounded(text: String): Boolean {
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
        val groundReply = NovaEngineAdapter.generate(
            NcieGround.groundPrompt(question, chunks), activity.settings.predictLength)
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
private val DOCS_Q = Regex(
    "(?i)^\\s*(?:what\\s+documents\\s+do\\s+i\\s+have|my\\s+documents|" +
        "list\\s+my\\s+documents)\\s*[.!?]*\\s*$")

private fun MainActivity.answerDocsList(text: String): Boolean {
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
private fun groundDocsBody(ctx: android.content.Context): String {
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
        try { WikiCore.warmUp(ctx) } catch (e: Exception) { }
        for (t in wiki) {
            val len = try { WikiCore.articleText(ctx, t)?.length ?: 0 } catch (e: Exception) { 0 }
            sb.append("  ").append(t).append(" — ").append(len).append(" chars\n")
        }
    }
    sb.append("```")
    return sb.toString()
}

// v9.6.0 "Engine Pack": "what have you learned" - the Experience Engine's
// deterministic answer: the last 20 questions whose answers the user
// regenerated (filesDir/experience.txt), monospace. Like DOCS_Q above: no
// model call, no network, works with no model loaded at all.
private val LEARNED_Q = Regex("(?i)^\\s*what\\s+have\\s+you\\s+learned\\s*[.!?]*\\s*$")

private fun MainActivity.answerLearned(text: String): Boolean {
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
private val CONNECT_Q = Regex("(?i)^\\s*(?:connections?|related\\s+documents?)\\s*[.!?]*\\s*$")

private fun MainActivity.answerConnections(text: String): Boolean {
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

/** v9.7.0 "Quiet Fetch": the single entry into the online fetch flow.
 *  Both the quiet offer (only when NO local material backs the question)
 *  and the manual "look it up online" command route through here, so the
 *  fetch stays ask-first (OnlineFetch's dialog) and the call site stays
 *  single. */
private fun offerOnlineFetch(act: MainActivity, text: String) {
    OnlineFetch.offer(act, text, false)
}

/** v9.7.0 "Quiet Fetch": parse the manual online-lookup command.
 *  "look it up online: <q>" / "look it up online <q>" /
 *  "search online for <q>" -> q; the bare "look it up online" -> the
 *  last question in this chat ("" when there is none, so the caller can
 *  explain itself). Null = not the command at all. */
private fun MainActivity.onlineLookupQ(text: String): String? {
    val lower = text.lowercase()
    val prefix = when {
        lower == "look it up online" -> 0
        lower.startsWith("look it up online") -> "look it up online".length
        lower.startsWith("search online for") -> "search online for".length
        else -> return null
    }
    if (prefix == 0) {
        val last = currentChat.messages.lastOrNull { it.role == Role.USER }?.text
        return last?.trim() ?: ""
    }
    var q = text.substring(prefix).trim(' ', ':')
    if (q.lowercase().startsWith("for ")) q = q.substring(4)
    return q.trim()
}
