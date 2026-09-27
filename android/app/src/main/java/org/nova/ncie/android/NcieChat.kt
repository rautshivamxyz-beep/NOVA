package org.nova.ncie.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.nova.CHIP_PROMPTS
import org.nova.ChatStore
import org.nova.docStop
import org.nova.Exams
import org.nova.FOLLOW_UP_Q
import org.nova.Knowledge
import org.nova.MainActivity
import org.nova.Msg
import org.nova.NovaEngine
import org.nova.Role
import org.nova.SMALLTALK_REGEX
import org.nova.WikiCore
import java.io.File

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
fun MainActivity.ncieSend() {
    val act = this

        pendingCitation = null
        pendingQaKey = null
        if (generationJob?.isActive == true) {
            generationJob?.cancel()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val isChip = CHIP_PROMPTS.contains(text)
        // v7.4: no-model tools FIRST - calculator and phone commands work
        // even before any model is downloaded
        if (solveArithmetic(text)) { input.setText(""); return }
        if (tryPhoneCommand(text)) { input.setText(""); return }
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
        // v7.5.1: "1b" missed "1.2b", so LFM 2.5 1.2B got the fat
        // two-source prefill meant for big models - lean now, like 1B
        val tiny = "1b" in mlabel || "1.2b" in mlabel || "0.6b" in mlabel || "0.5b" in mlabel

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
                if (notes.length > (if (tiny) 1200 else 2400))
                    notes = notes.substring(0, if (tiny) 1200 else 2400) + "\n[...more omitted]"
                knowledgePart = "(Relevant notes from the user's documents - use them ONLY if they clearly help answer this exact request; if they do not, ignore them completely and answer normally:\n$notes)\n\n"
            }
        }
        // offline Wikipedia: matching articles as background facts
        var wikiPart = ""
        if (settings.wikiEnabled && WikiCore.isReady(this)) {
            val wikiHits = WikiCore.search(this, text, if (tiny) 1 else 2)
            if (wikiHits.isNotEmpty()) {
                var facts = wikiHits.joinToString("\n---\n") { "${it.title}: ${it.text}" }
                // v7.5: wiki is background only - halve it so the model reads
                // less before the first word; notes (the quality driver) stay
                val cap = if (tiny) 900 else 1200
                if (facts.length > cap) facts = facts.substring(0, cap) + "…"
                wikiPart = "(Wikipedia background - use it to answer, ignore if not relevant:\n$facts)\n\n"
            }
        }
        // v5.4: study questions - rewrap the notes as STRICT instructions,
        // record the source pages, and let the notes be the only background
        if (studyQ && docPart.isEmpty() && knowledgePart.isNotEmpty()) {
            var notes2 = hits.joinToString(NL + "---" + NL) { "[" + it.doc + "] " + it.text }
            if (notes2.length > (if (tiny) 1200 else 2400))
                notes2 = notes2.substring(0, if (tiny) 1200 else 2400)
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
        // one background source for tiny models, both for bigger ones
        prompt = (if (tiny) (if (knowledgePart.isNotEmpty()) knowledgePart else wikiPart)
                  else knowledgePart + wikiPart) + prompt

        // v5.4.7: show when the answer is grounded in the user's notes
        if (knowledgePart.isNotEmpty()) toast("Using your notes")
        autoContinueCount = 0
        replyRetried = false
        startGeneration(prompt, text)
    }