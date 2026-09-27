#!/usr/bin/env python3
"""NCIE Stage 5 (#1): the collapse. send() -> thin dispatch; the 391-line
routing body moved verbatim behind the kernel boundary (ncie/android/
NcieChat.kt, pushed complete in the setup commit). This script:
  1. extracts send() and PROVES the pushed NcieChat.kt body matches it
     (after the only edit: 3x this@MainActivity -> the captured act)
  2. replaces send() with the 7-line dispatch
  3. internalizes the 43 members the routing reads (private -> internal)
  4. updates tmp/verify_patches.py: markers that lived inside send() are
     now checked against NcieChat.kt (explicit guard update for the move)
Fails loudly on any drift; the workflow runs the updated guard before
committing.
"""
import sys

PATH = "android/app/src/main/java/org/nova/MainActivity.kt"
NCIE = "android/app/src/main/java/org/nova/ncie/android/NcieChat.kt"
GUARD = "tmp/verify_patches.py"

src = open(PATH, encoding="utf-8").read()
ncie = open(NCIE, encoding="utf-8").read()

sig = "    private fun send() {"
assert src.count(sig) == 1, "send() signature not found exactly once"
i = src.index(sig)
j = src.index("\n    }", i) + len("\n    }")
old_send = src[i:j]

# 1. the pushed NcieChat.kt must contain this exact body (act-captured)
moved = old_send.replace("this@MainActivity", "act")
assert old_send.count("this@MainActivity") == 3, "expected 3 this@MainActivity labels"
body = moved[len(sig):]
assert body in ncie, "NcieChat.kt does not contain the current send() body - drift!"
assert ncie.count("fun MainActivity.ncieSend()") == 1

NEW_SEND = '    private fun send() {\n        // NCIE Stage 5 (#1): the collapsed dispatch. The full routing body\n        // - pre-model gates, notes branches, study cards, prompt assembly\n        // with memory/exam/notes/wiki injection - lives behind the kernel\n        // boundary now: ncie/android/NcieChat.kt (MainActivity.ncieSend).\n        ncieSend()\n    }'
assert src.count(old_send) == 1
new_src = src.replace(old_send, NEW_SEND)

DECLS = [('CHIP_PROMPTS', 'private val CHIP_PROMPTS = setOf('), ('FOLLOW_UP_Q', 'private val FOLLOW_UP_Q = Regex('), ('NL', '    private val NL = 10.toChar().toString()'), ('SMALLTALK_REGEX', 'private val SMALLTALK_REGEX = Regex('), ('adapter', '    private val adapter = MessageAdapter()'), ('autoContinueCount', '    private var autoContinueCount = 0'), ('compactSummary', '    private var compactSummary: String? = null'), ('compacting', '    private var compacting = false'), ('currentChat', '    private lateinit var currentChat: Chat'), ('docContext', '    private var docContext: String? = null'), ('docInjected', '    private var docInjected = false'), ('docInjectedText', '    private var docInjectedText: String = ""'), ('docName', '    private var docName: String? = null'), ('docSearch', '    private fun docSearch(query: String, maxChars: Int = 4000): String ='), ('docStop', 'private val docStop = setOf("what", "who", "when", "where", "why", "how", "the", "and",'), ('ensureModelReady', '    private fun ensureModelReady(): Boolean {'), ('generationJob', '    private var generationJob: Job? = null'), ('input', '    private lateinit var input: EditText'), ('lastInjectedExamKey', '    private var lastInjectedExamKey: String? = null'), ('lastInjectedMemory', '    private var lastInjectedMemory: String? = null'), ('lastNotesChatId', '    private var lastNotesChatId: String = ""'), ('lastNotesDoc', '    private var lastNotesDoc: String? = null'), ('lastNotesHit', '    private var lastNotesHit: List<Knowledge.Chunk> = emptyList()'), ('maybeAutoRemember', '    private fun maybeAutoRemember(text: String) {'), ('maybeSetReminder', '    private fun maybeSetReminder(text: String) {'), ('needsContextCarry', '    private var needsContextCarry = false'), ('pendingCards', '    private var pendingCards = false'), ('pendingCitation', '    private var pendingCitation: String? = null'), ('pendingQaKey', '    private var pendingQaKey: String? = null'), ('readIdx', '    private var readIdx = 0'), ('readSents', '    private var readSents: List<String> = emptyList()'), ('replyRetried', '    private var replyRetried = false'), ('runTool', '    private fun runTool(prompt: String) {'), ('scope', '    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)'), ('scrollToEnd', '    private fun scrollToEnd(force: Boolean = true) {'), ('settings', '    private lateinit var settings: Settings'), ('solveArithmetic', '    private fun solveArithmetic(text: String): Boolean {'), ('startGeneration', '    private fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true, plain: Boolean = false) {'), ('summarizeDoc', '    private fun summarizeDoc() {'), ('summarizeNotes', '    private fun summarizeNotes(doc: String, userText: String, fullDoc: Boolean) {'), ('toast', '    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()'), ('tryPhoneCommand', '    private fun tryPhoneCommand(text: String): Boolean {'), ('tts', '    private var tts: TextToSpeech? = null')]
for name, line in DECLS:
    assert new_src.count(line) == 1, f"anchor not unique: {name}"
for name, line in DECLS:
    new_src = new_src.replace(line, line.replace("private ", "internal ", 1))

imp = "import org.nova.ncie.android.NovaEngineAdapter"
assert new_src.count(imp) == 1
new_src = new_src.replace(imp, imp + "\nimport org.nova.ncie.android.ncieSend", 1)

assert new_src.count("ncieSend()") == 1
assert new_src.count("NovaEngineAdapter.stream(") == 1
assert "internal val docStop" in new_src and "internal val CHIP_PROMPTS" in new_src
assert new_src.count("{") - new_src.count("}") == src.count("{") - src.count("}")

open(PATH, "w", encoding="utf-8").write(new_src)
g = open(GUARD, encoding="utf-8").read()

REPL = [('FILES = ["MainActivity.kt", "Backup.kt", "NotifBrain.kt", "KnowledgeActivity.kt"]', 'FILES = ["MainActivity.kt", "Backup.kt", "NotifBrain.kt", "KnowledgeActivity.kt"]\n# Stage 5 (#1): send() collapsed - its body moved verbatim to\n# ncie/android/NcieChat.kt (MainActivity.ncieSend). The markers that\n# lived inside send() are checked against NcieChat.kt now; everything\n# else still points at MainActivity.'), ('ma = load("MainActivity.kt")', 'ma = load("MainActivity.kt")\nnc = load("ncie/android/NcieChat.kt")'), ('("v7.3 greeting fast-path (regex + use)", "SMALLTALK_REGEX", 2)', '("v7.3 greeting fast-path (regex)", "SMALLTALK_REGEX", 1)'), ('("v7.4 chip prompt set", "CHIP_PROMPTS", 2)', '("v7.4 chip prompt set", "CHIP_PROMPTS", 1)'), ('("v7.4 chip routing check", "val isChip = CHIP_PROMPTS.contains(text)", 1)', '# moved to NcieChat.kt below: ("v7.4 chip routing check", "val isChip = CHIP_PROMPTS.contains(text)", 1)'), ('("v7.5 lean wiki cap", "val cap = if (tiny) 900 else 1200", 1)', '# moved to NcieChat.kt below: ("v7.5 lean wiki cap", "val cap = if (tiny) 900 else 1200", 1)'), ('("v7.5.1 LFM tiny detection", \'"1.2b" in mlabel\', 1)', '# moved to NcieChat.kt below: ("v7.5.1 LFM tiny detection", \'"1.2b" in mlabel\', 1)'), ('("v7.5.1 tiny notes caps", "if (tiny) 1200 else 2400", 4)', '# moved to NcieChat.kt below: ("v7.5.1 tiny notes caps", "if (tiny) 1200 else 2400", 4)'), ('("v7.6 greeting context reset", "greeting sent into a dirty/stale context", 1)', '# moved to NcieChat.kt below: ("v7.6 greeting context reset", "greeting sent into a dirty/stale context", 1)'), ('("v7.6 notes relevance gate", "relevance gate - one shared word", 1)', '# moved to NcieChat.kt below: ("v7.6 notes relevance gate", "relevance gate - one shared word", 1)'), ('("v7.6 input cleared only when consumed", \'if (solveArithmetic(text)) { input.setText("")\', 1)', '# moved to NcieChat.kt below: ("v7.6 input cleared only when consumed", \'if (solveArithmetic(text)) { input.setText("")\', 1)'), ('("v7.5.1 instant resets", "NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt)", 7)', '("v7.5.1 instant resets", "NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt)", 6)'), ('i_tools = ma.find(\'if (solveArithmetic(text)) { input.setText("")\')\ni_model = ma.find("if (!ensureModelReady()) return")', 'i_tools = nc.find(\'if (solveArithmetic(text)) { input.setText("")\')\ni_model = nc.find("if (!ensureModelReady()) return")'), ('for f, text in [("MainActivity.kt", ma), ("Backup.kt", bk), ("NotifBrain.kt", nb), ("KnowledgeActivity.kt", ka)]:', 'for f, text in [("MainActivity.kt", ma), ("ncie/android/NcieChat.kt", nc), ("Backup.kt", bk), ("NotifBrain.kt", nb), ("KnowledgeActivity.kt", ka)]:')]

for old, new in REPL:
    assert g.count(old) == 1, f"guard anchor not unique: {old[:60]!r}"
    g = g.replace(old, new)

OLD_BLOCK = 'for name, marker, n in MA_MARKERS:\n    check("%s (x%d)" % (name, n), ma.count(marker) == n)\n'
NEW_BLOCK = 'for name, marker, n in MA_MARKERS:\n    check("%s (x%d)" % (name, n), ma.count(marker) == n)\n\n# markers that moved with send()\'s body to ncie/android/NcieChat.kt\nNC_MARKERS = [\n    ("v7.3 greeting fast-path (use)", "SMALLTALK_REGEX", 2),\n    ("v7.4 chip prompt set (use)", "CHIP_PROMPTS", 2),\n    ("v7.4 chip routing check", "val isChip = CHIP_PROMPTS.contains(text)", 1),\n    ("v7.5 lean wiki cap", "val cap = if (tiny) 900 else 1200", 1),\n    ("v7.5.1 LFM tiny detection", \'"1.2b" in mlabel\', 1),\n    ("v7.5.1 tiny notes caps", "if (tiny) 1200 else 2400", 4),\n    ("v7.6 greeting context reset", "greeting sent into a dirty/stale context", 1),\n    ("v7.6 notes relevance gate", "relevance gate - one shared word", 1),\n    ("v7.6 input cleared only when consumed", \'if (solveArithmetic(text)) { input.setText("")\', 1),\n    ("Stage 5 collapse: the moved body is one function", "fun MainActivity.ncieSend()", 1),\n    ("Stage 5 collapse: body reached the act capture", "val act = this", 1),\n]\nfor name, marker, n in NC_MARKERS:\n    check("%s (x%d)" % (name, n), nc.count(marker) == n)\ncheck("Stage 5 collapse: send() is the thin dispatch", ma.count("ncieSend()") == 1)\ncheck("v7.6 streaming turn through NovaEngineAdapter", ma.count("NovaEngineAdapter.stream(") == 1)\n'
assert g.count(OLD_BLOCK) == 1, "MA_MARKERS loop not found"
g = g.replace(OLD_BLOCK, NEW_BLOCK)
new_guard = g
open(GUARD, "w", encoding="utf-8").write(new_guard)
print(f"OK: send() {old_send.count(chr(10))+1} -> {NEW_SEND.count(chr(10))+1} lines; "
      f"MainActivity {src.count(chr(10))+1} -> {new_src.count(chr(10))+1} lines; "
      f"guard updated for the moved markers")
