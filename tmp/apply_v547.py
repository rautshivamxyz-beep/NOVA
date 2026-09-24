#!/usr/bin/env python3
# v5.4.7: "quiz me on X" flashcards from notes + "using your notes" badge
# + knowledge included in backup/restore. Applied by CI (one-shot).
import hashlib, sys

def load(p):
    return open(p, encoding="utf-8").read()

def save(p, s):
    open(p, "w", encoding="utf-8").write(s)
    print("md5 %s: %s" % (p, hashlib.md5(open(p, "rb").read()).hexdigest()))

def rep(src, old, new, fname):
    n = src.count(old)
    if n != 1:
        print("ANCHOR COUNT %d (expected 1) in %s: %r" % (n, fname, old[:70]))
        sys.exit(1)
    return src.replace(old, new)

# ============ MainActivity.kt =================================================
P = "android/app/src/main/java/org/nova/MainActivity.kt"
s = load(P)

# 1) "quiz me on <topic>" -> flashcards straight from the notes
s = rep(s, r'''        maybeAutoRemember(text)''',
r'''        // v5.4.7: "quiz me on power sharing" - study flashcards straight
        // from the notes, reusing the study-card machinery and its Q:/A:
        // parser, so a quiz is graded material you already verified
        if (docContext == null && settings.knowledgeEnabled && Knowledge.hasDocs(this)) {
            val quizMe = Regex("(?i)\\b(?:quiz|test) me on\\b").find(text)
            if (quizMe != null) {
                val topic = text.substringAfter(quizMe.value).trim()
                val parts = Knowledge.bestChunks(this, if (topic.length > 2) topic else text, 10)
                if (parts.isNotEmpty()) {
                    val um = Msg(Role.USER, text)
                    currentChat.messages.add(um); adapter.add(um)
                    pendingCards = true
                    val mat = parts.joinToString("\n")
                    startGeneration("(Create 8 study flashcards from this material. " +
                        "Format each card EXACTLY as:\nQ: <question>\nA: <answer>\n" +
                        "No numbering, no text before or after.\n-----\n$mat\n-----)", null)
                    scrollToEnd()
                    return
                }
            }
        }
        maybeAutoRemember(text)''', P)

# 2) "using your notes" badge while generating
s = rep(s, r'''        autoContinueCount = 0
        replyRetried = false
        startGeneration(prompt, text)''',
r'''        // v5.4.7: show when the answer is grounded in the user's notes
        if (knowledgePart.isNotEmpty()) toast("Using your notes")
        autoContinueCount = 0
        replyRetried = false
        startGeneration(prompt, text)''', P)

save(P, s)

# ============ Backup.kt: knowledge rides along in backups ====================
P = "android/app/src/main/java/org/nova/Backup.kt"
s = load(P)
s = rep(s, r'''        JSONObject()
            .put("nova_backup", 1)
            .put("memory", s.memory)''',
r'''        val kFile = File(ctx.filesDir, "knowledge.json")
        val kJson = if (kFile.exists()) try { kFile.readText() } catch (e: Exception) { "" } else ""
        JSONObject()
            .put("nova_backup", 1)
            .put("memory", s.memory)''', P)
s = rep(s, r'''            .put("predict_length", s.predictLength)''',
r'''            .put("predict_length", s.predictLength)
            .put("knowledge", kJson)''', P)
s = rep(s, r'''        s.knowledgeEnabled = o.optBoolean("knowledge", true)''',
r'''        s.knowledgeEnabled = o.optBoolean("knowledge", true)
        // v5.4.7: restore the whole knowledge base, not just the toggle
        val kJson = o.optString("knowledge")
        if (kJson.isNotEmpty()) Knowledge.restoreAll(ctx, kJson)''', P)
save(P, s)

# ============ Knowledge.kt: restore the base from backup text ================
P = "android/app/src/main/java/org/nova/Knowledge.kt"
s = load(P)
s = rep(s, r'''    fun removeDoc(ctx: Context, name: String) {''',
r'''    /** v5.4.7: backup restore - replace the whole knowledge base. */
    fun restoreAll(ctx: Context, json: String) {
        try {
            val arr = JSONArray(json)
            val chunks = ArrayList<Chunk>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.getString("t")
                chunks.add(Chunk(o.getString("d"), t, t.lowercase(), normOf(t)))
            }
            save(ctx, chunks)
        } catch (e: Exception) { }
    }

    fun removeDoc(ctx: Context, name: String) {''', P)
save(P, s)
print("OK - v5.4.7 patch applied to all 3 files")
