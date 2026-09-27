#!/usr/bin/env python3
"""Repairs tmp/apply_ui_v77.py in place before the one-shot run.

Fixes: the semibold font sha typo, the two guard counts, and the missing
updateSendLook() send-button states (they re-style the button on every
keystroke and would have reverted the iris gradient immediately).
"""
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
P = os.path.join(ROOT, "tmp", "apply_ui_v77.py")
src = open(P, encoding="utf-8").read()

def rep(old, new, n):
    global src
    c = src.count(old)
    if c != n:
        print("FATAL amend anchor x%d want %d: %r" % (c, n, old[:60]))
        sys.exit(1)
    src = src.replace(old, new)

# 1. semibold font sha: one stray f
rep("78a843fade9d4612a5567302fb595b56976eb5fcebff4fea5a5912d638bafcde3",
    "78a843fade9d4612a5567302fb595b56976eb5fcebf4fea5a5912d638bafcde3", 1)

# 2. guard counts: updateSendLook adds a second TL_BR gradient and the
#    enabled branch stops using the plain GradientDrawable().apply form
rep('Orientation.TL_BR") == 1)', 'Orientation.TL_BR") == 2)', 1)
rep('apply {") == 3)', 'apply {") == 5)', 1)

# 3. send button states in updateSendLook must match the new iris design.
#    The injected block lives inside one outer triple-quoted string, so the
#    inner Kotlin snippets use double-quote triples to avoid nesting.
OLD_TAIL = '''("typeface = Typeface.DEFAULT_BOLD", "setTypeface(typeface, Typeface.BOLD)", 2),
],
"android/app/src/main/java/org/nova/ChatsActivity.kt": ['''
NEW_TAIL = '''("typeface = Typeface.DEFAULT_BOLD", "setTypeface(typeface, Typeface.BOLD)", 2),
("""            sendBtn.background = rippleOverlay(GradientDrawable().apply {
                setColor(accentDeep); cornerRadius = dp(20).toFloat()
            })""",
 """            sendBtn.background = rippleOverlay(GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(accent, accentDeep)).apply {
                cornerRadius = dp(21).toFloat()
            })""", 1),
("""            sendBtn.background = rippleOverlay(GradientDrawable().apply {
                setColor(NovaTheme.sendDim); cornerRadius = dp(20).toFloat()
            })""",
 """            sendBtn.background = rippleOverlay(GradientDrawable().apply {
                setColor(NovaTheme.sendDim); cornerRadius = dp(21).toFloat()
            })""", 1),
],
"android/app/src/main/java/org/nova/ChatsActivity.kt": ['''
rep(OLD_TAIL, NEW_TAIL, 1)

open(P, "wb").write(src.encode("utf-8"))
print("apply_ui_v77.py amended: sha, guard counts, updateSendLook states")
