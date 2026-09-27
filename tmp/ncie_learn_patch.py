#!/usr/bin/env python3
"""NCIE v0.7.0 wiring (#1): Smart Skip + the quality gate, live.

1. inserts the LEARN record call at MainActivity's turn-completion point
   (the else-branch of auto-continue = the turn is truly complete, after
   cleanReplyText and the citation append, with the final full text)
2. adds the NcieLearn import beside the other NCIE imports
3. updates tmp/verify_patches.py with the two new markers (explicit guard
   update, as always)
4. runs the updated guard - fails loud BEFORE any commit happens
"""
import subprocess
import sys

MA = "android/app/src/main/java/org/nova/MainActivity.kt"
GUARD = "tmp/verify_patches.py"

src = open(MA, encoding="utf-8").read()
assert src.count("NcieLearn.record(") == 0, "MainActivity already patched"

imp_anchor = "import org.nova.ncie.android.NcieKnowledge\n"
assert src.count(imp_anchor) == 1, "import anchor not found exactly once"
src = src.replace(imp_anchor, imp_anchor + "import org.nova.ncie.android.NcieLearn\n")
assert src.count("import org.nova.ncie.android.NcieLearn") == 1

anchor = """                    } else {
                        // auto-compact: compress old turns once the chat grows"""
assert src.count(anchor) == 1, "turn-completion anchor not found exactly once"
insert = """                    } else {
                        // NCIE v0.7.0 (#1): the turn is complete (no continuation
                        // pending) - hand the final answer to the kernel's LEARN
                        // phase. NcieLearn quality-gates it (blank/leaked/boiler-
                        // plate replies and internal prompts never enter the
                        // cache) and writes asynchronously; nothing blocks here.
                        NcieLearn.record(this@MainActivity, userText, replyMsg.text)
                        // auto-compact: compress old turns once the chat grows"""
src = src.replace(anchor, insert)
assert src.count("NcieLearn.record(this@MainActivity, userText, replyMsg.text)") == 1
open(MA, "w", encoding="utf-8").write(src)

g = open(GUARD, encoding="utf-8").read()
assert g.count("NcieLearn") == 0, "guard already patched"
ma_anchor = '    ("v7.6 compaction chat guard", "remember which chat this compaction belongs to", 1),'
assert g.count(ma_anchor) == 1
g = g.replace(ma_anchor, ma_anchor +
    '\n    ("NCIE 7: record the completed turn for the learner", "NcieLearn.record(this@MainActivity, userText, replyMsg.text)", 1),')
nc_anchor = '    ("Stage 5 collapse: body reached the act capture", "val act = this", 1),'
assert g.count(nc_anchor) == 1
g = g.replace(nc_anchor, nc_anchor +
    '\n    ("NCIE 7: Smart Skip recall in the chat turn", "NcieLearn.recall(this, text)", 1),')
open(GUARD, "w", encoding="utf-8").write(g)

r = subprocess.run([sys.executable, GUARD, "."], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
