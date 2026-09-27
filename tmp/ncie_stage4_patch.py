#!/usr/bin/env python3
"""NCIE Stage 4 (#1): the streaming turn + the last Knowledge gates.

- startGeneration's main user turn executes through NovaEngineAdapter
  (the kernel's StreamLlmEngine bridge): same NovaEngine.send Flow, same
  predictLength, same stop semantics — the pipe, not the policy
- every remaining Knowledge.* call in send() moves to NcieKnowledge
  (hasDocs x4, bestDocName x2, bestChunks x2, docs x2, nameOnlyQuery),
  so send() is fully kernel-routed

Knowledge.kt itself, summarizeNotes' internals, the doc picker and the
import flow stay on Knowledge — untouched. Fails loudly on anchor drift.
"""
import sys

PATH = "android/app/src/main/java/org/nova/MainActivity.kt"

# (anchor, replacement, expected_count)
REPLACEMENTS = [
    ("import org.nova.ncie.android.NcieKnowledge",
     "import org.nova.ncie.android.NcieKnowledge\nimport org.nova.ncie.android.NovaEngineAdapter", 1),
    ("if (docContext == null && settings.knowledgeEnabled && Knowledge.hasDocs(this)) {",
     "if (docContext == null && settings.knowledgeEnabled && NcieKnowledge.hasDocs(this)) {", 3),
    ("if (settings.knowledgeEnabled && Knowledge.hasDocs(this)) {",
     "if (settings.knowledgeEnabled && NcieKnowledge.hasDocs(this)) {", 1),
    ("val ndoc = Knowledge.bestDocName(this, text)",
     "val ndoc = NcieKnowledge.bestDocName(this, text)", 1),
    ("Knowledge.bestChunks(this, text, 10)",
     "NcieKnowledge.bestChunks(this, text, 10)", 1),
    ('val names = Knowledge.docs(this).joinToString(", ") { it.first }',
     'val names = NcieKnowledge.docs(this).joinToString(", ") { it.first }', 2),
    ("Knowledge.bestDocName(this, qtext)",
     "NcieKnowledge.bestDocName(this, qtext)", 1),
    ("Knowledge.nameOnlyQuery(qtext, doc)",
     "NcieKnowledge.nameOnlyQuery(qtext, doc)", 1),
    ("Knowledge.bestChunks(this, if (topic.length > 2) topic else text, 10)",
     "NcieKnowledge.bestChunks(this, if (topic.length > 2) topic else text, 10)", 1),
    ("NovaEngine.send(if (wantMath) mathPrompt(p2) else p2,\n                    settings.predictLength)",
     "NovaEngineAdapter.stream(if (wantMath) mathPrompt(p2) else p2,\n                    settings.predictLength)", 1),
]

src = open(PATH, encoding="utf-8").read()

for old, _, n in REPLACEMENTS:
    found = src.count(old)
    if found != n:
        sys.exit(f"FATAL: anchor found {found} times (expected {n}): {old[:60]!r}")

new_src = src
for old, new, _ in REPLACEMENTS:
    new_src = new_src.replace(old, new)

# ---- sanity: the patch did what it says, and the file is still whole ----
assert new_src.count("NcieKnowledge.hasDocs(this)") == 4
assert new_src.count("Knowledge.hasDocs(this)") == 4          # all four are the NcieKnowledge calls
assert new_src.count("NcieKnowledge.bestDocName") == 2
assert new_src.count("NcieKnowledge.bestChunks(this,") == 2
assert new_src.count("Knowledge.bestChunks(this,") == 3       # + summarizeNotes' own call, untouched
assert new_src.count("NcieKnowledge.docs(this)") == 2
assert new_src.count("Knowledge.docs(this)") == 3             # + the doc picker, untouched
assert new_src.count("NcieKnowledge.nameOnlyQuery(qtext, doc)") == 1
assert new_src.count("NcieKnowledge.search(this, text)") == 2  # Stage 3, still intact
assert new_src.count("NovaEngineAdapter.stream(") == 1
assert new_src.count("NovaEngine.send(") == 5                  # the 5 internal one-shot generations, untouched
assert new_src.count("import org.nova.ncie.android.NovaEngineAdapter") == 1
assert "Knowledge.docChunks" in new_src                        # summarizeNotes untouched
assert "Knowledge.addDoc" in new_src                           # import flow untouched
assert "Knowledge.warmUp(this@MainActivity)" in new_src        # startup warm-up untouched
assert new_src.count("{") - new_src.count("}") == src.count("{") - src.count("}")
# verify_patches.py markers that must survive
assert new_src.count("relevance gate - one shared word") == 1
assert new_src.count("Knowledge.warmUp(this@MainActivity)") == 1
assert new_src.count("if (tiny) 1200 else 2400") == 4
assert new_src.count("takeLast(650)") == 1 and new_src.count("takeLast(260)") == 1
assert new_src.count("never invent") == 2
assert new_src.count("Extract the key facts") == 2
assert new_src.count('"1.2b" in mlabel') == 1
assert new_src.count('if (solveArithmetic(text)) { input.setText("")') == 1

open(PATH, "w", encoding="utf-8").write(new_src)
print(f"OK: streaming turn through NovaEngineAdapter + send() fully kernel-routed "
      f"({len(src) - len(new_src)} net chars removed)")
