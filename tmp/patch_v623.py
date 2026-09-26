#!/usr/bin/env python3
"""NOVA v6.2.3 app patch: strict mode falls back to general knowledge.

Strict mode used to dead-end: when the notes did not cover the question,
the model was ordered to reply exactly "My notes don't cover this" and
stop, so the user had to toggle strict mode off to get any answer.

New behaviour: notes first; when they do not cover it, answer from general
knowledge with a clear marker line. The user never flips the setting again.

Idempotent - safe to run on every CI build.
Usage: patch_v623.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
MA = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")

OLD = '''    private fun effectivePrompt(p: String): String =
        if (settings.strictMode)
            "STRICT MODE: Answer ONLY from the user's notes and the Wikipedia extracts " +
                "in this conversation. If they do not contain the answer, say exactly: " +
                "'My notes don't cover this.' Never invent facts, names, dates or numbers.\\n\\n" + p
        else p
'''

NEW = '''    private fun effectivePrompt(p: String): String =
        if (settings.strictMode)
            "STRICT MODE: Answer from the user's notes and the Wikipedia extracts in this " +
                "conversation WHEN they cover the question. If they do not cover it, begin the " +
                "reply with 'From general knowledge (not in your notes):' and answer from your " +
                "own knowledge. Never invent facts, names, dates or numbers.\\n\\n" + p
        else p
'''

MARKER = "From general knowledge (not in your notes):"

src = open(MA, encoding="utf-8").read()
if MARKER in src:
    print("MainActivity.kt: v6.2.3 strict-mode patch already applied")
else:
    n = src.count(OLD)
    assert n == 1, "effectivePrompt block found %dx (expected 1x)" % n
    open(MA, "w", encoding="utf-8").write(src.replace(OLD, NEW))
    print("MainActivity.kt: strict mode now falls back to general knowledge")
