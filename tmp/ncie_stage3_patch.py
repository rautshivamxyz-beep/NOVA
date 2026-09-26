#!/usr/bin/env python3
"""NCIE Stage 3 (#1): send()'s notes retrieval routes through the kernel.

- hits now come from NcieKnowledge (kernel: ANALYZE -> PLAN -> KnowledgeStore
  search on the app's knowledge.json chunks, verbatim), replacing the direct
  Knowledge.search calls
- the v7.6 relevance gate moves INSIDE NcieKnowledge.search (same logic)
- NcieKnowledge.warmUpNotes parses knowledge.json off the main thread at startup

Every prompt template, cap, follow-up-carry and study rewrap is untouched.
Fails loudly if any anchor is not found exactly once.
"""
import sys

PATH = "android/app/src/main/java/org/nova/MainActivity.kt"

GATE_OLD = '''            // v7.6: relevance gate - one shared word (e.g. just "bose")
            // matched junk notes and the model answered from them with a
            // confident-looking citation. Require the significant query
            // terms to actually appear in the matched chunks.
            if (hits.isNotEmpty()) {
                val sigTerms = Knowledge.tokenize(text).filter { it.length > 3 }.distinct()
                val hitText = hits.joinToString(" ") { h -> h.text }.lowercase()
                val matched = sigTerms.count { hitText.contains(it) }
                if (matched == 0 || (sigTerms.size >= 2 && matched < 2)) {
                    hits = emptyList()
                }
            }
'''

GATE_NEW = '''            // v7.6: relevance gate - one shared word (e.g. just "bose")
            // matched junk notes and the model answered from them with a
            // confident-looking citation. The gate (significant query
            // terms must appear in the matched chunks) now runs inside
            // the kernel - see NcieKnowledge.search.
'''

REPLACEMENTS = [
    ("import org.nova.ncie.android.NcieArithmetic",
     "import org.nova.ncie.android.NcieArithmetic\nimport org.nova.ncie.android.NcieKnowledge"),
    ("hits = Knowledge.search(this, text)",
     "hits = NcieKnowledge.search(this, text)"),
    ("val h2 = Knowledge.search(this, text)",
     "val h2 = NcieKnowledge.search(this, text)"),
    (GATE_OLD, GATE_NEW),
    ("scope.launch(Dispatchers.IO) { Knowledge.warmUp(this@MainActivity) }",
     "scope.launch(Dispatchers.IO) { Knowledge.warmUp(this@MainActivity); NcieKnowledge.warmUpNotes(this@MainActivity) }"),
]

src = open(PATH, encoding="utf-8").read()

for old, _ in REPLACEMENTS:
    n = src.count(old)
    if n != 1:
        sys.exit(f"FATAL: anchor found {n} times (expected 1): {old[:60]!r}")

new_src = src
for old, new in REPLACEMENTS:
    new_src = new_src.replace(old, new, 1)

# ---- sanity: the patch did what it says, and the file is still whole ----
assert new_src.count("NcieKnowledge.search(this, text)") == 2
assert new_src.count("NcieKnowledge.warmUpNotes(this@MainActivity)") == 1
assert new_src.count("Knowledge.search(this, text)") == 2   # both occurrences are the NcieKnowledge calls
assert "Knowledge.tokenize" not in new_src                # gate code moved into the kernel
assert new_src.count("{") - new_src.count("}") == src.count("{") - src.count("}")
# verify_patches.py markers that must survive
assert new_src.count("relevance gate - one shared word") == 1
assert new_src.count("Knowledge.warmUp(this@MainActivity)") == 1
assert new_src.count("if (tiny) 1200 else 2400") == 4
assert new_src.count("takeLast(650)") == 1 and new_src.count("takeLast(260)") == 1
assert new_src.count("never invent") == 2
assert new_src.count('if (solveArithmetic(text)) { input.setText("")') == 1

open(PATH, "w", encoding="utf-8").write(new_src)
print(f"OK: notes retrieval routed through NcieKnowledge ({len(src) - len(new_src)} net chars removed)")
