#!/usr/bin/env python3
"""NCIE Stage 2 (#1): rewire MainActivity.solveArithmetic to route through
the NCIE DecisionKernel (org.nova.ncie.android.NcieArithmetic).

Replaces the 90-line inline evaluator with a delegation to the kernel gate;
the display logic (user message, "**= ..." reply, chat save) is unchanged.
Fails loudly if any anchor is not found exactly once, so a drifted anchor
can never silently ship a half-patched MainActivity.
"""
import sys

PATH = "android/app/src/main/java/org/nova/MainActivity.kt"

IMPORT_ANCHOR = "import androidx.recyclerview.widget.RecyclerView"
IMPORT_NEW = "import org.nova.ncie.android.NcieArithmetic"

START = "    /** v7.0.0: pure arithmetic gets an EXACT instant answer"
END = "    /** v7.1: welcome a brand-new user and point at the model download. */"

NEW = r'''    /** v7.0.0 (NCIE Stage 2 of #1): pure arithmetic still gets an EXACT
     *  instant answer — but the go/no-go decision now routes through the
     *  NCIE DecisionKernel (org.nova.ncie.android.NcieArithmetic), the
     *  app's first live kernel route. The expression language is byte-for-
     *  byte the one shipped since v7.0.0 (trig in degrees, log base 10,
     *  sqrt, pi/e, unicode operators, comma separators); it moved into
     *  ArithmeticTool, and canHandle only claims text it fully evaluated,
     *  so a TOOL route guarantees a real answer. Word problems still go
     *  to the AI. */
    private fun solveArithmetic(text: String): Boolean {
        val t = text.trim()
        val shown = NcieArithmetic.solve(t) ?: return false
        val um = Msg(Role.USER, t)
        currentChat.messages.add(um); adapter.add(um)
        val reply = Msg(Role.ASSISTANT,
            "**= $shown**\n\n(Exact calculation - instant and never wrong. Word problems still go to the AI.)")
        currentChat.messages.add(reply); adapter.add(reply)
        scrollToEnd()
        scope.launch(Dispatchers.IO) {
            try { ChatStore.save(this@MainActivity, currentChat) } catch (e: Exception) { }
        }
        return true
    }

'''

src = open(PATH, encoding="utf-8").read()

for name, anchor in (("import", IMPORT_ANCHOR), ("start", START), ("end", END)):
    n = src.count(anchor)
    if n != 1:
        sys.exit(f"FATAL: {name} anchor found {n} times (expected 1)")
if src.count(IMPORT_NEW):
    sys.exit("FATAL: stage-2 import already present — already patched?")

i = src.index(START)
j = src.index(END)
if j < i:
    sys.exit("FATAL: anchors out of order")

new_src = src[:i] + NEW + src[j:]
new_src = new_src.replace(IMPORT_ANCHOR, IMPORT_ANCHOR + "\n" + IMPORT_NEW, 1)

# ---- sanity: the patch did what it says, and the file is still whole ----
assert "NcieArithmetic.solve(t)" in new_src
assert "val p = object" not in new_src          # old evaluator gone
assert new_src.count('if (solveArithmetic(text)) { input.setText("")') == 1  # CI-guard call site intact
assert new_src.count("{") - new_src.count("}") == src.count("{") - src.count("}")  # brace delta preserved
assert new_src.count(IMPORT_NEW) == 1
assert "private fun solveArithmetic(text: String): Boolean {" in new_src

open(PATH, "w", encoding="utf-8").write(new_src)
print("OK: solveArithmetic now routes through NcieArithmetic "
      f"({len(src) - len(new_src)} chars of inline evaluator removed, "
      f"{len(NEW)} chars of gate call added)")
