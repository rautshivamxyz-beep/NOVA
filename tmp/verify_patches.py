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
FILES = ["MainActivity.kt", "KnowledgeActivity.kt"]
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

def load_test(f):
    # v9.33.34: the pure-JVM unit tests live in the test source set.
    return open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/" + f), encoding="utf-8").read()


ma = load("MainActivity.kt") + load("ChatText.kt") + load("MessageAdapter.kt")
nc = load("ncie/android/NcieChat.kt") + load("ncie/android/NcieChatExt.kt")
# v9.4.0 "Audit Fixes II": Backup.kt (the legacy JSON backup) is deleted -
# the full zip backup in BackupActivity replaced it, so its checks are
# retired here with it.
ka = load("KnowledgeActivity.kt")
ca = load("ChatsActivity.kt")
sa = load("SettingsActivity.kt")
ea = load("ExamsActivity.kt")
moa = load("ModelsActivity.kt")
mea = load("MemoryActivity.kt")
nt = load("NovaTheme.kt")
# v9.17.0 "Leftovers"
lo = load("ncie/android/NcieLeftovers.kt")
lr2 = load("LeftoverReceiver.kt")

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
    ("v7.5.1/9.33.1 instant resets", "NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt)", 7),
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
    ("v7.3 greeting fast-path (use)", "SMALLTALK_REGEX", 3),
    ("v7.4 chip prompt set (use)", "CHIP_PROMPTS", 3),
    ("v7.4 chip routing check", "val isChip = CHIP_PROMPTS.contains(text)", 1),
    ("v7.5/9.33.5 lean wiki cap", "val cap = if (lean) 1400 else 1800", 1),
    ("NCIE 6: kernel context-budget gate in the chat turn", "NcieKnowledge.leanContext(text)", 1),
    # v9.33.52: the rule moved into ModelProfile so the health screen can report
    # it and a test can pin it - the marker follows it there.
    ("v8.3.1 tiny detection parses the parameter count", "ModelProfile.isTiny(mlabel)", 1),
    ("v7.5.1/NCIE-6 lean notes caps", "if (lean) 1200 else 2400", 4),
    ("v7.6 greeting context reset", "greeting sent into a dirty/stale context", 1),
    ("v7.6 notes relevance gate", "relevance gate - one shared word", 1),
    ("v7.6 input cleared only when consumed", 'if (solveArithmetic(text)) { input.setText("")', 1),
    ("Stage 5 collapse: the moved body is one function", "fun MainActivity.ncieSend(", 1),
    ("Stage 5 collapse: body reached the act capture", "val act = this", 3),
    ("NCIE 7: Smart Skip recall in the chat turn", "NcieLearn.recall(this, text)", 1),
]
for name, marker, n in NC_MARKERS:
    check("%s (x%d)" % (name, n), nc.count(marker) == n)
check("Stage 5 collapse: send() is the thin dispatch", ma.count("ncieSend()") == 1)
check("v7.6 streaming turn through NovaEngineAdapter (all 6 generations now)", ma.count("NovaEngineAdapter.stream(") == 6)

# v9.33.20: NotifBrain was a second NotificationListenerService that was
# never registered (the manifest kept only NovaListener), so its code never
# ran. The dead file is gone; NovaListener is the single source of truth.
check("v9.33.20: the dead NotifBrain file is gone",
      not os.path.exists(os.path.join(ROOT, BASE + "NotifBrain.kt")))
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
of_ = load("ncie/android/OnlineFetch.kt")
nsk = load("ncie/android/NcieSkills.kt")
cov = load("ncie/verify/Coverage.kt")
sk = load("ncie/skill/SkillStore.kt")
gs = load("ncie/learn/GapStore.kt")
np_ = load("ncie/android/NcieProfile.kt")
fla = load("FetchLogActivity.kt")
html = load("ncie/knowledge/HtmlText.kt")
skl = open(os.path.join(ROOT, "android/app/src/main/assets/skills.txt"), encoding="utf-8").read()
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

# ---- build.gradle: guard against the srdDirs corruption seen on 2026-09-26 ----
bg = open(os.path.join(ROOT, "android/app/build.gradle"), encoding="utf-8").read()
check("build.gradle: jniLibs srcDirs intact (no typo corruption)",
      bg.count("jniLibs.srcDirs") == 1 and bg.count("srdDirs") == 0)
# ---- v8.5.0: live search, code mode, fetch log, profile tool ----
check("v8.5.0: live web search - Wikipedia first, DuckDuckGo fallback (OnlineFetch)",
      of_.count("duckduckgo") == 3 and of_.count("HtmlText") == 6)
check("v8.5.0: every fetch logged (OnlineFetch)",
      of_.count("fetch_log") == 3)
check("v8.5.0: HtmlText synced from NCIE v0.9.5",
      html.count("codeBlocks") == 2)
check("v8.5.0: the profile tool intercepts the question (NcieChat + NcieProfile)",
      nc.count("answerProfile") == 1 and np_.count("memorySnapshot") == 1 and np_.count("PROFILE_Q") == 2)
check("v8.5.0: wiki background only on knowledge-seeking turns (NcieChat)",
      nc.count("val seek =") == 1)
check("v8.5.0: the Fetches screen (FetchLogActivity + WikiCore + manifest + drawer)",
      fla.count("removeArticle") == 2 and wc.count("removeArticle") == 1 and
      wc.count("articleText") == 1 and open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"), encoding="utf-8").read().count("FetchLogActivity") == 1 and
      ma.count("Fetches") == 1)
check("v8.5.0: hands gap-fill - timers and email drafts (MainActivity)",
      ma.count("ACTION_SET_TIMER") == 1 and ma.count("Email drafted") == 1)
check("v8.5.0: code mode skill ships (skills.txt, 6 skills)",
      skl.count("[skill]") == 6)

# ---- v8.5.1: the Fetches screen hotfix ----
# ---- v8.5.2: natural phone commands ----
check("v8.5.2: polite fillers stripped repeatedly (MainActivity)",
      ma.count("repeat(5)") == 1 and ma.count("kindly") == 1)
check("v8.5.2: wake-me-up joins the alarm (MainActivity)",
      ma.count("wake") == 3)
check("v8.5.2: volume control (MainActivity)",
      ma.count("AUDIO_SERVICE") == 1 and ma.count("volume mute") == 2)
check("v8.5.2: phone help in chat (MainActivity)",
      ma.count("Here's what I can do - just type it:") == 1 and ma.count("phoneHelp") == 2)

# ---- v8.5.3: the two bad replies fixed ----
check("v8.5.3: tell-me-about-me joins the profile question (NcieProfile)",
      np_.count("myself|me") == 1)
check("v8.5.3: self-description facts offered to memory (NcieChat)",
      nc.count("maybeRememberFacts") == 2 and nc.count("FACT_SCHOOL") == 2)
check("v8.5.3: wiki background passes the Coverage gate (NcieChat)",
      nc.count("Coverage.ratio") == 1)
check("v8.5.3: nothing-local study questions get the honest prompt (NcieChat)",
      nc.count("Never invent chapter contents") == 1)

# ---- v8.6.0: online discovery for any question ----
# ---- v9.10.0 "Revision Planner + Hardening": version bump + feature markers ----
# ---- v9.33.72: the version check asserts a FLOOR, never one snapshot ----
# The v9.33.70 form of this check pinned build.gradle to the exact release
# that added it ("9.33.70" / 238). The very next version bump - a routine
# action - then turned the whole verify red and blocked the pipeline for
# ~14 hours (run 761, 2026-10-25 18:13Z: "VERIFY FAILED: 1 problem(s) -
# v9.33.70: version bumped"). A version check must only ever assert that the
# version has NOT gone backwards.
def _version_parts(v):
    return tuple(int(x) for x in v.split(".") if x.isdigit())


def version_at_least(floor_name, floor_code):
    """True when build.gradle's versionName and versionCode are both at or
    above the floor. Fails closed: an unreadable or unparseable version is a
    failure, never a pass."""
    vn = re.search(r"versionName '([0-9]+(?:\.[0-9]+)*)'", bg)
    vc = re.search(r"versionCode (\d+)", bg)
    if not vn or not vc:
        return False
    cur, floor = _version_parts(vn.group(1)), _version_parts(floor_name)
    if not cur or not floor:
        return False
    width = max(len(cur), len(floor))
    cur += (0,) * (width - len(cur))
    floor += (0,) * (width - len(floor))
    return cur >= floor and int(vc.group(1)) >= floor_code


check("v9.33.70: version is at least 9.33.70 (238)", version_at_least("9.33.70", 238))

# v9.33.72: the same trap cannot come back. Every "version bumped" check from
# now on goes through version_at_least(); a literal exact pin in this file is
# itself a failure.
_pin_needle = 'count("version' + 'Name ' + chr(39)   # never written literally
_self_source = open(__file__, encoding="utf-8").read()
check("v9.33.72: no check pins versionName to one exact release",
      _self_source.count(_pin_needle) == 0)
check("v9.33.72: build.gradle declares exactly one versionName and versionCode",
      bg.count("versionName ") == 1 and bg.count("versionCode ") == 1)

# ---- v9.33.70: the fetched-source text rule pinned ----
# cleanSourceText is the single "turn a fetched source into a short, readable
# answer" rule (v9.33.7 merged the two drifted copies); a silent regression
# shows a search-result paragraph or a mid-sentence truncation.
check("v9.33.70: the fetched-source text rule is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/SourceTextTest.kt")).read().count("@Test") >= 5)

# ---- v9.33.69: the HTML-to-text converter pinned ----
# HtmlText turns every fetched page into the reading text NOVA stores and
# quotes; a silent regression dumps chrome into the knowledge base or mangles
# the entities the model answers from.
check("v9.33.69: the HTML-to-text converter is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/knowledge/HtmlTextTest.kt")).read().count("@Test") >= 8)

# ---- v9.33.68: the rolling-summary sanitizer pinned ----
# NcieSummary.sanitize strips the summarizer's own prompt markers before the
# summary is re-injected; a silent regression puts them back into the model's
# mouth ("New message:" / "Answer:").
check("v9.33.68: the rolling-summary sanitizer is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieSummaryTest.kt")).read().count("@Test") >= 6)

# ---- v9.33.67: the busy-reply line parser pinned ----
# NcieStyle.parseLines decides what actually reaches a contact in an
# away-message: a silent regression ships a list marker or a stray quote,
# or drops a good line when the length window moves.
check("v9.33.67: the busy-reply line parser is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieStyleTest.kt")).read().count("@Test") >= 6)

# ---- v9.33.57: the capability-gap log pinned ----
check("v9.33.57: the capability-gap log is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/learn/GapStoreTest.kt")).read().count("@Test") >= 6)

# ---- v9.33.56: more of the deterministic core pinned ----
# WikiStore.search picks the article handed to the model as background
# (the v7.8.1 whole-word title bonus lives there), and Distiller picks
# which learned facts become permanent knowledge (the v0.9.2 opt#8
# document-name fix lives there). Both are silent when they regress.
check("v9.33.56: the offline-wiki ranking is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/knowledge/WikiStoreTest.kt")).read().count("@Test") >= 4)
check("v9.33.56: the memory graduation ceremony is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/learn/DistillerTest.kt")).read().count("@Test") >= 6)

# ---- v9.33.55: unit tests for the deterministic core ----
# The two pure functions with the widest blast radius were still
# untested: the app arithmetic gate (an instant, model-free answer - a
# silent regression either shows a wrong number or drops the turn to
# the LLM) and Coverage.ratio (the fetched/wiki background gate - a
# silent regression either admits junk or discards a good answer).
check("v9.33.55: the app arithmetic gate is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/ArithmeticToolTest.kt")).read().count("@Test") >= 6)
check("v9.33.55: the coverage gate is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/verify/CoverageTest.kt")).read().count("@Test") >= 5)
check("v9.33.55: the instant answer drops Java's %g trailing zeros",
      load("ncie/android/NcieArithmetic.kt").count("trimTrailingZeros(") >= 1 and
      load("ncie/android/NcieArithmetic.kt").count("trimEnd('0')") == 1)

# ---- v9.33.54: what makes it dumber ----
check("v9.33.54: the rules say what to do, not what not to do",
      load("Settings.kt").count("beginning with the answer itself") == 1 and
      load("Settings.kt").count("Put the code first and keep any note to one line") == 1 and
      load("Settings.kt").count("NEVER RECITE") == 0)
check("v9.33.54: the background is labelled for use, not for recital",
      load("ncie/android/NcieChat.kt").count("Background about the user - use it to inform your answer") == 1)
check("v9.33.54: the exam line reaches every model, lean profile included",
      load("ncie/android/NcieChat.kt").count("if (!tiny) {") == 0)

# ---- v9.33.53: the capability question, and the recital ----
check("v9.33.53: the capability question is caught in the wordings people use",
      ma.count("what things you can do") == 1 and
      ma.count("|^what") == 2)
# v9.33.54 restated this rule in the positive - the marker follows it.
check("v9.33.53: NOVA is told what to do with the background",
      load("Settings.kt").count("USE THE BACKGROUND") == 1 and
      load("Settings.kt").count("Use them silently") == 1)

# ---- v9.33.52: the "tiny" decision is one rule, and it is visible ----
check("v9.33.52: one definition of the lean profile",
      load("ncie/ModelProfile.kt").count("fun isTiny(") == 1 and
      load("ncie/android/NcieChat.kt").count("ModelProfile.isTiny(mlabel)") == 1 and
      load("ncie/android/NcieChat.kt").count("MODEL_PARAMS") == 0)
check("v9.33.52: and the health screen reports it",
      load("NovaHealth.kt").count("prompt profile: ") == 1 and
      load("NovaHealth.kt").count("ModelProfile.describe(label)") == 1)
check("v9.33.52: the rule is unit-tested",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/ModelProfileTest.kt")).read().count("@Test") >= 3)

# ---- v9.33.51: NOVA's own rules were labelled "NOT an instruction" ----
check("v9.33.51: the rulebook leads the prompt, outside the data block",
      # v9.33.62: the standing rules moved into the SYSTEM prompt; only the
      # turn-specific rules still lead the per-turn prompt here.
      load("ncie/android/NcieChat.kt").count("prompt = detailRule + codeTaskRule + skillPart + contextBlock + prompt") == 1 and
      load("ncie/android/NcieChat.kt").count("val contextData = knowledgePart + wikiPart + summaryPart") == 1)
check("v9.33.51: and it can never be truncated away",
      load("ncie/android/NcieChat.kt").count("experiencePart") == 1 and
      load("Settings.kt").count("CODE: when the user asks for code") == 1)

# ---- v9.33.62: the rules are cached in the system prompt; answers to the point ----
check("v9.33.62: the standing rules ride in the SYSTEM prompt (encoded once)",
      load("Settings.kt").count("const val RULEBOOK") == 1 and
      load("NovaEngine.kt").count("Settings.RULEBOOK") == 2 and
      load("ncie/android/NcieChat.kt").count("val qualityRules") == 0)
check("v9.33.62: answers are to the point by default",
      load("Settings.kt").count("Keep it to the point") == 1 and
      load("Settings.kt").count("Expand into steps, examples or extra detail only when") == 1)

# ---- v9.33.50: the three findings the audit got right ----
check("v9.33.50: the user's own instructions never go to a remote machine",
      load("ncie/PrivatePrompt.kt").count("fun systemPromptFor(") == 1 and
      load("RemoteEngine.kt").count("PrivatePrompt.systemPromptFor(systemPrompt(), privateOnly)") == 1)
check("v9.33.50: plain http to a public address is refused",
      load("ncie/RemoteHost.kt").count("fun isInsecurePublic(") == 1 and
      load("RemoteEngine.kt").count("RemoteHost.isInsecurePublic(") == 1)
check("v9.33.50: a discovered server is never switched on by itself",
      load("SettingsActivity.kt").count("settings.remoteEnabled = true") == 0)
check("v9.33.50: the model option says what it actually is",
      open(os.path.join(ROOT, "setup-oracle.sh")).read().count("MODEL=7b") == 1 and
      open(os.path.join(ROOT, "setup-oracle.sh")).read().count("MODEL=8b") == 0)
check("v9.33.50: both new guarantees are unit-tested",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/RemoteHostTest.kt")).read().count("@Test") >= 6 and
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/PrivatePromptTest.kt")).read().count("systemPromptFor") >= 2)

# ---- v9.33.49: the instance has to survive Oracle ----
check("v9.33.49: the guide carries Oracle's own fix for reclamation",
      open(os.path.join(ROOT, "REMOTE_SERVER.md")).read().count("Pay As You Go (PAYG)") == 1)
check("v9.33.49: and the 2-OCPU trap that deletes every A1 instance",
      open(os.path.join(ROOT, "REMOTE_SERVER.md")).read().count("within\n2 OCPUs and 12 GB") == 1)
check("v9.33.49: the server names its thread count",
      open(os.path.join(ROOT, "setup-oracle.sh")).read().count("-t $(nproc)") == 1)

# ---- v9.33.48: an 8B model, honestly offered ----
# v9.33.50: the option was called 8b and never was one - it downloads a 7B.
check("v9.33.48: the script can actually run a big model",
      open(os.path.join(ROOT, "setup-oracle.sh")).read().count("MODEL=7b") >= 1 and
      open(os.path.join(ROOT, "setup-oracle.sh")).read().count("Qwen2.5-7B-Instruct-Q4_K_M.gguf") == 1)
check("v9.33.48: it refuses a model the machine cannot hold",
      open(os.path.join(ROOT, "setup-oracle.sh")).read().count("MemTotal") == 1 and
      open(os.path.join(ROOT, "setup-oracle.sh")).read().count("NEED_MB") >= 3)
check("v9.33.48: the guide says free+always-on+fast is pick-two",
      open(os.path.join(ROOT, "REMOTE_SERVER.md")).read().count("30 GPU-hours a week") == 1)

# ---- v9.33.47: the cloud path is not a second-class path ----
check("v9.33.47: the find button does not mislead a cloud user",
      load("SettingsActivity.kt").count("R.string.remote_find_hint") == 1 and
      open(os.path.join(ROOT, "android/app/src/main/res/values/strings.xml")).read().count("Using a cloud server like Oracle? Skip this") == 1)
check("v9.33.47: the guide points a laptop-less reader at the cloud path",
      open(os.path.join(ROOT, "REMOTE_SERVER.md")).read().count("Not using a laptop at all?") == 1)

# ---- v9.33.46: the Oracle move, server side ----
check("v9.33.46: one command sets up the whole Oracle server",
      os.path.exists(os.path.join(ROOT, "setup-oracle.sh")) and
      open(os.path.join(ROOT, "setup-oracle.sh")).read().count("--api-key $KEY") == 1 and
      open(os.path.join(ROOT, "setup-oracle.sh")).read().count("systemctl enable nova-server") == 1)
check("v9.33.46: a cloud server always gets a password",
      open(os.path.join(ROOT, "setup-oracle.sh")).read().count("/dev/urandom") == 1)

# ---- v9.33.45: only the question leaves the phone ----
check("v9.33.45: the private blocks are stripped, and the set is pinned",
      load("ncie/PrivatePrompt.kt").count("fun strip(prompt: String): String?") == 1 and
      load("ncie/PrivatePrompt.kt").count("(The user shared a document titled") == 2 and
      load("ncie/PrivatePrompt.kt").count("if (out.contains(m)) return null") == 2)
check("v9.33.45: private-only is ON by default",
      load("Settings.kt").count("getBoolean(KEY_REMOTE_PRIVATE, true)") == 1)
check("v9.33.45: an unclean prompt is never sent",
      load("RemoteEngine.kt").count("PrivatePrompt.strip(prompt) ?: throw") == 1)
check("v9.33.45: the guarantee is unit-tested",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/PrivatePromptTest.kt")).read().count("@Test") >= 8)

# ---- v9.33.44: a server reachable from outside must have a password ----
check("v9.33.44: the password is sent on the call AND on the search",
      load("RemoteEngine.kt").count('setRequestProperty("Authorization", "Bearer " + key())') == 2 and
      load("Settings.kt").count("var remoteKey") == 1)
check("v9.33.44: the start scripts pick up password.txt",
      open(os.path.join(ROOT, "start-nova-server.bat")).read().count("--api-key") == 1 and
      open(os.path.join(ROOT, "start-nova-server.sh")).read().count("--api-key") == 1)

# ---- v9.33.43: nobody types an IP address ----
check("v9.33.43: the phone finds the computer by itself",
      load("RemoteEngine.kt").count("fun discover(") == 1 and
      load("RemoteEngine.kt").count("/v1/models") >= 1 and
      load("RemoteEngine.kt").count("fun find(") == 1 and
      load("SettingsActivity.kt").count("private fun findComputer()") == 1)
check("v9.33.43: one double-click starts the laptop half",
      os.path.exists(os.path.join(ROOT, "start-nova-server.bat")) and
      os.path.exists(os.path.join(ROOT, "start-nova-server.sh")))

# ---- v9.33.42: a bigger model on the user's own network ----
check("v9.33.42: the bigger machine is preferred, this phone is the fallback",
      load("NovaEngine.kt").count("if (RemoteEngine.enabled())") == 1 and
      load("NovaEngine.kt").count("if (!got)") == 1)
check("v9.33.42: the client speaks the llama-server protocol",
      load("RemoteEngine.kt").count("/v1/chat/completions") >= 2 and
      load("RemoteEngine.kt").count("text/event-stream") == 1)
check("v9.33.42: the cleartext exception is explained where it is made",
      open(os.path.join(ROOT, "android/app/src/main/res/xml/network_security_config.xml"))
        .read().count("Borrowed brain") == 1)
check("v9.33.42: the laptop setup is written down",
      os.path.exists(os.path.join(ROOT, "REMOTE_SERVER.md")))

# ---- v9.33.41: the app corrects itself on a regenerate ----
check("v9.33.41: a rejected answer leaves memory, fact and cache",
      ma.count("NcieLearn.memoryForget(expQ)") == 1 and
      ma.count("NcieEngines.logExperience(this@MainActivity, expQ)") == 1)

# ---- v9.33.40: the measurement travels, so the catalogue improves ----
check("v9.33.40: the measurement is kept so the report can carry it",
      load("Settings.kt").count("var lastSpeedReport") == 1 and
      load("SpeedTest.kt").count("lastSpeedReport =") == 1)
check("v9.33.40: the report carries content-free numbers only",
      load("NovaHealth.kt").count("measured speed: ") == 1 and
      load("NovaHealth.kt").count("auto-replies sent today: ") == 1)

# ---- v9.33.39: a content-free report the user sends to improve NOVA ----
check("v9.33.39: the diagnostics report says what it does NOT contain",
      load("NovaHealth.kt").count("fun diagnostics(ctx: Context): String") == 1 and
      load("NovaHealth.kt").count("No messages, no chats, no personal data") == 1)
check("v9.33.39: the report is user-sent, never auto-sent",
      load("HealthActivity.kt").count("Intent.ACTION_SEND") == 1 and
      load("HealthActivity.kt").count("createChooser") == 1 and
      load("HealthActivity.kt").count("http") == 0)

# ---- v9.33.38: right answers, and a profile that is not a trivia dump ----
check("v9.33.38: the OFFLINE Wikipedia path applies the entity rule too",
      load("WikiCore.kt").count("EntityMatch.isEntityQuestion(query)") == 1 and
      load("WikiCore.kt").count("EntityMatch.titleMatchesEntity(it.title, query)") == 1)
check("v9.33.38: one shared lookup-shape rule, not two copies",
      load("ncie/knowledge/EntityMatch.kt").count("fun isEntityQuestion(") == 1 and
      load("ncie/android/OnlineFetch.kt").count("= EntityMatch.isEntityQuestion(q)") == 1)
check("v9.33.38: the profile shows earned facts, not a trivia dump",
      load("ncie/android/NcieProfile.kt").count("it.interactions >= 2 || it.score >= 0.6") == 1)
check("v9.33.38: the shared rule is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/knowledge/EntityMatchTest.kt")).read().count("isEntityQuestion") >= 6)

# ---- v9.33.37: a measured speed test (real tokens, not chars/4) ----
check("v9.33.37: the speed test measures a real generation",
      load("SpeedTest.kt").count("NovaEngineAdapter.stream(PROMPT, TOKENS)") == 1 and
      load("SpeedTest.kt").count("tokensPerSecond") >= 1)
check("v9.33.37: a benchmark never costs the chat its memory",
      load("SpeedTest.kt").count("NovaEngine.contextResetExternally = true") == 1 and
      load("NovaEngine.kt").count("var contextResetExternally") == 1)
check("v9.33.37: the Models screen offers the measurement",
      load("ModelsActivity.kt").count("SpeedTest.run(") == 1)
check("v9.33.37: the speed-test strings have a Hindi translation",
      open(os.path.join(ROOT, "android/app/src/main/res/values-hi/strings.xml")).read().count('name="speed_test">') == 1)

# ---- v9.33.36: unit tests for the ADAPTIVE planner ----
akt = load_test("plan/AdaptiveKernelTest.kt")
check("v9.33.36: the ADAPTIVE planner is pinned by a unit test",
      akt.count("class AdaptiveKernelTest") == 1 and akt.count("AdaptiveKernel(") >= 1)
check("v9.33.36: both adaptive rules are exercised",
      akt.count("fun aHalfLearnedTopicGetsOneMoreTier") == 1 and
      akt.count("fun aStrugglingTopicGetsOneMoreTier") == 1 and
      akt.count("fun theStruggleMatchIsWholeWordNotSubstring") == 1)

# ---- v9.33.35: unit tests for the VERIFY gate ----
vf = load_test("verify/VerifierTest.kt")
check("v9.33.35: the VERIFY gate is pinned by a unit test",
      vf.count("class VerifierTest") == 1 and vf.count("MathVerifier(") >= 1)
check("v9.33.35: both halves of verify are exercised",
      vf.count("fun anAnswerWithTheComputedValuePasses") == 1 and
      vf.count("fun anAnswerThatDriftsFromItsSourcesFails") == 1 and
      vf.count("fun leakedChatBoilerplateFails") == 1)

# ---- v9.33.34: unit tests for the ANALYZE and PLAN gates ----
an = load_test("analyze/AnalyzerTest.kt")
dk = load_test("plan/DecisionKernelTest.kt")
check("v9.33.34: the ANALYZE gate is pinned by a unit test",
      an.count("class AnalyzerTest") == 1 and an.count("RuleBasedAnalyzer()") == 1 and
      an.count("fun aHindiOrMarathiQuestionIsKnowledgeSeekingToo") == 1)
check("v9.33.34: the PLAN gate is pinned by a unit test",
      dk.count("class DecisionKernelTest") == 1 and dk.count("DecisionKernel(") >= 1 and
      dk.count("fun thinkingAndContextBudgetsScaleInThreeTiers") == 1)
check("v9.33.34: the four routes are all exercised",
      dk.count("Route.CACHE") == 1 and dk.count("Route.TOOL,") == 1 and
      dk.count("Route.TOOL_THEN_LLM") == 1 and dk.count("Route.LLM") == 1)

# ---- v9.33.33: Lite mode for low-RAM phones ----
check("v9.33.33: one place decides the phone is small",
      load("DeviceCapabilities.kt").count("fun isLowRam(") == 1 and
      load("DeviceCapabilities.kt").count("LOW_RAM_GB = 5.0") == 1)
check("v9.33.33: the embedder is skipped on a low-RAM phone",
      load("ncie/android/NcieEmbed.kt").count("DeviceCapabilities.isLowRam(ctx)") == 1)
check("v9.33.33: the embedder is released under memory pressure",
      load("ncie/android/NcieEmbed.kt").count("fun release()") == 1 and
      ma.count("TRIM_MEMORY_RUNNING_CRITICAL") == 1)
check("v9.33.33: lite mode is reported, not silent",
      load("NovaHealth.kt").count('"\\nlite mode: "') == 1)

# ---- v9.33.32: compaction guards overflow instead of capping memory ----
check("v9.33.32: compaction fires on the context budget, not every 8 messages",
      ma.count("historyChars > NcieTune.CONTEXT_BUDGET_CHARS") == 1 and
      ma.count("messages.size - compactedAtCount >= 12") == 1)
check("v9.33.32: the carry keeps more turns raw and summarises structurally",
      nc.count("takeLast(10).joinToString") == 2 and
      ma.count("FACTS: what the user told you") == 1)

# ---- v9.33.31: chat memory + LFM sampling ----
check("v9.33.31: the LFM profile uses the vendor's sampling numbers",
      load("ncie/android/NcieTune.kt").count('"temperature" to 0.2') == 1 and
      load("ncie/android/NcieTune.kt").count('"top_k" to 50.0') == 1)
check("v9.33.31: the engine keeps the conversation (no per-turn reset)",
      ma.count("if (newBubble && (needsContextCarry || NovaEngine.contextResetExternally))") == 1)
check("v9.33.31: history is replayed only when the engine lacks it",
      nc.count("needsContextCarry && currentChat.messages.isNotEmpty() -> {") == 1 and
      nc.count("needsContextCarry && compactSummary != null") == 1)
check("v9.33.31: the trim never deletes the user's history",
      load("ncie/android/NcieTune.kt").count("msgs.addAll(keep)") == 0 and
      load("ncie/android/NcieTune.kt").count("msgs.clear()") == 0)
check("v9.33.31: the bigger history budget is in place",
      load("ncie/android/NcieTune.kt").count("CONTEXT_BUDGET_CHARS = 10000") == 1)

check("v9.33.30: Knowledge and Backup read from resources",
      load("KnowledgeActivity.kt").count("getString(R.string.") >= 18 and
      load("BackupActivity.kt").count("getString(R.string.") >= 13)

check("v9.33.29: Setup, Update and FetchLog read from resources",
      load("SetupActivity.kt").count("getString(R.string.") >= 15 and
      load("UpdateActivity.kt").count("getString(R.string.") >= 10 and
      load("FetchLogActivity.kt").count("getString(R.string.") >= 7)

check("v9.33.28: the Planner screen reads from resources",
      load("PlannerActivity.kt").count("getString(R.string.") >= 25)

# ---- v9.33.27: the Chats, Health, Exams and Memory screens are translatable ----
check("v9.33.27: the four screens read from resources",
      load("ChatsActivity.kt").count("getString(R.string.") >= 10 and
      load("HealthActivity.kt").count("getString(R.string.") >= 5 and
      load("ExamsActivity.kt").count("getString(R.string.") >= 12 and
      load("MemoryActivity.kt").count("getString(R.string.") >= 8)
check("v9.33.27: every string carries a Hindi translation",
      open(os.path.join(ROOT, "android/app/src/main/res/values-hi/strings.xml")).read().count("<string name=") >= 100)

# ---- v9.33.26: the Settings screen is translatable (resources + Hindi) ----
check("v9.33.26: the Settings screen reads from resources",
      load("SettingsActivity.kt").count("getString(R.string.") >= 40)
check("v9.33.26: the strings live in strings.xml with a Hindi translation",
      open(os.path.join(ROOT, "android/app/src/main/res/values/strings.xml")).read().count("<string name=") >= 50 and
      os.path.exists(os.path.join(ROOT, "android/app/src/main/res/values-hi/strings.xml")))

# ---- v9.33.21: every icon-only control carries a spoken label ----
check("v9.33.21: every icon-only control carries a spoken label",
      ma.count("contentDescription = ") >= 8 and
      load("NovaUi.kt").count('contentDescription = "Back"') == 1 and
      load("ChatsActivity.kt").count("contentDescription = getString(R.string.action_back)") == 1 and
      load("PlannerActivity.kt").count("contentDescription = getString(R.string.action_back)") == 1 and
      load("KnowledgeActivity.kt").count("contentDescription = ") == 2)

# ---- v9.33.22: the multilingual pass, completed (strength gate + one tokenizer) ----
check("v9.33.22: the strength gate skips the English embedder for a non-Latin question",
      load("ncie/android/NcieGround.kt").count("non-Latin stays keyword-only") == 1)
check("v9.33.22: the notes norm uses the one Unicode tokenizer",
      nk.count("Tokens.norm(t)") == 1 and nk.count("[^a-z0-9]") == 0)

# ---- v9.33.23: Hindi/Marathi notes retrieval - short words + stopwords ----
check("v9.33.23: short non-Latin words survive the query tokenizer",
      kstore.count("w.length == 2 && w.any { it.code > 127 }") == 1)
check("v9.33.23: the retrieval stopword lists carry the Devanagari function words",
      kstore.count('"का"') == 1 and
      load("ncie/android/NcieGround.kt").count('"का"') == 1)

# ---- v9.33.24: Hindi/Marathi WIKI retrieval - the same two fixes ----
check("v9.33.24: the wiki query tokenizers keep short non-Latin words",
      ws.count("it.length == 2 && it.any { c -> c.code > 127 }") == 1 and
      wc.count("it.length == 2 && it.any { c -> c.code > 127 }") == 1)
check("v9.33.24: the wiki stopword lists carry the Devanagari function words",
      ws.count('"का"') == 1 and wc.count('"का"') == 1)

# ---- v9.33.25: Hindi/Marathi KEYWORD extraction - short words reach the terms ----
check("v9.33.25: the keyword extractor keeps short non-Latin words",
      open(os.path.join(ROOT, BASE + "ncie/Tokens.kt")).read().count("minLen > 2 && it.length == 2") == 1)

# ---- v9.33.20: delete a message; the smoke test is wired into CI ----
check("v9.33.20: a message can be deleted from the chat",
      ma.count("fun deleteMessage(") == 1 and
      ma.count('options += "Delete"') == 1 and
      ma.count('"Delete" -> onDelete?.invoke(m)') == 1)
check("v9.33.20: the smoke test is wired into CI",
      "smoke_test.py" in open(os.path.join(ROOT, ".github/workflows/nova-test.yml")).read())

# ---- v9.33.19: one Unicode-aware tokenizer; Hindi/Marathi retrieval works ----
check("v9.33.19: one Unicode-aware tokenizer (Tokens)",
      open(os.path.join(ROOT, BASE + "ncie/Tokens.kt")).read().count("fun split(") == 1)
check("v9.33.19: the retrieval tokenizers are Unicode-aware",
      load("ncie/analyze/Analyzer.kt").count("Tokens.words(") == 1 and
      load("ncie/knowledge/WikiStore.kt").count("Tokens.") == 3 and
      load("ncie/android/NcieGround.kt").count("Tokens.norm(") == 3 and
      load("WikiCore.kt").count("Tokens.split(") == 1)
check("v9.33.19: a Devanagari question is knowledge-seeking",
      nc.count("u0900-") == 1 and load("ncie/analyze/Analyzer.kt").count("u0900-") == 1)
check("v9.33.19/22: the English embedder is skipped for non-Latin questions (both sites)",
      load("ncie/android/NcieGround.kt").count("LATIN.containsMatchIn(question)") == 2)
check("v9.33.19: the tokenizer has unit tests",
      os.path.exists(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/TokensTest.kt")))

# ---- v9.33.18: a joke needs no model; silent degradations shown; a11y labels ----
check("v9.33.18: a joke request is answered deterministically",
      nc.count("answerJoke(text)") == 1 and
      open(os.path.join(ROOT, BASE + "ncie/android/Jokes.kt")).read().count("fun at(") == 1)
check("v9.33.18: the health report shows the silent degradations",
      load("NovaHealth.kt").count("fun capabilities(") == 1 and
      load("NovaHealth.kt").count("capabilities(ctx)") >= 1)
check("v9.33.18: the primary controls carry a spoken label",
      ma.count("contentDescription = ") >= 5 and ma.count("fun setSendButton(") == 1)
check("v9.33.18: the joke set has real unit tests",
      os.path.exists(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/JokesTest.kt")))

# ---- v9.33.17 "one-phrase modes, honest about a failed save" ----
check("v9.33.17: the one-phrase modes report a failed save honestly",
      load("ncie/android/NcieModes.kt").count("Couldn't switch away mode") == 2)

# ---- v9.33.16 "the deterministic core, proven + honest" ----
check("v9.33.16: focus mode does not claim success when the write fails",
      load("ncie/android/NcieFocus.kt").count("Couldn't start focus mode") == 1)
check("v9.33.16: the deadline date parser is unit-tested",
      load("ncie/android/NcieDeadlines.kt").count("internal fun dateAt(") == 1 and
      os.path.exists(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieDeadlinesTest.kt")))

# ---- v9.33.15: nothing fails silently, fresh context for generation, name it exactly ----
check("v9.33.15: swallowed exceptions are recorded (NovaLog)",
      open(os.path.join(ROOT, BASE + "NovaLog.kt")).read().count("fun quiet(") == 1 and
      ma.count("NovaLog.quiet(") >= 40)
check("v9.33.15: the health report surfaces the swallowed count",
      load("NovaHealth.kt").count("NovaLog.summary()") == 1)
check("v9.33.15: a generation task starts a clean context",
      nc.count("freshForCode") == 3 and ma.count("var lastTurnWasCode") == 1)
check("v9.33.15: an entity is looked up as a page title first",
      load("ncie/android/OnlineFetch.kt").count("EntityMatch.entityPhrase(q)") == 1 and
      open(os.path.join(ROOT, BASE + "ncie/knowledge/EntityMatch.kt")).read().count("fun entityPhrase(") == 1)

# ---- v9.33.14 "reliability": right entity or nothing, no invented lyrics, real tests ----
check("v9.33.14: the entity-title gate ships (EntityMatch)",
      open(os.path.join(ROOT, BASE + "ncie/knowledge/EntityMatch.kt")).read().count("fun titleMatchesEntity(") == 1)
check("v9.33.14: OnlineFetch refuses an article that does not name the entity",
      load("ncie/android/OnlineFetch.kt").count("titleMatchesEntity(wiki.first, text)") == 1 and
      load("ncie/android/OnlineFetch.kt").count("does not name the thing asked about") == 1)
check("v9.33.14: a lyrics request is declined, not invented (NcieChat)",
      nc.count("answerLyrics(text)") == 1 and nc.count("can't give you song lyrics") == 1)
check("v9.33.14: the pure kernel has real unit tests",
      os.path.exists(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/execute/tools/CalculatorToolTest.kt")) and
      os.path.exists(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/knowledge/EntityMatchTest.kt")))
# ---- A2 "Deadline radar" (v9.33.9) ----
check("v9.33.9: the deadline radar exists",
      os.path.isfile(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NcieDeadlines.kt")))
check("v9.33.9: the radar is deterministic and offline (no model, no net)",
      load("ncie/android/NcieDeadlines.kt").count("object NcieDeadlines") == 1 and
      load("ncie/android/NcieDeadlines.kt").count("fun radar(") == 1)
check("v9.33.9: the radar command is wired into the chat turn",
      nc.count("answerDeadlines(text)") == 1 and
      nc.count("fun MainActivity.answerDeadlines(") == 1)
# ---- A3 "Weekly review" (v9.33.10) ----
check("v9.33.10: the weekly review exists",
      os.path.isfile(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NcieWeekly.kt")))
check("v9.33.10: the weekly review is deterministic and offline",
      load("ncie/android/NcieWeekly.kt").count("object NcieWeekly") == 1 and
      load("ncie/android/NcieWeekly.kt").count("fun review(") == 1)
check("v9.33.10: the weekly review command is wired into the chat turn",
      nc.count("answerWeekly(text)") == 1 and
      nc.count("fun MainActivity.answerWeekly(") == 1)
# ---- A4 "Watchdog" (v9.33.11) ----
check("v9.33.11: the watchdog exists",
      os.path.isfile(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NcieWatchdog.kt")))
check("v9.33.11: the watchdog is deterministic and offline (no model, no net)",
      load("ncie/android/NcieWatchdog.kt").count("object NcieWatchdog") == 1 and
      load("ncie/android/NcieWatchdog.kt").count("fun run(") == 1)
check("v9.33.11: the watchdog command is wired into the chat turn",
      nc.count("answerWatchdog(text)") == 1 and
      nc.count("fun MainActivity.answerWatchdog(") == 1)
# ---- A5 "Focus mode" (v9.33.12) ----
check("v9.33.12: focus mode exists",
      os.path.isfile(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NcieFocus.kt")))
check("v9.33.12: focus mode is deterministic and offline (no model, no net)",
      load("ncie/android/NcieFocus.kt").count("object NcieFocus") == 1 and
      load("ncie/android/NcieFocus.kt").count("fun active(") == 1)
check("v9.33.12: the focus command is wired into the chat turn",
      nc.count("answerFocus(text)") == 1 and
      nc.count("fun MainActivity.answerFocus(") == 1)
check("v9.33.12: focus mode silences the auto-responder (NovaListener)",
      load("NovaListener.kt").count("NcieFocus.active(this)") == 1)
# ---- A5 completion (v9.33.13): focus also holds the proactive nudges ----
check("v9.33.13: focus mode holds the spoken nudges (NovaJarvis)",
      load("ncie/android/NovaJarvis.kt").count("NcieFocus.active(ctx)") == 1)
check("v9.31.0: hands-free, nudges and triggers exist",
      os.path.isfile(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NovaJarvis.kt")) and
      load("ncie/android/NovaJarvis.kt").count("object NovaHandsfree") == 1 and
      load("ncie/android/NovaJarvis.kt").count("object NovaNudge") == 1 and
      load("ncie/android/NovaJarvis.kt").count("object NovaTriggers") == 1)
check("v9.31.0: NOVA can be the phone's assistant",
      open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml")).read().count("android.intent.action.ASSIST") == 1)
check("v9.31.0: the Jarvis commands are wired",
      load("ncie/android/NcieChat.kt").count("answerJarvis(text)") == 1)
check("v9.30.0: Skill Forge exists and appends a skill", open(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NcieSkillForge.kt")).read().count("source=nova") >= 1)
check("v9.30.0: Growth loop exists", os.path.isfile(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NcieEvolve.kt")))
check("v9.30.0: the forge is wired into the completed turn", open(os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")).read().count("NcieSkillForge.maybeForge") == 1)
check("v9.30.0: skills cache can reload after a forge", open(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NcieSkills.kt")).read().count("fun reload(") == 1)
check("v9.30.0: growth command wired", open(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NcieChat.kt")).read().count("answerSkills(text)") == 1)
check("v9.30.0: tool router knows the wider catalogue", open(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NciePlanner.kt")).read().count("\"calculator\" to") == 1)
# ---- v9.22.0 "Busy replies" ----
check("v9.22.0: the auto-responder sends up to N varied lines per sender",
      load("NovaListener.kt").count("count < st.autoReplyMax.coerceIn(1, 6)") == 1 and
      load("NovaListener.kt").count("lines[count % lines.size]") == 1 and
      load("NovaListener.kt").count("autoRepliedAt[who] = now2") == 1)
check("v9.22.0: the busy lines + the count live in Settings",
      load("Settings.kt").count("fun busyLines(") == 1 and
      load("Settings.kt").count("DEFAULT_BUSY_LINES") == 2 and
      load("Settings.kt").count("var autoReplyMax") == 1)
check("v9.22.0: every auto-reply is logged (so the health screen can show it)",
      load("NovaListener.kt").count("fun logAutoReply(") == 1 and
      load("NovaListener.kt").count("fun autoRepliesToday(") == 1)
# ---- v9.23.0 "The model for the messages" ----
check("v9.23.0: the model writes a reply to what they actually said",
      load("ncie/android/NcieStyle.kt").count("suspend fun busyReply(") == 1 and
      load("NovaListener.kt").count("NcieStyle.busyReply(svc, incoming)") == 1)
check("v9.23.0: the reply runs off the notification thread (with a timeout)",
      load("NovaListener.kt").count("replyScope.launch") == 1 and
      load("NovaListener.kt").count("withTimeoutOrNull(20000L)") == 1)
# ---- v9.24.0 "Wake for WhatsApp" ----
check("v9.24.0: a WhatsApp message wakes the model",
      load("ncie/android/NcieStyle.kt").count("suspend fun wakeAndReply(") == 1 and
      load("ncie/android/NcieStyle.kt").count("NovaEngine.load(ctx, path") == 1 and
      load("NovaListener.kt").count("NcieStyle.wakeAndReply(svc, incoming)") == 1 and
      load("NovaListener.kt").count('pkg == "com.whatsapp"') >= 2)
# ---- v9.25.0 "Privacy + honesty" ----
check("v9.25.0: the app lock hides the chat behind it (MainActivity)",
      ma.count("android.R.id.content") >= 3 and
      ma.count("never show the chat behind the lock") == 1)
check("v9.25.0: the 'not in my notes' banner is no longer written (MainActivity)",
      ma.count("do NOT announce the source") == 1 and
      ma.count("'From general knowledge (not in your notes):'") == 0)
# ---- v9.26.0 "Search + busy replies" ----
check("v9.26.0: the fetch sends a browser User-Agent (so DuckDuckGo answers)",
      load("NovaNet.kt").count("Chrome/122.0.0.0 Mobile Safari") == 1 and
      load("NovaNet.kt").count("NOVA-local-assistant/1.0 (offline study)") == 0 and
      load("NovaNet.kt").count("Accept-Language") == 1)
check("v9.26.0/9.32.2: a Devanagari (and any short) question is searched as written, not refused",
      "devanagari || text.trim().length <= 120" in of_)
check("v9.26.0: the busy-line prompt puts the TASK before the examples",
      load("ncie/android/NcieStyle.kt").count("TASK: ") == 1 and
      load("ncie/android/NcieStyle.kt").count("ONLY a style reference") == 1 and
      load("ncie/android/NcieStyle.kt").count("Each must be a STATEMENT, never a question") == 1 and
      load("ncie/android/NcieStyle.kt").count('filterNot { it.trimEnd().endsWith("?") }') == 1)
# ---- v9.27.0 "Indian voice" ----
check("v9.27.0: spoken replies prefer an Indian-English accent",
      load("NcieVoice.kt").count('Locale("en", "IN")') == 1 and
      load("NcieVoice.kt").count("LANG_NOT_SUPPORTED") >= 1 and
      load("NcieVoice.kt").count("tts?.language = def") == 1)
# ---- v9.28.0 "Best Indian voice" ----
check("v9.28.0: the best offline en-IN voice is chosen",
      load("NcieVoice.kt").count("isNetworkConnectionRequired") >= 1 and
      load("NcieVoice.kt").count("maxByOrNull { v -> v.quality }") == 1 and
      load("NcieVoice.kt").count("tts?.voice = best") == 1)
# ---- v9.29.0 "Respect the engine" ----
check("v9.29.0: the engine's own voice is left alone when it has no en-IN voice",
      load("NcieVoice.kt").count("englishDevice") >= 1 and
      load("NcieVoice.kt").count("inVoices.isNotEmpty()") == 1 and
      load("NcieVoice.kt").count("a non-English phone") >= 0)
# ---- v9.21.0 "Reliable + Easy": health, setup, help ----
check("v9.21.0: the health engine + the three screens ship",
      load("NovaHealth.kt").count("object NovaHealth") == 1 and
      load("NovaHealth.kt").count("fun armAll(") == 1 and
      load("NovaHealth.kt").count("fun checks(") == 1 and
      load("SetupActivity.kt").count("class SetupActivity") == 1 and
      load("HealthActivity.kt").count("class HealthActivity") == 1 and
      load("HelpActivity.kt").count("class HelpActivity") == 1)
check("v9.21.0: alarms are armed at app start and on boot",
      ma.count("NovaHealth.armAll(this@MainActivity)") == 1 and
      load("ReminderStore.kt").count("NovaHealth.armAll(context)") == 1)
check("v9.21.0: the new screens are registered (manifest)",
      open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"), encoding="utf-8").read().count(".HealthActivity") == 1 and
      open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"), encoding="utf-8").read().count(".SetupActivity") == 1)
# ---- v9.20.0 "Search fix": the fetch gate + honest failures ----
check("v9.20.0: the fetch gate accepts a 2-keyword match (OnlineFetch)",
      of_.count("fun hits(") == 1 and of_.count("hits(q, wiki.second) >= 2") == 1 and
      of_.count("Coverage.ratio(q,") == 2)
check("v9.20.0: failures name their cause (OnlineFetch)",
      of_.count("Couldn't fetch anything (") == 1 and of_.count("fun joinReason(") == 1)
check("v9.20.0: Wikipedia + DuckDuckGo each get a fallback (OnlineFetch)",
      of_.count("rest_v1/page/summary") == 1 and of_.count("html.duckduckgo.com") == 1)
# ---- v9.19.0 "Polish": the Phase 2-5 features tied together ----
check("v9.19.0: the leftover state joins the briefing + dream report",
      lo.count("fun dailyDigest(") == 1 and
      load("ncie/android/NcieRoutines.kt").count("NcieLeftovers.dailyDigest(ctx)") == 1 and
      load("ncie/android/NcieDream.kt").count("NcieLeftovers.dailyDigest(ctx)") == 1)
check("v9.19.0: money radar accepts the rupee sign",
      lo.count("\\u20B9") == 1)
check("v9.19.0: time machine searches promises + expiry",
      load("ncie/android/NcieTimeMachine.kt").count('Hit("promise"') == 1 and
      load("ncie/android/NcieTimeMachine.kt").count('Hit("expiry"') == 1)
# ---- v9.18.0 "Redesign": the design system + the screens on it ----
check("v9.18.0: the design system ships (NovaUi kit)",
      load("NovaUi.kt").count("object NovaUi") == 1 and
      load("NovaUi.kt").count("fun card(") == 1 and
      load("NovaUi.kt").count("fun switchRow(") == 1)
check("v9.18.0: the theme carries the new tokens",
      load("NovaTheme.kt").count("RADIUS_CARD") == 1 and
      load("NovaTheme.kt").count("accentSoft") >= 1)
check("v9.18.0: the screens build on the kit",
      load("SettingsActivity.kt").count("NovaUi.") >= 5 and
      load("ExamsActivity.kt").count("NovaUi.") >= 3 and
      load("ModelsActivity.kt").count("NovaUi.") >= 2 and
      load("PlannerActivity.kt").count("NovaUi.") >= 2 and
      load("MemoryActivity.kt").count("NovaUi.") >= 1 and
      load("KnowledgeActivity.kt").count("NovaUi.") >= 1)
# ---- v9.17.1 "Audit fixes" ----
check("v9.17.1: 'i'm fine' is chat unless a guardian is armed",
      lo.count("Settings(ctx).guardianUntil > System.currentTimeMillis()") == 1)
check("v9.17.1: bare 'silence' no longer means quiet hours",
      lo.count("|silence|dnd)(?:") == 0 and lo.count("silence\\\\s+status") == 1)
check("v9.17.1: a failed lookup explains why (proxy probe)",
      load("ncie/android/OnlineFetch.kt").count("fun diagnose(") == 1)
# ---- v9.17.0 "Leftovers": the rest of the backlog in one dispatcher ----
check("v9.17.0: leftover dispatcher ships (NcieLeftovers)",
      lo.count("object NcieLeftovers") == 1 and nc.count("NcieLeftovers.handle(act, text)") == 1)
check("v9.17.0: guardian + quiet hours + recurring ship",
      lo.count("object NcieGuardian") == 1 and lo.count("object NcieQuiet") == 1 and
      lo.count("object NcieRecurring") == 1 and lr2.count("class LeftoverReceiver") == 1)
check("v9.17.0: ledger + money + scam + expiry + packages ship",
      lo.count("object NcieLedger") == 1 and lo.count("object NcieMoney") == 1 and
      lo.count("object NcieScam") == 1 and lo.count("object NcieExpiry") == 1 and
      lo.count("object NciePackages") == 1)
check("v9.17.0: quiet hours gate the auto-responder (NovaListener)",
      load("NovaListener.kt").count("NcieLeftovers.isQuietNow(this)") == 1)
check("v9.17.0: guardian receiver registered (manifest)",
      open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"), encoding="utf-8").read().count(".LeftoverReceiver") == 1)
check("v9.17.0: keystores are gitignored",
      open(os.path.join(ROOT, ".gitignore"), encoding="utf-8").read().count("*.keystore") == 1)
# ---- v9.16.4 "Quiz routing fix": the quiz topic is cleaned, bare "quiz me"
#      routes to the tutor, and a deictic topic falls back to the open doc ----
check("v9.16.4: quiz topic is cleaned + bare 'quiz me' routes (NcieChat)",
      nc.count("cleanQuizTopic") == 2 and nc.count("TUTOR_QUIZ_BARE") == 2)
check("v9.16.4: deictic quiz falls back to the open document (NcieTutor)",
      load("ncie/android/NcieTutor.kt").count("quizSourceFor") == 2)
# ---- v9.16.5 "Direct call + SMS": do it ourselves, no dialer/messaging app ----
_mf5 = open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
check("v9.16.5: call + SMS permissions declared (AndroidManifest)",
      _mf5.count("android.permission.CALL_PHONE") == 1 and
      _mf5.count("android.permission.SEND_SMS") == 1)
check("v9.16.5: direct call + direct SMS wired (MainActivity + NovaSms)",
      ma.count("Intent.ACTION_CALL") == 1 and ma.count("NovaSms.sendDirect") == 2 and
      load("SendReceiver.kt").count("NovaSms.sendDirect") == 1)
# ---- v9.16.6 "Contact lookup": multi-strategy resolution ----
check("v9.16.6: robust contact lookup (NovaSms)",
      load("SendReceiver.kt").count("numberForContact") == 2 and
      load("SendReceiver.kt").count("filterNumber") == 2)
# ---- v9.16.7 "Smaller-model quality + WhatsApp" ----
check("v9.16.7: tiny-model sampling profile (NcieTune)",
      load("ncie/android/NcieTune.kt").count("TINY_DEFAULTS") == 2)
check("v9.16.7: tiny-model reply cap (NovaEngine)",
      load("NovaEngine.kt").count("minOf(predictLength, 320)") == 1)
check("v9.16.7: WhatsApp uses the wa.me deep link (MainActivity)", ma.count("wa.me/") == 1)
# ---- v9.16.8 "Silent reply" ----
check("v9.16.8: silent reply through the notification action (NovaListener)",
      load("NovaListener.kt").count("fun silentReply(") == 1 and
      load("NovaListener.kt").count("addResultsToIntent") == 1)
# ---- v9.16.9 "Auto-responder" ----
check("v9.16.9: the auto-responder ships (NovaListener + Settings)",
      load("NovaListener.kt").count("autoReplied") >= 1 and
      load("Settings.kt").count("autoReplyMsg") >= 1)
# ---- v9.16.10 "Encrypted backup" ----
check("v9.16.10: encrypted backup ships (BackupActivity)",
      load("BackupActivity.kt").count("AES/GCM/NoPadding") == 2 and
      load("BackupActivity.kt").count("PBKDF2WithHmacSHA256") == 1)
# ---- v9.16.11 "Dream mode" ----
check("v9.16.11: Dream mode ships (NcieDream + briefing)",
      load("ncie/android/NcieDream.kt").count("fun run(") == 1 and
      load("ncie/android/NcieRoutines.kt").count("NcieDream.run(") == 1)
# ---- v9.16.12 "Dream mode: nightly" ----
check("v9.16.12: the nightly Dream receiver ships",
      load("DreamReceiver.kt").count("NcieDream.run(") == 1 and
      load("ncie/android/NcieDream.kt").count("fun schedule(") == 1 and
      open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"),
           encoding="utf-8").read().count(".DreamReceiver") == 1)
# ---- v9.16.13 "Time Machine" ----
check("v9.16.13: the Time Machine ships (NcieTimeMachine + find)",
      load("ncie/android/NcieTimeMachine.kt").count("fun search(") == 1 and
      nc.count("NcieTimeMachine.search(") == 1)
# ---- v9.16.14 "Jarvis: away / back" ----
check("v9.16.14: away/back modes ship (NcieModes + router)",
      load("ncie/android/NcieModes.kt").count("fun away(") == 1 and
      nc.count("NcieModes.away(") == 1)
# ---- v9.16.15 "Voice briefing" ----
check("v9.16.15: voice briefing ships (speakNow + briefing)",
      ma.count("fun speakNow(") == 1 and
      nc.count("speakNow(briefText)") == 1)
# ---- v9.16.16 "Doc-aware honesty" ----
check("v9.16.16: deictic doc reference asks for the document",
      nc.count("DOC_DEICTIC_Q.containsMatchIn(text)") == 1 and
      nc.count("won't guess at what's inside") == 1)
# ---- v9.15.0 "Camera": in-app camera screen (preview + shutter) -> OCR -> chat ----
mf = open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
check("v9.15.0: the camera permission is declared (AndroidManifest)",
      mf.count('android.permission.CAMERA') == 1 and
      mf.count('android:name=".CameraActivity"') == 1)
cam = load("CameraActivity.kt")
check("v9.15.0: the camera screen ships (preview + shutter + capture)",
      cam.count("PreviewView") >= 1 and cam.count("takePicture(") == 1 and
      cam.count("bindToLifecycle(") == 1)
check("v9.15.0: the camera row + result wiring are in MainActivity",
      ma.count("drawerRow(\"Scan a page\", R.drawable.ic_camera)") == 1 and
      ma.count("REQ_CAMERA") == 4)
# ---- v9.16.3 "Fixes + Automation" ----
_vamf3 = open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
_bak = open(os.path.join(ROOT, "android/app/src/main/java/org/nova/BackupActivity.kt"), encoding="utf-8").read()
check("v9.16.3: launcher visibility for 'open <app>' (AndroidManifest)",
      _vamf3.count("<queries>") == 1 and _vamf3.count("android.intent.category.LAUNCHER") == 2)
check("v9.16.3: backups include settings (shared_prefs)",
      _bak.count("shared_prefs") >= 3)
check("v9.16.3: offline Wikipedia dataset points at the public repo (WikiCore)",
      wc.count("NOVA-APK/main/wiki/articles-v1.txt") == 1)
check("v9.16.3: update check reads the public release API (MainActivity)",
      ma.count("NOVA-APK/releases/latest") == 1)
_nex3 = open(os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/NcieExam.kt"), encoding="utf-8").read()
check("v9.16.3: chat exam also lands in the Exams store (NcieExam)",
      _nex3.count("Exams.add(act, \"Exam\", ms)") == 1)
# ---- v9.16.2 "Working buttons" ----
check("v9.16.2: the stop button cancels the generation (MainActivity)",
      ma.count("if (generationJob?.isActive == true) generationJob?.cancel()") == 4)
check("v9.16.2: a camera button on the typing row (MainActivity)",
      ma.count("R.drawable.ic_camera") == 2 and ma.count("setOnClickListener { openCamera() }") == 1)
check("v9.16.2: summariser prompts thinned (MainActivity)",
      ma.count("Combine these section notes") == 2)
check("v9.16.2: online fetch tries several Wikipedia hits + looser gate (OnlineFetch)",
      of_.count("srlimit=3") == 1 and of_.count("Coverage.ratio(q,") == 2)
# ---- v9.16.1 "Thin Prompt + Hardening" ----
_vst = open(os.path.join(ROOT, "android/app/src/main/java/org/nova/Settings.kt"), encoding="utf-8").read()
check("v9.16.1: thin base system prompt (Settings)",
      _vst.count("nothing you say leaves the device") == 1 and
      _vst.count("Be concise and direct. If you are not sure, say so instead of guessing") == 1 and
      _vst.count("Do not invent facts") == 1)
check("v9.16.1: PIN uses PBKDF2 + a throttle (MainActivity)",
      ma.count("PBKDF2WithHmacSHA256") == 1 and ma.count("pinLockUntil") == 4 and
      ma.count("MessageDigest.isEqual") == 2)
check("v9.16.1: context is delimited as data (NcieChat)",
      nc.count("CONTEXT (reference data") == 1)
check("v9.16.1: ONNX embedding run is serialized (NcieEmbed)",
      load("ncie/android/NcieEmbed.kt").count("@Synchronized") == 1)
# ---- v9.16.0 "Private Fetch": SOCKS proxy + https-only online lookup ----
nnf = load("NovaNet.kt")
check("v9.16.0: the SOCKS proxy helper ships (NovaNet)",
      nnf.count("Proxy.Type.SOCKS") == 1 and nnf.count("fun getText(") == 1)
check("v9.16.0: the proxy toggle is in settings (SettingsActivity)",
      sa.count("proxyEnabled") == 2)
check("v9.16.0: online fetch goes through the proxy helper (OnlineFetch)",
      of_.count("NovaNet") == 2 and of_.count('startsWith("https")') == 1)
_vamf = open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
# v9.33.42 "Borrowed brain": this guarantee was NARROWED, on purpose, and it
# is worth being explicit about. A model server on the user's own Wi-Fi speaks
# plain http, and Android's network security config cannot scope cleartext to
# private address ranges (it takes hostnames), so the feature cannot exist
# while this is false. What still holds: the app's OWN lookups go through the
# https-only helper in OnlineFetch, and the only cleartext connection in the
# codebase is the one to the address the user typed on their own network.
check("v9.33.58: cleartext is scoped - NOT a global manifest flag",
      # v9.33.58: the redundant app-wide android:usesCleartextTraffic flag was
      # removed. The LAN exception now lives only in network_security_config,
      # and NovaNet refuses an https->http redirect downgrade.
      _vamf.count('android:usesCleartextTraffic="true"') == 0 and
      open(os.path.join(ROOT, "android/app/src/main/res/xml/network_security_config.xml"))
        .read().count('cleartextTrafficPermitted="true"') == 1)
# ---- v9.14.0 "Sharp Memory": learn each context source's value; skip the dead ones ----
nval = load("ncie/android/NcieValue.kt")
check("v9.14.0: the adaptive context value tracker ships (NcieValue)",
      nval.count("fun allow(") == 1 and nval.count("fun note(") == 1 and
      nval.count("fun summary(") == 1 and nval.count("fun reset(") == 1)
check("v9.14.0: the wiki source is gated by its learned value (NcieChat)",
      nc.count('NcieValue.allow(this, "wiki")') == 1)
check("v9.14.0: the wiki source's value is measured at turn end (MainActivity)",
      ma.count("NcieValue.note(this@MainActivity, \"wiki\",") == 1)
# ---- v9.13.8 "Lean turn": one question embedding per turn; no embedder without docs ----
ng = load("ncie/android/NcieGround.kt")
check("v9.13.8: the question embedding is computed once per turn (NcieGround)",
      ng.count("fun queryVector(") == 1 and
      ng.count("queryVector(ctx, question)") == 2 and
      ng.count("NcieEmbed.embed(ctx, question)") == 1)
check("v9.13.8: the semantic pass is skipped when there are no documents",
      ng.count("if (!NcieKnowledge.hasDocs(ctx)) return false") == 1)
# ---- v9.13.7 "LFM 2.5 230M + wider speculation": catalog entry + draft matcher ----
mcat = load("ModelCatalog.kt")
check("v9.13.7: LFM 2.5 230M is in the model catalog",
      mcat.count('"lfm25-230m"') == 1 and mcat.count("LFM2.5-230M-Q4_K_M.gguf") == 1)
# ---- v9.13.5 "Full CPU kernels": every per-chip ggml variant ships in the APK ----
check("v9.14.1: the extra ggml CPU variants are excluded again (build.gradle)",
      bg.count("excludes += ['lib/**/libggml-cpu") == 1 and
      bg.count("libggml-cpu-android_armv8.6_1.so") == 1)
# ---- v9.13.4 "Thinking Tags Hardened": all thinking-tag variants stripped ----
check("v9.13.4: every thinking-tag variant is stripped (MainActivity)",
      ma.count('THINK_TAGS = listOf("think", "thinking")') == 1 and
      ma.count("fun stripThinking(s: String): String") == 1)
# ---- v9.13.1 "Honest Summaries": verification pass, meta strip, facts-only prompt ----
check("v9.13.1: honest summaries - deterministic fact verification (MainActivity)",
      ma.count("fun verifySummaryFacts(") == 1 and
      ma.count("(unverified)") == 1 and
      ma.count("could not be matched to your notes") == 1)
check("v9.13.1: meta stripper + facts-only prompt on both summarizers (MainActivity)",
      ma.count("fun stripSummaryMeta(") == 1 and
      ma.count("verifySummaryFacts(stripSummaryMeta(") == 4 and
      ma.count("Use only facts that appear in the TEXT") == 2)
ne = load("ncie/android/NcieExam.kt")
nr = load("ncie/android/NcieRoutines.kt")
# ---- v9.11.0 "Inference Quality": sampling profiles, base prompt, context budget ----
# (variable names tun/nev/st: nt stays NovaTheme below, ne stays NcieExam)
tun = load("ncie/android/NcieTune.kt")
nev = load("NovaEngine.kt")
# v9.33.42: there are two paths now - the remote machine, and the fallback to
# this phone - and BOTH await the engine before firing. The rule is unchanged.
check("v9.13.9: a prompt is never fired into a busy engine (NovaEngine)",
      nev.count("private suspend fun awaitEngineReady(") == 1 and
      nev.count("awaitEngineReady(engine)") == 2 and
      nev.count("emitAll(engine.sendUserPrompt(") == 2)
check("v9.13.7: draft matcher covers Qwen and LFM families (NovaEngine)",
      nev.count('"qwen" in target') == 1 and nev.count('"lfm" in target') == 1)
# ---- v9.13.3 "No-Thinking for 1B+": models 1B and larger skip hidden thinking ----
check("v9.13.3: 1B+ models are told to skip hidden thinking (NovaEngine)",
      nev.count("Do NOT produce any thinking") == 1 and
      nev.count("val big = params != null && params >= 1.0") == 1 and
      nev.count("MODEL_PARAMS.find(n)") == 1)
st = load("Settings.kt")
# ---- v9.13.6 "Faster Qwen3": speculative decoding on by default (lossless) ----
check("v9.13.6: speculative decoding defaults ON (Settings)",
      st.count("prefs.getBoolean(KEY_SPEC, false)") == 1)
check("v9.11.0: per-model sampling profiles ship (NcieTune)",
      tun.count("model_settings") >= 2 and tun.count("QWEN_DEFAULTS") == 2 and
      tun.count("LFM_DEFAULTS") == 2 and tun.count("OTHER_DEFAULTS") == 2 and
      tun.count("CONTEXT_BUDGET_CHARS") >= 2 and tun.count("CONTEXT_WINDOW_CHARS") == 1)
check("v9.11.0: tuning commands + context trimming wired into the chat turn (NcieChat)",
      nc.count("answerTune(text)") == 1 and nc.count("NcieTune.trimContext(") == 1 and
      nc.count("NcieTune.applyProfile(") == 1 and nc.count("TUNE_SET_Q") == 3)
check("v9.11.0: strong concise base system prompt (Settings default + styled NovaEngine prompt)",
      st.count("Be concise and direct. If you are not sure, say so instead of guessing") == 1 and
      st.count("Do not invent facts") == 1 and
      st.count("prompt.ifBlank { DEFAULT_SYSTEM_PROMPT }") == 1 and
      nev.count("Settings(context).styledPrompt(systemPrompt)") == 2)
check("v9.10.0: the exam planner ships (date file, countdown, plan, today's topic)",
      ne.count("fun setExamDate(") == 1 and ne.count("fun buildPlan(") == 1 and
      ne.count("fun daysLeft(") == 1 and ne.count("fun todayTopic(") == 1 and
      ne.count("exam_date.txt") == 2 and ne.count("revision_plan.txt") == 2 and
      ne.count("tutor_miss.txt") == 3)
check("v9.10.0: exam commands intercepted in the chat + briefing lines",
      nc.count("EXAM_SET") == 3 and nc.count("NcieExam.") == 5 and
      nr.count("days to exam") == 1 and nr.count("NcieExam.") == 2)
check("v9.10.0: hardening - planner soft-fail, store lock, save-on-send",
      nk.count("check(") == 0 and nk.count("synchronized(storeLock)") == 5 and
      ma.count("persist the question IMMEDIATELY") == 1)
check("v8.6.0: the offer fires on any knowledge question, skills stay offline (NcieChat)",
      nc.count("lastSkillMatched == null") == 2 and
      nc.count("OnlineFetch.offer(act, text,") == 1)
check("v8.6.0: local material is the alternative, not the blocker (OnlineFetch)",
      of_.count("haveLocal") == 2 and of_.count("Answer locally") == 1 and
      of_.count("Look online too?") == 1)
check("v8.5.1: own ListView under android.R.id.list, never re-parented (FetchLogActivity)",
      fla.count("R.id.list") == 1 and fla.count("listAdapter = ") == 1 and
      fla.count("listView.apply") == 0)

# ---- v8.4.0: the four stages - online learning, weak topics, skills, gaps ----
check("v8.4.0: online learning toggle (SettingsActivity)",
      sa.count("onlineLearning") == 2)
check("v8.4.0: fetched articles join the wiki store (WikiCore)",
      wc.count("appendArticle") == 1)
check("v8.4.0: keywords-only search terms (NcieKnowledge)",
      nk.count("keyTerms") == 1)
check("v8.4.0: ask-first gap offer + skill match in the chat path (NcieChat)",
      nc.count("OnlineFetch.offer") == 1 and nc.count("NcieSkills.match") == 1)
check("v8.4.0: skill turn bookkeeping (MainActivity + NcieChat + NcieLearn)",
      ma.count("lastSkillMatched") == 2 and nc.count("lastSkillMatched") == 4 and
      nl.count("lastSkillMatched") == 1)
check("v8.4.0: failures noted, gaps logged (NcieLearn)",
      nl.count("noteFailure") == 1 and nl.count("noteGap") == 2)
check("v8.4.0: weak topics surface on the Memory screen (NcieLearn)",
      nl.count("weakTopics") == 1)
check("v8.4.0: the fetcher gates with Coverage and sends keywords only",
      of_.count("Coverage.ratio") == 2 and of_.count("wikipedia.org") == 4)
check("v8.4.0: skills parsed by the kernel (NcieSkills)",
      nsk.count("SkillStore.parse") == 2)
check("v8.4.0: kernel stage files synced (Coverage, SkillStore, GapStore)",
      cov.count("v0.9.4") >= 1 and sk.count("[skill]") >= 1 and gs.count("MAX = 100") == 1)
check("v8.4.0: the Struggle Rule synced (AdaptiveKernel)",
      ak.count("STRUGGLE RULE") == 1)
check("v8.4.0: weak-topic signal synced (Learner + PersistentLearner)",
      lr.count("noteFailure") == 2 and pl.count("noteFailure") == 1 and pl.count("weakTopics") == 1)

# ---- v8.3.1: tiny-model detection parses the label (1.5B was missed) ----
check("v8.3.1: model size parsed, not substring-matched",
      nc.count("ModelProfile.isTiny(mlabel)") == 1 and
      load("ncie/ModelProfile.kt").count("MODEL_PARAMS") == 2)

# ---- v8.3.0: the study planner + doubt journal (drawer row) ----
check("v8.3.0: planner drawer row present (MainActivity)",
      ma.count("PlannerActivity::class.java") == 1)

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
      wc.count("prepared") == 9 and wc.count("WikiStore()") == 1)

# ---- v7.9.0: the memory screen joins the drawer; CI enforces version bumps ----
amf = open(os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
check("v7.9.0: memory screen in the drawer, not the launcher (AndroidManifest.xml)",
      amf.count("NOVA Memory") == 1 and
      amf.count("android.intent.category.LAUNCHER") == 2)
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
      ma.count('setSendButton(R.drawable.ic_mic, textDim, "Speak")') == 1 and
      ma.count("sendBtn.setOnClickListener { startSpeech() }") == 1 and
      ma.index('setSendButton(R.drawable.ic_send, Color.WHITE, "Send")') <
      ma.index('setSendButton(R.drawable.ic_mic, textDim, "Speak")'))

# ---- v8.0.0: UI overhaul - midnight palette + cards everywhere ----
check("v8.0.0: midnight palette in NovaTheme (init + dark apply)",
      nt.count("#151821") == 2 and nt.count("#5B8DEF") == 2)
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
check("v7.7.0: theme chrome matches graphite dark", sty.count("#0B0D12") == 3)
fam = open(os.path.join(ROOT, "android/app/src/main/res/font/inter.xml"), encoding="utf-8").read()
check("v7.7.0: font family ships 4 weights", fam.count("inter_") == 4)
for w in ("inter_regular", "inter_medium", "inter_semibold", "inter_bold"):
    check("v7.7.0: %s.ttf present" % w,
          os.path.exists(os.path.join(ROOT, "android/app/src/main/res/font", w + ".ttf")))
check("v8.0.0: midnight palette installed (was graphite + iris)",
      nt.count("#5B8DEF") == 2 and nt.count("#0B0D12") == 2)
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

# ---- v9.33.76 "Every-script retrieval terms" ----
# v9.33.19 gave every script a tokenizer, but four retrieval call sites kept
# the old hand-rolled rule `Tokens.split(x).filter { it.length > 2 }`. That
# counts CHARACTERS, and a Devanagari content word is short in characters
# because its vowel signs are separate code points, so "ghar" and "man" were
# dropped before anything matched: a Hindi question lost its content words in
# the tool scorer, the search acceptance gate and the document window.
# The rule now has one name (Tokens.contentWords) and these checks keep it.
ntools = load("ncie/android/NcieTools.kt")
nof = load("ncie/android/OnlineFetch.kt")
check("v9.33.76: Tokens defines contentWords (the one content-word rule)",
      "fun contentWords(s: String, minLen: Int = 3): List<String> = words(s, minLen)"
      in load("ncie/Tokens.kt"))
check("v9.33.76: tool-result scoring uses every-script terms",
      "Tokens.contentWords(query)" in ntools)
check("v9.33.76: the search acceptance gate uses every-script terms",
      "Tokens.contentWords(q).distinct()" in nof)
check("v9.33.76: document excerpt ranking uses every-script terms",
      "Tokens.contentWords(ql)" in ma)
check("v9.33.76: document-window overlap uses every-script terms",
      "Tokens.contentWords(text)" in nc)
# and the ASCII-length rule cannot come back on the retrieval path
_BARE_ASCII_TERMS = re.compile(
    r"Tokens\.split\([^)]*\)\s*\.filter\s*\{\s*it\.length\s*>\s*2")
check("v9.33.76: no ASCII-length query filter left on the retrieval path",
      not any(_BARE_ASCII_TERMS.search(t) for t in
              [ntools, nof, ma, nc, ka, lo, load("ncie/android/NcieKnowledge.kt")]))

# ---- v9.33.77 "Answer-first window" ----
# docSearchIn builds the "from my notes" excerpt. It used to take the top 12
# paragraphs by score, restore document order and STOP at the first one that
# did not fit - so early filler could consume the budget and drop the
# paragraph holding the answer, and when the first selected paragraph alone
# was larger than the budget it returned an EMPTY string (the notes silently
# vanished and the model answered from imagination). These checks keep the
# relevance-first form and its unit tests in place.
check("v9.33.77: the document window ranks by relevance first",
      "val ranked = scored.sortedByDescending { it.first }.map { it.second }" in ma)
check("v9.33.77: a paragraph that does not fit is skipped, not fatal",
      "if (chosen.isNotEmpty()) continue   // a smaller paragraph may fit" in ma)
check("v9.33.77: the window is capped at its character budget",
      "if (window.length > maxChars) window.take(maxChars) else window" in ma)
check("v9.33.77: the old document-order-then-stop selection is gone",
      "take(12).map { it.second }.sorted()" not in ma)
check("v9.33.77: the empty-window regression is pinned by a unit test",
      "fun aWindowIsNeverSilentlyEmpty()" in
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ChatTextTest.kt"),
           encoding="utf-8").read())

for f, text in [("MainActivity.kt", ma), ("ncie/android/NcieChat.kt", nc), ("ncie/android/NcieTune.kt", tun), ("ncie/android/NcieLearn.kt", nl), ("ncie/android/NcieKnowledge.kt", nk), ("ncie/android/NcieExam.kt", ne), ("ncie/learn/Learner.kt", lr), ("ncie/learn/PersistentLearner.kt", pl), ("ncie/learn/LearnedFact.kt", lf), ("KnowledgeActivity.kt", ka), ("ncie/android/NcieLeftovers.kt", lo), ("LeftoverReceiver.kt", lr2), ("NovaUi.kt", load("NovaUi.kt")), ("NovaHealth.kt", load("NovaHealth.kt")), ("SetupActivity.kt", load("SetupActivity.kt")), ("HealthActivity.kt", load("HealthActivity.kt")), ("HelpActivity.kt", load("HelpActivity.kt")), ("ncie/android/NcieStyle.kt", load("ncie/android/NcieStyle.kt")), ("NovaListener.kt", load("NovaListener.kt")), ("ncie/android/NcieDeadlines.kt", load("ncie/android/NcieDeadlines.kt")), ("ncie/android/NcieWeekly.kt", load("ncie/android/NcieWeekly.kt")), ("ncie/android/NcieWatchdog.kt", load("ncie/android/NcieWatchdog.kt")), ("ncie/android/NcieFocus.kt", load("ncie/android/NcieFocus.kt")), ("ncie/android/NovaJarvis.kt", load("ncie/android/NovaJarvis.kt")), ("NovaLog.kt", load("NovaLog.kt")), ("ncie/android/NcieTools.kt", ntools), ("ncie/android/OnlineFetch.kt", nof), ("ncie/Tokens.kt", load("ncie/Tokens.kt"))]:
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
