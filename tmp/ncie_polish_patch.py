#!/usr/bin/env python3
"""NCIE v0.7.0 polish (#1): the last direct calls move behind the kernel
boundary.

1. MainActivity's five direct Knowledge.* calls (warm-up, the summarizers'
   chunk pulls, the notes filter, save-to-knowledge) become NcieKnowledge.*
   - after this, only the Knowledge.Chunk data type reference remains.
   KnowledgeActivity's six direct calls (docs, removeDoc x2, docText x2,
   addDoc) switch to NcieKnowledge too, plus its import.
2. The five direct NovaEngine.send sites in the summarizer/compaction paths
   become NovaEngineAdapter.stream(...) - the same engine, now reached
   through the kernel's LlmEngine component like every other generation.
3. Updates the guard: the warm-up marker and the streaming-turn count point
   at their new spellings, plus four new polish markers (MainActivity,
   KnowledgeActivity, NcieKnowledge).
4. Runs the updated guard - fails loud BEFORE any commit happens.
"""
import re
import subprocess
import sys

MA = "android/app/src/main/java/org/nova/MainActivity.kt"
KA = "android/app/src/main/java/org/nova/KnowledgeActivity.kt"
GUARD = "tmp/verify_patches.py"

src = open(MA, encoding="utf-8").read()
assert src.count("NcieKnowledge.docChunks(") == 0, "MainActivity already patched"

SWAPS = [
    ("Knowledge.warmUp(this@MainActivity); NcieKnowledge.warmUpNotes(this@MainActivity)",
     "NcieKnowledge.warmUp(this@MainActivity); NcieKnowledge.warmUpNotes(this@MainActivity)"),
    ("Knowledge.docChunks(this, doc)", "NcieKnowledge.docChunks(this, doc)"),
    ("Knowledge.bestChunks(this, userText)", "NcieKnowledge.bestChunks(this, userText)"),
    ("Knowledge.docs(this).map { it.first }", "NcieKnowledge.docs(this).map { it.first }"),
    ("Knowledge.addDoc(ctx, nm, msgText)", "NcieKnowledge.addDoc(ctx, nm, msgText)"),
]
for old, new in SWAPS:
    assert src.count(old) == 1, "anchor not found exactly once: " + old
    src = src.replace(old, new)

assert src.count("NovaEngine.send(") == 5, "expected exactly 5 direct NovaEngine.send sites"
assert src.count("NovaEngineAdapter.stream(") == 1
src = src.replace("NovaEngine.send(", "NovaEngineAdapter.stream(")
assert src.count("NovaEngineAdapter.stream(") == 6
# only the Knowledge.Chunk data type reference may remain
_direct = re.findall(r"(?<!Ncie)Knowledge\.", src)
assert len(_direct) == 1, "unexpected residual Knowledge.* reference: " + str(_direct)
assert "List<Knowledge.Chunk>" in src
open(MA, "w", encoding="utf-8").write(src)

ka = open(KA, encoding="utf-8").read()
assert ka.count("NcieKnowledge.") == 0, "KnowledgeActivity already patched"
KA_SWAPS = [
    ("import android.widget.Toast\n", "import android.widget.Toast\nimport org.nova.ncie.android.NcieKnowledge\n"),
    ("val docs = Knowledge.docs(this)", "val docs = NcieKnowledge.docs(this)"),
    ("Knowledge.removeDoc(this@KnowledgeActivity, name)", "NcieKnowledge.removeDoc(this@KnowledgeActivity, name)"),
    ("Knowledge.removeDoc(this, name)", "NcieKnowledge.removeDoc(this, name)"),
    ("val text = Knowledge.docText(this, name)", "val text = NcieKnowledge.docText(this, name)"),
    ("it.write(Knowledge.docText(this, nm).toByteArray())", "it.write(NcieKnowledge.docText(this, nm).toByteArray())"),
    ("Knowledge.addDoc(this, name, text)", "NcieKnowledge.addDoc(this, name, text)"),
]
for old2, new2 in KA_SWAPS:
    assert ka.count(old2) == 1, "KA anchor not found exactly once: " + old2[:60]
    ka = ka.replace(old2, new2)
ka_direct = re.findall(r"(?<!Ncie)Knowledge\.", ka)
assert len(ka_direct) == 0, "residual Knowledge.* in KnowledgeActivity: " + str(ka_direct)
open(KA, "w", encoding="utf-8").write(ka)

g = open(GUARD, encoding="utf-8").read()
assert g.count("NCIE polish") == 0, "guard already patched"

old_warm = '    ("v7.6 notes cache warm-up", "Knowledge.warmUp(this@MainActivity)", 1),'
assert g.count(old_warm) == 1
g = g.replace(old_warm,
    '    ("v7.6 notes cache warm-up", "NcieKnowledge.warmUp(this@MainActivity); NcieKnowledge.warmUpNotes(this@MainActivity)", 1),\n'
    '    ("NCIE polish: summarizer chunks through the boundary", "NcieKnowledge.docChunks(this, doc)", 1),\n'
    '    ("NCIE polish: save-to-knowledge through the boundary", "NcieKnowledge.addDoc(ctx, nm, msgText)", 1),\n'
    '    ("NCIE polish: all generation through the kernel engine", "NovaEngineAdapter.stream(", 6),')

old_stream = 'check("v7.6 streaming turn through NovaEngineAdapter", ma.count("NovaEngineAdapter.stream(") == 1)'
assert g.count(old_stream) == 1
g = g.replace(old_stream, 'check("v7.6 streaming turn through NovaEngineAdapter (all 6 generations now)", ma.count("NovaEngineAdapter.stream(") == 6)')

old_ka = 'check("v7.4 bounded import (KnowledgeActivity.kt)", ka.count("bound the read") == 1)'
assert g.count(old_ka) == 1
g = g.replace(old_ka, old_ka +
    '\ncheck("NCIE polish: import UI through the boundary", ka.count("NcieKnowledge.addDoc(this, name, text)") == 1)'
    '\nnk = load("ncie/android/NcieKnowledge.kt")'
    '\ncheck("NCIE polish: storage exposed on the boundary", nk.count("fun addDoc(c: Context, name: String, text: String)") == 1)'
    '\ncheck("NCIE polish: doc chunks exposed on the boundary", nk.count("fun docChunks(c: Context, name: String)") == 1)')

open(GUARD, "w", encoding="utf-8").write(g)

r = subprocess.run([sys.executable, GUARD, "."], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
