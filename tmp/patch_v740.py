#!/usr/bin/env python3
"""NOVA v7.4.0 app patch: reliability - data loss and corruption fixes.

From a full user-commissioned code review, verified in the code by hand:

1. Phone commands + calculator were dead without a model loaded -
   ensureModelReady() ran before them, and the input field itself was
   disabled whenever no model was present. Both fixed: tools run first,
   the input stays usable ("torch on" / "2+2" work on a fresh install).
2. Switching chats during a reply corrupted the new chat: the cancelled
   coroutine's cleanup still ran against the (new) currentChat, appending
   leftover tokens into the newly opened chat and saving them there. The
   generation now remembers which chat it belongs to and never touches a
   chat it does not own; the partial turn is saved into its own chat.
3. Follow-up chips ("Explain simply" etc.) hit the QA cache with a
   constant key, so after first use every chat replayed the first cached
   answer forever, and "3-point summary" rerouted into the notes
   summarizer. Chip prompts are now recognized and excluded from both.

Backslash-safe: every backslash in the Kotlin output is built via the
@@ marker, so this file contains no backslash literals.

Idempotent - safe to run on every CI build.
Usage: patch_v740.py <NOVA repo root>
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

# ---- 1) no-model tools first: calculator + phone commands ----
A_OLD = '''        if (!ensureModelReady()) return
        if (compacting) {
            toast("Compressing older messages — one moment")
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        // phone commands: call / text / alarm / open app - no model needed
        if (solveArithmetic(text)) return
        if (tryPhoneCommand(text)) return
'''
A_NEW = '''        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        val isChip = CHIP_PROMPTS.contains(text)
        // v7.4: no-model tools FIRST - calculator and phone commands work
        // even before any model is downloaded
        if (solveArithmetic(text)) return
        if (tryPhoneCommand(text)) return
        if (!ensureModelReady()) return
        if (compacting) {
            toast("Compressing older messages — one moment")
            return
        }
'''

# ---- 2a) remember which chat this generation belongs to ----
B_OLD = '''    private fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true, plain: Boolean = false) {
        if (userText != null) {
'''
B_NEW = '''    private fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true, plain: Boolean = false) {
        // v7.4: if the user switches chats mid-reply, this generation must
        // never touch the newly opened chat - remember whose reply this is
        val genChat = currentChat
        if (userText != null) {
'''

# ---- 2b) flush(): never append into a different chat's UI ----
C_OLD = '''            fun flush() {
                if (pending.isNotEmpty()) {
                    adapter.appendToLast(pending.toString())
                    pending.setLength(0)
                }
            }
'''
C_NEW = '''            fun flush() {
                if (pending.isNotEmpty()) {
                    if (currentChat === genChat) adapter.appendToLast(pending.toString())
                    pending.setLength(0)
                }
            }
'''

# ---- 2c) cancelled mid-generation: only mark OUR chat's bubble ----
D_OLD = '''            } catch (e: CancellationException) {
                flush()
                adapter.appendToLast(" ⏹")
'''
D_NEW = '''            } catch (e: CancellationException) {
                flush()
                if (currentChat === genChat) adapter.appendToLast(" ⏹")
'''

# ---- 2d) cleanup: bail out safely when the chat switched ----
E_OLD = '''            } finally {
                flush()
                withContext(Dispatchers.Main) {
                    generating = false
'''
E_NEW = '''            } finally {
                flush()
                withContext(Dispatchers.Main) {
                    if (currentChat !== genChat) {
                        // v7.4: a different chat is open now - do not touch
                        // its UI or state; save the partial turn into ITS
                        // chat and stop here
                        generating = false
                        updateSendLook()
                        setStatus()
                        try {
                            withContext(Dispatchers.IO) { ChatStore.save(this@MainActivity, genChat) }
                        } catch (e: Exception) { }
                        return@withContext
                    }
                    generating = false
'''

# ---- 2e) persist the right chat ----
F_OLD = '''                        withContext(Dispatchers.IO) { ChatStore.save(this@MainActivity, currentChat) }
'''
F_NEW = '''                        withContext(Dispatchers.IO) { ChatStore.save(this@MainActivity, genChat) } }
'''

# ---- 3) chips: skip QA cache + notes summarizer ----
G_OLD = '''            if (studyQ && docPart.isEmpty()) {
'''
G_NEW = '''            if (studyQ && !isChip && docPart.isEmpty()) {
'''
H_OLD = '''            if (asksSummary) {
                summarizeDoc()
                return
            }
'''
H_NEW = '''            if (asksSummary && !isChip) {
                summarizeDoc()
                return
            }
'''

# ---- chip prompt set, next to the other top-level regexes ----
I_OLD = k('''private val SMALLTALK_REGEX = Regex(
    "(?i)^[@@@@s']*(hi+|hey+|hello+|yo|sup|namaste|hola|good (morning|afternoon|evening|night)" +
        "|how are (you|u)|how r (you|u)|what'?s up|how'?s it going)[@@@@s.!~?]*$"
)
''')
I_NEW = k('''private val SMALLTALK_REGEX = Regex(
    "(?i)^[@@@@s']*(hi+|hey+|hello+|yo|sup|namaste|hola|good (morning|afternoon|evening|night)" +
        "|how are (you|u)|how r (you|u)|what'?s up|how'?s it going)[@@@@s.!~?]*$"
)

// v7.4: the four follow-up chip prompts - recognized so they never hit
// the QA cache (stale-answer replay) or the notes summarizer
private val CHIP_PROMPTS = setOf(
    "Explain that more simply, like I am 12 years old.",
    "Give me one clear real-life example of that.",
    "Quiz me on this topic with 3 questions, one at a time.",
    "Summarize that in exactly 3 short bullet points."
)
''')

# ---- 4) input usable without a model ----
J_OLD = '''        val ready = NovaEngine.isModelLoaded && !NovaEngine.isLoading
        input.isEnabled = ready
        input.hint = if (ready) "Message NOVA…" else "Tap ≡ to load a model"
'''
J_NEW = '''        val ready = NovaEngine.isModelLoaded && !NovaEngine.isLoading
        // v7.4: keep the input usable without a model - the calculator and
        // phone commands run before any model is needed
        input.isEnabled = !NovaEngine.isLoading
        input.hint = if (ready) "Message NOVA…"
            else if (NovaEngine.isLoading) "Loading model..."
            else "No model - tap the menu (calculator and phone commands work anyway)"
'''

src = open(MA, encoding="utf-8").read()
if "v7.4" in src:
    print("MainActivity.kt: v7.4.0 patch already applied")
else:
    src = rep(src, A_OLD, A_NEW, "no-model tools first")
    src = rep(src, B_OLD, B_NEW, "genChat capture")
    src = rep(src, C_OLD, C_NEW, "flush guard")
    src = rep(src, D_OLD, D_NEW, "cancel guard")
    src = rep(src, E_OLD, E_NEW, "finally guard")
    src = rep(src, F_OLD, F_NEW, "save genChat")
    src = rep(src, G_OLD, G_NEW, "chip QA cache skip")
    src = rep(src, H_OLD, H_NEW, "chip summarize skip")
    src = rep(src, I_OLD, I_NEW, "chip prompt set")
    src = rep(src, J_OLD, J_NEW, "input enable")
    open(MA, "w", encoding="utf-8").write(src)
    print("MainActivity.kt: v7.4.0 reliability pack applied")

# ---------- Backup.kt: the "knowledge" key collision (data loss) ----------
BK = os.path.join(ROOT, "android/app/src/main/java/org/nova/Backup.kt")
s = open(BK, encoding="utf-8").read()
if 'put("knowledge_enabled"' not in s:
    s = rep(s, '            .put("knowledge", s.knowledgeEnabled)',
            '            .put("knowledge_enabled", s.knowledgeEnabled)', "backup put")
    s = rep(s, '        s.knowledgeEnabled = o.optBoolean("knowledge", true)',
            '        // v7.4: "knowledge" used to hold the boolean toggle (old backups),\n'
            '        // which silently overwrote the documents - fallback only\n'
            '        s.knowledgeEnabled = o.optBoolean("knowledge_enabled", o.optBoolean("knowledge", true))',
            "backup read")
    s = rep(s, '        if (kJson.isNotEmpty()) Knowledge.restoreAll(ctx, kJson)',
            '        if (kJson.isNotEmpty() && kJson.trimStart().startsWith("{")) Knowledge.restoreAll(ctx, kJson)',
            "backup restore")
    open(BK, "w", encoding="utf-8").write(s)
    print("Backup.kt: knowledge key collision fixed (backups now really save notes)")
else:
    print("Backup.kt: already fixed")

# ---------- NotifBrain.kt: file I/O off the main thread ----------
NB = os.path.join(ROOT, "android/app/src/main/java/org/nova/NotifBrain.kt")
s = open(NB, encoding="utf-8").read()
if "v7.4: this callback" not in s:
    s = rep(s,
            "    override fun onNotificationPosted(sbn: StatusBarNotification) {\n"
            "        try {\n",
            "    override fun onNotificationPosted(sbn: StatusBarNotification) {\n"
            "        // v7.4: this callback arrives on the MAIN thread - file I/O there\n"
            "        // janks the whole phone while chatting; run it on a worker thread\n"
            "        Thread {\n"
            "        try {\n",
            "notif thread open")
    s = rep(s,
            "        } catch (e: Exception) { }\n"
            "    }\n"
            "\n"
            "    companion object {\n",
            "        } catch (e: Exception) { }\n"
            "        }.start()\n"
            "    }\n"
            "\n"
            "    companion object {\n",
            "notif thread close")
    open(NB, "w", encoding="utf-8").write(s)
    print("NotifBrain.kt: notification I/O moved to a worker thread")
else:
    print("NotifBrain.kt: already fixed")

# ---------- KnowledgeActivity.kt: bounded import read (OOM kill) ----------
KA = os.path.join(ROOT, "android/app/src/main/java/org/nova/KnowledgeActivity.kt")
s = open(KA, encoding="utf-8").read()
if "v7.4: bound the read" not in s:
    s = rep(s,
            '        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""',
            "        // v7.4: bound the read - an unbounded readBytes() on a huge file\n"
            "        // OOM-kills the app (and the loaded model with it)\n"
            "        val bytes = contentResolver.openInputStream(uri)?.use { s ->\n"
            "            val cap = 30 shl 20\n"
            "            val out = java.io.ByteArrayOutputStream()\n"
            "            val buf = ByteArray(1 shl 16)\n"
            "            var total = 0\n"
            "            while (true) {\n"
            "                val n = s.read(buf)\n"
            "                if (n < 0) break\n"
            "                total += n\n"
            '                if (total > cap) throw IllegalStateException("File too large (over 30 MB)")\n'
            "                out.write(buf, 0, n)\n"
            "            }\n"
            "            out.toByteArray()\n"
            '        } ?: return ""',
            "bounded read")
    open(KA, "w", encoding="utf-8").write(s)
    print("KnowledgeActivity.kt: bounded import read")
else:
    print("KnowledgeActivity.kt: already fixed")
