#!/usr/bin/env python3
"""NOVA v7.5 app patch: less reading, denser + faster summaries.

1. Both summarizers (attached document + saved notes) extract DENSE FACT
   BULLETS per section instead of writing 5-8 long sentences - about half
   the tokens to write per section (about half the time) and MORE facts
   for the final combine to organize, so the final summary gets BETTER,
   not thinner. The final combine prompt is unchanged.
2. Wikipedia background injection halved (2400 -> 1200 chars) - it is a
   background source the model is already told to ignore when irrelevant,
   so answers keep their quality while the model reads less before the
   first word. (Notes injection is untouched - it is the quality driver.)

Backslash-safe: @@ marker only.

Idempotent - safe to run on every CI build.
Usage: patch_v750.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
MA = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")

BS = chr(92)
def k(s):
    return s.replace("@@", BS)

def rep(src, old, new, what):
    n = src.count(old)
    assert n == 1, "%s: anchor found %dx (expected 1x)" % (what, n)
    return src.replace(old, new)

A_OLD = k('''NovaEngine.send(
                            "Summarize this part of a document in 5-8 detailed sentences. " +
                                "Keep all names, numbers, dates and facts:" +
                                "@@n-----@@n$c@@n-----", 400
''')
A_NEW = k('''NovaEngine.send(
                            // v7.5: dense fact bullets instead of long sentences - half the
                            // writing time, MORE facts for the final combine to organize
                            "Extract the key facts from this part of a " +
                                "document as a bullet list. One fact per " +
                                "line, short lines. Keep every name, number, " +
                                "date and term exactly as written. No full " +
                                "sentences, no commentary:" +
                                "@@n-----@@n$c@@n-----", 280
''')
B_OLD = k('''NovaEngine.send(
                            "Summarize this part of the notes in 5-8 detailed sentences. " +
                                "Keep every date, name, number, term and fact exactly as " +
                                "stated in the text:$antiCot@@n-----@@n$c@@n-----", 400
''')
B_NEW = k('''NovaEngine.send(
                            // v7.5: dense fact bullets - see summarizeDoc
                            "Extract the key facts from this part of the notes " +
                                "as a bullet list. One fact per line, short lines. " +
                                "Keep every date, name, number, term and fact " +
                                "stated in the text:$antiCot@@n-----@@n$c@@n-----", 280
''')

C_OLD = '''                val cap = if (tiny) 900 else 2400
'''
C_NEW = '''                // v7.5: wiki is background only - halve it so the model reads
                // less before the first word; notes (the quality driver) stay
                val cap = if (tiny) 900 else 1200
'''

src = open(MA, encoding="utf-8").read()
if "v7.5" in src:
    print("MainActivity.kt: v7.5.0 patch already applied")
else:
    src = rep(src, A_OLD, A_NEW, "doc section extraction")
    src = rep(src, B_OLD, B_NEW, "notes section extraction")
    src = rep(src, C_OLD, C_NEW, "wiki cap")
    open(MA, "w", encoding="utf-8").write(src)
    print("MainActivity.kt: v7.5.0 read-time + summary pack applied")
