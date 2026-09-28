#!/usr/bin/env python3
"""NOVA post-patch verification (automatic test, level 1).

Runs in CI right after the patch chain. Checks that every feature marker
from v6.2.3 .. v7.7.0 is present in the generated code exactly as many
times as expected, that no old code survived where a patch should have
replaced it, that the send-order fix is in the right order, and that all
four patched Kotlin files still balance with no invalid escapes.

This is the guard against the #1 build risk from the code review: a patch
silently skipping (stale anchor, stray comment) and shipping the APK
minus a feature with no error anywhere. Also checks build.gradle against
the srdDirs typo corruption seen on 2026-09-26.

Usage: verify_patches.py <NOVA repo root>
Exits 1 (fails CI) if anything is missing.
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = "android/app/src/main/java/org/nova/"
FILES = ["MainActivity.kt", "Backup.kt", "NotifBrain.kt", "KnowledgeActivity.kt"]
# Stage 5 (#1): send() collapsed - its body moved verbatim to
# ncie/android/NcieChat.kt (MainActivity.ncieSend). The markers that
# lived inside send() are checked against NcieChat.kt now; everything
# else still points at MainActivity.

fails = []

def check(name, cond):
    print(("PASS  " if cond else "FAIL  ") + name)
    if not cond:
        fails.append(name)

def load(f):
    return open(os.path.join(ROOT, BASE + f), encoding="utf-8").read()


ma = load("MainActivity.kt")
nc = load("ncie/android/NcieChat.kt")
bk = load("Backup.kt")
nb = load("NotifBrain.kt")
ka = load("KnowledgeActivity.kt")
ca = load("ChatsActivity.kt")
sa = load("SettingsActivity.kt")
ea = load("ExamsActivity.kt")
moa = load("ModelsActivity.kt")
mea = load("MemoryActivity.kt")
nt = load("NovaTheme.kt")

# ---- feature markers, exactly as the patch chain writes them ----
MA_MARKERS = [
    ("v6.2.3 base markers", "class MainActivity", 1),
    ("v7.3 greeting fast-path (regex)", "SMALLTALK_REGEX", 1),
    ("v7.4 chip prompt set", "CHIP_PROMPTS", 1),
    # moved to NcieChat.kt below: ("v7.4 chip routing check", "val isChip = CHIP_PROMPTS.contains(text)", 1),
    ("v7.4 chat-switch guard", "val genChat = currentChat", 1),
    ("v7.4 input usable without model", "input.isEnabled = !NovaEngine.isLoading", 1),
    ("v7.5 section extraction prompt", "Extract the key facts", 2),
    # moved to NcieChat.kt below: ("v7.5 lean wiki cap", "val cap = if (tiny) 900 else 1200", 1),
    # moved to NcieChat.kt below: ("v7.5.1 LFM tiny detection", '"1.2b" in mlabel', 1),
    # moved to NcieChat.kt below: ("v7.5.1 tiny notes caps", "if (tiny) 1200 else 2400", 4),
    ("v7.5.1 doc chunk overlap", "takeLast(650)", 1),
    ("v7.5.1 notes chunk overlap", "takeLast(260)", 1),
    ("v7.5.1 anti-invent guardrails", "never invent", 2),
    ("v7.5.1 instant resets", "NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt)", 6),
    # moved to NcieChat.kt below: ("v7.6 greeting context reset", "greeting sent into a dirty/stale context", 1),
    # moved to NcieChat.kt below: ("v7.6 notes relevance gate", "relevance gate - one shared word", 1),
    ("v7.6 reply boilerplate cleaner", "private fun cleanReplyText", 1),
    # moved to NcieChat.kt below: ("v7.6 input cleared only when consumed", 'if (solveArithmetic(text)) { input.setText("")', 1),
    ("v7.6 notes cache warm-up", "NcieKnowledge.warmUp(this@MainActivity); NcieKnowledge.warmUpNotes(this@MainActivity)", 1),
    ("NCIE polish: summarizer chunks through the boundary", "NcieKnowledge.docChunks(this, doc)", 1),
    ("NCIE polish: save-to-knowledge through the boundary", "NcieKnowledge.addDoc(ctx, nm, msgText)", 1),
    ("NCIE polish: all generation through the kernel engine", "NovaEngineAdapter.stream(", 6),
    ("v7.6.5: pressed-state ripple feedback", "fun rippleOverlay(", 1),
    ("v0.8.1: kernel-sized generation leash", "NcieKnowledge.generationBudget(", 1),
    ("v0.8.1: live streaming in the summarizers", "uiProgress(", 5),
    ("v7.6 compaction chat guard", "remember which chat this compaction belongs to", 1),
    ("NCIE 7: record the completed turn for the learner",
     "NcieLearn.record(this@MainActivity, userText, replyMsg.text, lastAnswerSources)", 1),
]
for name, marker, n in MA_MARKERS:
    check("%s (x%d)" % (name, n), ma.count(marker) == n)

# markers that moved with send()'s body to ncie/android/NcieChat.kt
NC_MARKERS = [
    ("v7.3 greeting fast-path (use)", "SMALLTALK_REGEX", 2),
    ("v7.4 chip prompt set (use)", "CHIP_PROMPTS", 2),
    ("v7.4 chip routing check", "val isChip = CHIP_PROMPTS.contains(text)", 1),
    ("v7.5/NCIE-6 lean wiki cap", "val cap = if (lean) 900 else 1200", 1),
    ("NCIE 6: kernel context-budget gate in the chat turn", "NcieKnowledge.leanContext(text)", 1),
    ("v7.5.1 LFM tiny detection", '"1.2b" in mlabel', 1),
    ("v7.5.1/NCIE-6 lean notes caps", "if (lean) 1200 else 2400", 4),
    ("v7.6 greeting context reset", "greeting sent into a dirty/stale context", 1),
    ("v7.6 notes relevance gate", "relevance gate - one shared word", 1),
    ("v7.6 input cleared only when consumed", 'if (solveArithmetic(text)) { input.setText("")', 1),
    ("Stage 5 collapse: the moved body is one function", "fun MainActivity.ncieSend()", 1),
    ("Stage 5 collapse: body reached the act capture", "val act = this", 1),
    ("NCIE 7: Smart Skip recall in the chat turn", "NcieLearn.recall(this, text)", 1),
]
for name, marker, n in NC_MARKERS:
    check("%s (x%d)" % (name, n), nc.count(marker) == n)
check("Stage 5 collapse: send() is the thin dispatch", ma.count("ncieSend()") == 1)
check("v7.6 streaming turn through NovaEngineAdapter (all 6 generations now)", ma.count("NovaEngineAdapter.stream(") == 6)

check("v7.4 backup key fix (Backup.kt)", bk.count('put("knowledge_enabled"') == 1)
check("v7.4 notification thread fix (NotifBrain.kt)", nb.count("return@Thread") == 1)
check("v7.4 bounded import (KnowledgeActivity.kt)", ka.count("bound the read") == 1)
check("NCIE polish: import UI through the boundary", ka.count("NcieKnowledge.addDoc(this, name, text)") == 1)
nk = load("ncie/android/NcieKnowledge.kt")
check("NCIE polish: storage exposed on the boundary", nk.count("fun addDoc(c: Context, name: String, text: String)") == 1)
check("NCIE polish: doc chunks exposed on the boundary", nk.count("fun docChunks(c: Context, name: String)") == 1)
# ---- v0.8.1: stale-learner fix + kernel generation budget ----
nl = load("ncie/android/NcieLearn.kt")
check("v0.8.1: learner invalidated when notes change",
      nl.count("fun invalidate()") == 1)
check("v0.8.1: records join the learner's own thread",
      nl.count("io.execute { l.record(userText, response) }") == 1)
check("v0.8.1: addDoc/removeDoc drop the learned cache",
      nk.count("NcieLearn.invalidate()") == 2)
check("v0.8.1: kernel-sized generation leash on the boundary",
      nk.count("fun generationBudget(") == 1)
lr = load("ncie/learn/Learner.kt")
# v0.9.0: PersistentLearner + LearningStore split out of Learner.kt into
# their own file (same package) — the markers are counted across both.
pl = load("ncie/learn/PersistentLearner.kt")
ak = load("ncie/plan/AdaptiveKernel.kt")
lf = load("ncie/learn/LearnedFact.kt")
check("v0.8.1: vendored learner has clear() (interface + both impls)",
      lr.count("fun clear()") + pl.count("fun clear()") == 3)
check("v0.8.1: vendored learner has synonym classes",
      lr.count("synonymGroups") + pl.count("synonymGroups") == 2)
ws = load("ncie/knowledge/WikiStore.kt")
kstore = load("ncie/knowledge/KnowledgeStore.kt")
knn = load("Knowledge.kt")
wc = load("WikiCore.kt")
# v8.1.0: the title-bonus loop now reads the PREPARED index, so qWords
# appears once more - whole-word matching itself is unchanged
check("v7.8.1: wiki title bonus matches whole words (hiv no longer inside shivam)",
      ws.count("space-padded") == 1 and ws.count("qWords") == 3)
check("v7.8.1: self-introduction routed to plain chat (NcieChat.kt)",
      nc.count("SELF_INTRO_REGEX") == 2 and nc.count("maybeRememberName") == 2)
check("v7.8.1: name is a knowledge stopword (Knowledge.kt)",
      knn.count('"name", "names"') == 1)
check("v7.8.2: name is a KERNEL knowledge stopword (KnowledgeStore.kt)",
      kstore.count('"name", "names"') == 1)
check("v7.6 atomic notes saves (Knowledge.kt)", knn.count("atomic write") == 1)
check("v7.6 notes warm-up for send gate (Knowledge.kt)", knn.count("warm the cache from a background thread") == 1)
check("v7.6 wiki done marker written last (WikiCore.kt)", wc.count("done.tmp") == 1)
check("v7.6 backup restores exams+reminders (Backup.kt)", bk.count("restore exams and reminders too") == 1)

# ---- build.gradle: guard against the srdDirs corruption seen on 2026-09-26 ----
bg = open(os.path.join(ROOT, "android/app/build.gradle"), encoding="utf-8").read()
check("build.gradle: jniLibs srcDirs intact (no typo corruption)",
      bg.count("jniLibs.srcDirs") == 1 and bg.count("srdDirs") == 0)
check("v8.2.0: version bumped for the adaptive rules + sourced memory",
      bg.count("versionName '8.2.0'") == 1 and bg.count("versionCode 69") == 1)

# ---- v8.2.0: adaptive budgets (NCIE v0.9.3) + RAG-sourced memory ----
check("v8.2.0: adaptive planner adoptable (NcieKnowledge.kt)",
      nk.count("AdaptiveKernel") == 2 and nk.count("adoptAdaptivePlanner") == 2)
check("v8.2.0: learner boot upgrades the planner (NcieLearn.kt)",
      nl.count("adoptAdaptivePlanner(l)") == 1)
check("v8.2.0: memory records carry their RAG sources (NcieLearn.kt)",
      nl.count("sources: List<String>") == 1 and nl.count("quality(clean, sources)") == 1)
check("v8.2.0: per-turn sources flow (MainActivity.kt + NcieChat.kt)",
      ma.count("lastAnswerSources") == 2 and nc.count("lastAnswerSources") == 2)
check("v8.2.0: knowsTopic probe synced (kernel learn files)",
      pl.count("knowsTopic") == 2 and lr.count("knowsTopic") == 2)
check("v8.2.0: the Mastery Rule synced (AdaptiveKernel.kt)",
      ak.count("THE MASTERY RULE") == 1)

# ---- v8.1.0: kernel optimization sync (NCIE v0.9.2) + app-side wiring ----
check("v8.1.0: learner canon-token cache kernel-side (PersistentLearner.kt)",
      pl.count("v0.9.2 fix") == 1 and pl.count("statsData") == 1)
check("v8.1.0: knowledge store inverted index (KnowledgeStore.kt)",
      kstore.count("reindexLocked") == 5)
check("v8.1.0: wiki store prepared index kernel-side (WikiStore.kt)",
      ws.count("fun prepare") == 1 and ws.count("PreparedIndex") == 5)
check("v8.1.0: debounced learner disk (NcieLearn.kt)",
      nl.count("DebouncedDisk") == 3 and nl.count("flushNow") == 4)
check("v8.1.0: graduated doc names via the kernel builder (NcieLearn.kt)",
      nl.count("docNameOf") == 1)
check("v8.1.0: single prepared WikiStore in the app (WikiCore.kt)",
      wc.count("prepared") == 5 and wc.count("WikiStore()") == 1)

# ---- v7.9.0: the memory screen joins the drawer; CI enforces version bumps ----
amf = open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
check("v7.9.0: memory screen in the drawer, not the launcher (AndroidManifest.xml)",
      amf.count("NOVA Memory") == 1 and
      amf.count("android.intent.category.LAUNCHER") == 1)
check("v7.9.0: memory row in the chat drawer (MainActivity.kt)",
      ma.count('drawerRow("Memory"') == 1)
wf = open(os.path.join(ROOT, ".github/workflows/nova-apk.yml"), encoding="utf-8").read()
check("v7.9.0: CI fails when app source changes without a version bump",
      wf.count("already released with this versionName") == 1)

# ---- v7.9.1: scrollable, decluttered drawer ----
check("v7.9.1: the drawer scrolls (drawerScroller wraps drawerPane)",
      ma.count("drawerScroller") == 9)
check("v7.9.1: secondary actions behind the More dialog",
      ma.count('drawerRow("More"') == 1 and
      ma.count('drawerRow("Help & Tips"') == 0 and
      ma.count('drawerRow("Check for updates"') == 0 and
      ma.count('"Check for updates")') == 1)

# ---- v7.9.2: home screen alignment + chats header polish ----
check("v7.9.2: home chips left-aligned (icon leads, text follows)",
      ma.count("Gravity.CENTER_VERTICAL or Gravity.START") == 1)
check("v7.9.3: the smart button sits right of the input",
      ma.index("pill.addView(sendBtn") > ma.index("pill.addView(input,"))
check("v7.9.2: chats header matches the other screens (20f title, 36dp back)",
      ca.count("textSize = 20f") >= 1 and ca.count("dp(36), dp(36)") >= 1)

# ---- v7.9.3: one smart button - mic when empty, send when typed ----
check("v7.9.3: smart button morphs between mic and send",
      ma.count("micBtn") == 0 and
      ma.count("icon(R.drawable.ic_mic, textDim)") == 1 and
      ma.count("sendBtn.setOnClickListener { startSpeech() }") == 1 and
      ma.index("icon(R.drawable.ic_send, Color.WHITE)") <
      ma.index("icon(R.drawable.ic_mic, textDim)"))

# ---- v8.0.0: UI overhaul - midnight palette + cards everywhere ----
check("v8.0.0: midnight palette in NovaTheme (init + dark apply)",
      nt.count("#12141D") == 2 and nt.count("#6C9CFF") == 2)
check("v8.0.0: replies live in cards (surface + hairline border)",
      ma.count("replies live in cards now") == 1 and
      ma.count("setCornerRadius(dp(ctx, 18).toFloat())") == 1)
check("v8.0.0: user bubble is a gradient now",
      ma.count("intArrayOf(NovaTheme.accent, NovaTheme.accentDeep)") == 1)
check("v8.0.0: model label chip in the header",
      ma.count("wears a chip now") == 1)
check("v8.0.0: chats and memory rows are cards",
      ca.count("chat rows are cards now") == 1 and
      mea.count("learned facts live in cards now") == 1)

# ---- v7.7.0: UI refresh (graphite + iris palette, Inter typeface, all screens) ----
sty = open(os.path.join(ROOT, "android/app/src/main/res/values/styles.xml"), encoding="utf-8").read()
nt = load("NovaTheme.kt")
check("v7.7.0: Inter applied app-wide via the theme", sty.count("@font/inter") == 1)
check("v7.7.0: theme chrome matches graphite dark", sty.count("#0A0B0E") == 3)
fam = open(os.path.join(ROOT, "android/app/src/main/res/font/inter.xml"), encoding="utf-8").read()
check("v7.7.0: font family ships 4 weights", fam.count("inter_") == 4)
for w in ("inter_regular", "inter_medium", "inter_semibold", "inter_bold"):
    check("v7.7.0: %s.ttf present" % w,
          os.path.exists(os.path.join(ROOT, "android/app/src/main/res/font", w + ".ttf")))
check("v8.0.0: midnight palette installed (was graphite + iris)",
      nt.count("#6C9CFF") == 2 and nt.count("#06070B") == 2)
check("v7.7.0: old navy accent gone from the palette",
      nt.count("#5B9BFF") == 0 and nt.count("#2E6BE6") == 0)
check("v7.7.0/v8.0.0: send button + user bubble are gradients",
      ma.count("GradientDrawable.Orientation.TL_BR") == 3)
check("v7.7.0: drawer widened (hide + layout)", ma.count("dp(304)") == 2)
check("v7.7.0: header buttons enlarged", ma.count("dp(36), dp(36)") == 2)
check("v7.7.0: no hardcoded navy stroke left", ma.count("#28314A") == 0)
check("v7.7.0: bold resolves inside the Inter family",
      ma.count("setTypeface(typeface, Typeface.BOLD)") == 2)
check("v7.7.0: rounded ripples on chips, round buttons and drawer rows",
      ma.count("rippleOverlay(GradientDrawable().apply {") == 5)
check("v7.7.0: ChatsActivity navy remnants gone",
      ca.count("#1A2030") == 0 and ca.count("#1F2635") == 0
      and ca.count("Typeface.DEFAULT_BOLD") == 0)
check("v7.7.0: SettingsActivity legacy red replaced",
      sa.count("#FF6B6B") == 0 and sa.count("Typeface.DEFAULT_BOLD") == 0)
check("v7.7.0: Knowledge/Exams/Models screens on Inter bold",
      ka.count("Typeface.DEFAULT_BOLD") == 0 and ea.count("Typeface.DEFAULT_BOLD") == 0
      and moa.count("Typeface.DEFAULT_BOLD") == 0)

# ---- old code that must be GONE (a skipped patch leaves these behind) ----
check("old 5-8-sentence summarizer prompts removed", ma.count("5-8 detailed sentences") == 0)
check("old flash reloads in summarizers removed", ma.count("NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath") == 0)

# ---- the send-order fix: no-model tools BEFORE ensureModelReady ----
i_tools = nc.find('if (solveArithmetic(text)) { input.setText("")')
i_model = nc.find("if (!ensureModelReady()) return")
check("v7.4/v7.6 order: calculator/phone commands before model check",
      i_tools != -1 and i_model != -1 and i_tools < i_model)

# ---- structural sanity: braces/parens balance + no invalid escapes ----
def structural(f, text):
    i, n = 0, len(text)
    braces = parens = 0
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif c == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
        elif c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
        else:
            if c == "{":
                braces += 1
            elif c == "}":
                braces -= 1
            elif c == "(":
                parens += 1
            elif c == ")":
                parens -= 1
            i += 1
    check("%s: braces balanced" % f, braces == 0)
    check("%s: parentheses balanced" % f, parens == 0)

for f, text in [("MainActivity.kt", ma), ("ncie/android/NcieChat.kt", nc), ("ncie/android/NcieLearn.kt", nl), ("ncie/android/NcieKnowledge.kt", nk), ("ncie/learn/Learner.kt", lr), ("ncie/learn/PersistentLearner.kt", pl), ("ncie/learn/LearnedFact.kt", lf), ("Backup.kt", bk), ("NotifBrain.kt", nb), ("KnowledgeActivity.kt", ka)]:
    structural(f, text)
    bad = []
    for m in re.finditer(r'"(?:[^"\\]|\\.)*"', text):
        for e in re.finditer(r"\\(.)", m.group(0)):
            if e.group(1) not in "tbnr\"'\\$su":
                bad.append(e.group(1))
    check("%s: no invalid string escapes" % f, not bad)

print("")
if fails:
    print("VERIFY FAILED: %d problem(s)" % len(fails))
    for x in fails:
        print(" - " + x)
    sys.exit(1)
print("VERIFY OK: all patch markers present, code structure sane")
