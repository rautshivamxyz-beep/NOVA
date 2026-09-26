#!/usr/bin/env python3
"""NOVA v7.3.0 app patch: chat quality - stop drowning small models.

Real user transcripts showed four failures, all with the same root cause -
EVERY message, including 'hi' and 'tell me a joke', was buried under note
extracts, Wikipedia extracts, strict-mode instructions and a LaTeX
instruction, and LFM 2.5 1.2B Instruct drowned and started summarizing
instead of chatting.

1. Greetings/smalltalk now get a clean tiny prompt: no notes, no wiki,
   no strict wrapper, no LaTeX instruction. NOVA just says hi back.
2. The LaTeX ('write formulas in dollar signs') instruction is only added
   when the message actually contains numbers or maths words - jokes no
   longer come out in boxed notation.
3. Non-study note injection now says: use ONLY if clearly relevant to this
   exact request, otherwise ignore and answer normally.
4. Strict mode: the 'From general knowledge' marker is said once at the
   start only, never repeatedly mid-reply.

Note: every backslash that must appear in the Kotlin source is built with
the @-marker below, so this file itself contains NO backslash literals
and survives any JSON/transport layering unchanged.

Idempotent - safe to run on every CI build.
Usage: patch_v730.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
MA = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")

# '@@' in the strings below becomes a real backslash in the patched file
BS = chr(92)
def k(s):
    return s.replace("@@", BS)

def rep(src, old, new, what):
    n = src.count(old)
    assert n == 1, "%s: anchor found %dx (expected 1x)" % (what, n)
    return src.replace(old, new)

# ---- 1) greeting fast path, before any notes/wiki routing ----
A_OLD = '''        if (solveArithmetic(text)) return
        if (tryPhoneCommand(text)) return
'''
A_NEW = '''        if (solveArithmetic(text)) return
        if (tryPhoneCommand(text)) return
        // v7.3: greetings get a clean tiny prompt - no notes/wiki/maths
        // wrapper, so the model chats instead of summarizing
        if (SMALLTALK_REGEX.containsMatchIn(text)) {
            startGeneration(
                "(The user said: '" + text + "' - greet them warmly in one or two " +
                    "short sentences and offer to help. Do not mention notes, documents, " +
                    "Wikipedia or summaries.)", text, plain = true)
            return
        }
'''

# ---- 2) startGeneration: plain mode + LaTeX only for maths-y messages ----
B_OLD = '''    private fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true) {
'''
B_NEW = '''    private fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true, plain: Boolean = false) {
'''

C_OLD = '''                NovaEngine.send(mathPrompt(effectivePrompt(prompt)), settings.predictLength)
'''
C_NEW = '''                // v7.3: strict wrapper (and the LaTeX instruction inside
                // mathPrompt) only when relevant - greetings/chit-chat get
                // none of it
                val p2 = if (plain) prompt else effectivePrompt(prompt)
                NovaEngine.send(if (plain || !looksMathy(userText ?: prompt)) p2 else mathPrompt(p2),
                    settings.predictLength)
'''

# ---- 3) non-study notes: clearly-relevant-only instruction ----
D_OLD = k('''                knowledgePart = "(Relevant notes from the user's documents - use them if they help:@@n$notes)@@n@@n"
''')
D_NEW = k('''                knowledgePart = "(Relevant notes from the user's documents - use them ONLY if they clearly help answer this exact request; if they do not, ignore them completely and answer normally:@@n$notes)@@n@@n"
''')

# ---- 4) strict-mode marker: once, at the start only ----
E_OLD = k('''                "reply with 'From general knowledge (not in your notes):' and answer from your " +
                "own knowledge. Never invent facts, names, dates or numbers.@@n@@n" + p
''')
E_NEW = k('''                "reply with 'From general knowledge (not in your notes):' and answer from your " +
                "own knowledge. Say that phrase once at the start only - never again inside " +
                "the reply. Never invent facts, names, dates or numbers.@@n@@n" + p
''')

# ---- 5) the helpers, next to their kin ----
F_OLD = k('''    /** v6.1.0: ask for LaTeX so formulas render like a textbook. */
    private fun mathPrompt(p: String): String =
        p + "@@n(If your answer includes mathematical formulas, write each formula in LaTeX, wrapped in dollar signs.)"
''')
F_NEW = k('''    /** v6.1.0: ask for LaTeX so formulas render like a textbook. */
    private fun mathPrompt(p: String): String =
        p + "@@n(If your answer includes mathematical formulas, write each formula in LaTeX, wrapped in dollar signs.)"

    /** v7.3: does this message actually involve maths? If not, the LaTeX
     *  instruction is skipped - jokes and greetings stop coming out in
     *  boxed notation. */
    private fun looksMathy(p: String): Boolean =
        p.any { it.isDigit() } ||
            Regex("(?i)@@@@b(calc|math|solve|equation|formula|sqrt|prime|percentage|integral|derivative|algebra|geometry)@@@@b")
                .containsMatchIn(p)
''')

G_OLD = k('''private val FOLLOW_UP_Q = Regex("(?i)@@@@b(explain (it|that|this)|in more detail|more detail|tell me more|explain more|elaborate|go on)@@@@b")
''')
G_NEW = k('''private val FOLLOW_UP_Q = Regex("(?i)@@@@b(explain (it|that|this)|in more detail|more detail|tell me more|explain more|elaborate|go on)@@@@b")

// v7.3: bare greetings / smalltalk - matched on the WHOLE message
private val SMALLTALK_REGEX = Regex(
    "(?i)^[@@@@s']*(hi+|hey+|hello+|yo|sup|namaste|hola|good (morning|afternoon|evening|night)" +
        "|how are (you|u)|how r (you|u)|what'?s up|how'?s it going)[@@@@s.!~?]*$"
)
''')

src = open(MA, encoding="utf-8").read()
if "v7.3" in src:
    print("MainActivity.kt: v7.3.0 patch already applied")
else:
    src = rep(src, A_OLD, A_NEW, "greeting fast path")
    src = rep(src, B_OLD, B_NEW, "startGeneration signature")
    src = rep(src, C_OLD, C_NEW, "send wrap")
    src = rep(src, D_OLD, D_NEW, "knowledge wrapper")
    src = rep(src, E_OLD, E_NEW, "strict marker once")
    src = rep(src, F_OLD, F_NEW, "looksMathy helper")
    src = rep(src, G_OLD, G_NEW, "smalltalk regex")
    open(MA, "w", encoding="utf-8").write(src)
    print("MainActivity.kt: v7.3.0 chat-quality pack applied")
