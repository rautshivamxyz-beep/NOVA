#!/usr/bin/env python3
# v5.4.5: follow-up questions keep their notes + anti-waffle answer prompts.
# Applied by CI (one-shot). Each anchor must appear exactly once or the
# script exits 1 without writing anything.
import hashlib, sys

P = "android/app/src/main/java/org/nova/MainActivity.kt"
src = open(P, encoding="utf-8").read()

def rep(old, new):
    global src
    n = src.count(old)
    if n != 1:
        print("ANCHOR COUNT %d (expected 1): %r" % (n, old[:70]))
        sys.exit(1)
    src = src.replace(old, new)

# --- 1) state: remember the notes that fed the last grounded answer -------
rep(r'''    private var pendingCitation: String? = null''',
r'''    private var pendingCitation: String? = null
    // v5.4.5: notes that fed the last grounded answer in this chat, so
    // keyword-less follow-ups ("explain it in more detail") stay grounded
    private var lastNotesHit: List<Knowledge.Chunk> = emptyList()
    private var lastNotesChatId: String = ""''')

# --- 2) follow-up carry + record the hits ----------------------------------
rep(r'''            hits = Knowledge.search(this, text)
            if (hits.isNotEmpty()) {''',
r'''            hits = Knowledge.search(this, text)
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
                lastNotesChatId = currentChat.id''')

# --- 3) anti-waffle instructions in the strict studyQ rewrap ---------------
rep(r'''                "terms and facts exactly as written." + NL + notes2 + ")" + NL + NL''',
r'''                "terms and facts exactly as written. Be direct and complete, " +
                "never pad: no filler like 'the story is often seen as', no " +
                "repeating the question, no repeating the same idea twice." +
                (if (qLow.startsWith("teach me "))
                    " Teach the topic fully from the notes, definition first."
                 else " Answer in at most 120 words unless the user asks for detail.") +
                NL + notes2 + ")" + NL + NL''')

# --- 4) follow-up detector --------------------------------------------------
rep(r'''private val THINK_OPEN = "<" + "think" + ">"''',
r'''private val THINK_OPEN = "<" + "think" + ">"

/** v5.4.5: follow-up questions with no keywords of their own - they mean
 *  "the same notes again", so the last grounded notes are carried forward. */
private val FOLLOW_UP_Q = Regex("(?i)\\b(explain (it|that|this)|in more detail|more detail|tell me more|explain more|elaborate|go on)\\b")''')

open(P, "w", encoding="utf-8").write(src)
print("md5:", hashlib.md5(open(P, "rb").read()).hexdigest())
print("OK - v5.4.5 patch applied")
