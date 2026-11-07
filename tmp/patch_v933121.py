#!/usr/bin/env python3
"""v9.33.121 "The rest of the sweep: the notes and the deck".

v9.33.119 gave every store that rewrites what it read the difference between
"nothing there" and "could not read it", and swept the four small ones. It
stopped there, and the two stores it left behind are the two the user cannot
re-create:

  Knowledge.addDoc    load every chunk, drop this doc's old chunks, add the
                      new ones, save the whole list
  Knowledge.removeDoc load every chunk, drop this doc's, save the whole list
  Study.parseAndAdd   load the deck, add the parsed cards, save the deck

Give either an unreadable file and the load hands it an empty list: adding ONE
note saved that one note over the user's whole notes library, and deleting one
note deleted all of them. Both files are the only copy the app has - there is
no cloud, no account, no export unless the user made one.

Same rule, same home (NcieFiles.readOrNull), the two loaders that were still
flattening a failed read into an empty list, and the mutators answer their
callers: addDoc and removeDoc return Boolean, parseAndAdd returns -1 for "the
deck could not be read" so the chat says that instead of "No cards found", and
the notes boundary only refreshes the caches and the index for a write that
actually happened.

Self-verifying: every anchor must match exactly once, and the finished tree is
checked for the new markers before the script exits 0. Run from the repo root
(or pass it): python3 tmp/patch_v933121.py .
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = os.path.join(ROOT, "android/app/src/main/java/org/nova/")

fails = []


def check(name, cond):
    print(("PASS  " if cond else "FAIL  ") + name)
    if not cond:
        fails.append(name)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def patch(path, pairs):
    text = read(path)
    for old, new in pairs:
        n = text.count(old)
        if n != 1:
            print("FATAL: anchor matched %d times in %s:\n%s" % (n, path, old))
            sys.exit(1)
        text = text.replace(old, new)
    write(path, text)
    return text


KNOWLEDGE = BASE + "Knowledge.kt"
STUDY = BASE + "Study.kt"
NCIE_KNOWLEDGE = BASE + "ncie/android/NcieKnowledge.kt"
MAIN = BASE + "MainActivity.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")

# ------------------------------------------------------------ the notes
OLD_LOAD = '''    private fun load(ctx: Context): ArrayList<Chunk> {
        cache?.let { return it }
        val list = ArrayList<Chunk>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    // v9.33.101: one bad row used to throw out of the loop and
                    // the catch then left the list EMPTY - every document the
                    // user added disappeared from "from my notes" at once.
                    val o = arr.optJSONObject(i) ?: continue
                    val t = o.optString("t")
                    if (t.isBlank()) continue
                    list.add(Chunk(o.optString("d"), t, t.lowercase(), normOf(t)))
                }
            }
        } catch (e: Exception) { NovaLog.quiet("Knowledge", e) }
        cache = list
        return list
    }
'''

NEW_LOAD = '''    private fun load(ctx: Context): ArrayList<Chunk> = loadOrNull(ctx) ?: ArrayList()

    /**
     * v9.33.121 "The rest of the sweep": the chunks on disk, or null when the
     * file exists and could not be read.
     *
     * v9.33.119 gave the stores that rewrite what they read the difference
     * between "nothing there" and "could not read it", and swept the four
     * small ones. These two are the ones the user cannot re-create: [addDoc]
     * and [removeDoc] are read-modify-write over the whole notes library, so
     * a note added while knowledge.json could not be parsed saved ONE note
     * over all of them, and a note deleted took the rest with it. The notes
     * are the only copy the app has - there is no cloud and no account.
     *
     * A failed read is not cached either: the next call tries again instead
     * of answering from an empty list for the rest of the session.
     */
    private fun loadOrNull(ctx: Context): ArrayList<Chunk>? {
        cache?.let { return it }
        val f = file(ctx)
        if (!f.exists()) {
            val empty = ArrayList<Chunk>()
            cache = empty
            return empty
        }
        val text = NcieFiles.readOrNull(f) ?: return null
        return try {
            val arr = JSONArray(text)
            val list = ArrayList<Chunk>()
            for (i in 0 until arr.length()) {
                // v9.33.101: one bad row used to throw out of the loop and
                // the catch then left the list EMPTY - every document the
                // user added disappeared from "from my notes" at once.
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t")
                if (t.isBlank()) continue
                list.add(Chunk(o.optString("d"), t, t.lowercase(), normOf(t)))
            }
            cache = list
            list
        } catch (e: Exception) {
            NovaLog.quiet("Knowledge", e)
            null
        }
    }
'''

OLD_ADD = '''    @Synchronized
    fun addDoc(ctx: Context, name: String, text: String) {
        val chunks = ArrayList(load(ctx).filter { it.doc != name })
        for (piece in chunkText(text)) chunks.add(Chunk(name, piece, piece.lowercase(), normOf(piece)))
        save(ctx, chunks)
        clearQaCache(ctx)
    }
'''

NEW_ADD = '''    @Synchronized
    fun addDoc(ctx: Context, name: String, text: String): Boolean {
        // v9.33.121: read-modify-write over the whole library - a load that
        // failed must never be saved back
        val all = loadOrNull(ctx) ?: run {
            NovaLog.quiet("Knowledge",
                java.io.IOException("unreadable notes - not overwriting them"))
            return false
        }
        val chunks = ArrayList(all.filter { it.doc != name })
        for (piece in chunkText(text)) chunks.add(Chunk(name, piece, piece.lowercase(), normOf(piece)))
        save(ctx, chunks)
        clearQaCache(ctx)
        return true
    }
'''

OLD_REMOVE = '''    fun removeDoc(ctx: Context, name: String) {
        val chunks = ArrayList(load(ctx).filter { it.doc != name })
        save(ctx, chunks)
        clearQaCache(ctx)
    }
'''

NEW_REMOVE = '''    fun removeDoc(ctx: Context, name: String): Boolean {
        // v9.33.121: the same read-modify-write, and this one runs from a
        // delete button - a note removed from a library NOVA could not read
        // used to delete every OTHER note as well
        val all = loadOrNull(ctx) ?: run {
            NovaLog.quiet("Knowledge",
                java.io.IOException("unreadable notes - not overwriting them"))
            return false
        }
        val chunks = ArrayList(all.filter { it.doc != name })
        save(ctx, chunks)
        clearQaCache(ctx)
        return true
    }
'''

knowledge = patch(KNOWLEDGE, [
    (OLD_LOAD, NEW_LOAD),
    (OLD_ADD, NEW_ADD),
    (OLD_REMOVE, NEW_REMOVE),
])

# ------------------------------------------------------------- the deck
OLD_STUDY_LOAD = '''    fun load(ctx: Context): MutableList<Card> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val out = mutableListOf<Card>()
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                // v9.33.101: one bad row used to throw out of the loop and the
                // catch below then returned an EMPTY deck - and the next save
                // wrote that empty deck over the file. A card is kept when it
                // still has both sides; a broken row costs only itself.
                val o = arr.optJSONObject(i) ?: continue
                val q = o.optString("q")
                val a = o.optString("a")
                if (q.isBlank() || a.isBlank()) continue
                out.add(Card(q, a, o.optInt("iv", 1), o.optLong("due", 0L)))
            }
        } catch (e: Exception) { NovaLog.quiet("Study", e) }
        return out
    }
'''

NEW_STUDY_LOAD = '''    fun load(ctx: Context): MutableList<Card> = loadOrNull(ctx) ?: mutableListOf()

    /**
     * v9.33.121 "The rest of the sweep": the cards on disk, or null when the
     * file exists and could not be read.
     *
     * v9.33.101 stopped one bad ROW from emptying the deck; the bad FILE was
     * still flattened to an empty deck, and [parseAndAdd] is read-modify-
     * write - so a deck NOVA could not parse was replaced by the handful of
     * cards a model reply happened to contain. The deck is small, but it is
     * the user's, and the review history in it is not re-derivable.
     */
    internal fun loadOrNull(ctx: Context): MutableList<Card>? {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val text = NcieFiles.readOrNull(f) ?: return null
        return try {
            val arr = JSONArray(text)
            val out = mutableListOf<Card>()
            for (i in 0 until arr.length()) {
                // v9.33.101: one bad row used to throw out of the loop and the
                // catch below then returned an EMPTY deck - and the next save
                // wrote that empty deck over the file. A card is kept when it
                // still has both sides; a broken row costs only itself.
                val o = arr.optJSONObject(i) ?: continue
                val q = o.optString("q")
                val a = o.optString("a")
                if (q.isBlank() || a.isBlank()) continue
                out.add(Card(q, a, o.optInt("iv", 1), o.optLong("due", 0L)))
            }
            out
        } catch (e: Exception) {
            NovaLog.quiet("Study", e)
            null
        }
    }
'''

OLD_PARSE = '''    fun parseAndAdd(ctx: Context, text: String): Int {
        val cards = load(ctx)
        val before = cards.size
'''

NEW_PARSE = '''    fun parseAndAdd(ctx: Context, text: String): Int {
        // v9.33.121: -1 is "the deck exists and could not be read, so nothing
        // was saved" - the caller says that instead of "No cards found", which
        // would blame the model reply for a storage failure.
        val cards = loadOrNull(ctx) ?: run {
            NovaLog.quiet("Study",
                java.io.IOException("unreadable card deck - not overwriting it"))
            return -1
        }
        val before = cards.size
'''

study = patch(STUDY, [
    (OLD_STUDY_LOAD, NEW_STUDY_LOAD),
    (OLD_PARSE, NEW_PARSE),
])

# ------------------------------------------------- the notes boundary
OLD_NK_ADD = '''    fun addDoc(c: Context, name: String, text: String) {
        Knowledge.addDoc(c, name, text)
        NcieLearn.invalidate()
        // v9.6.0 "Engine Pack": the FlashMap index - refresh this
        // source's chunk-count line as the grounding layer sees it
        // (callers are already off the main thread, like the write above).
        NcieGround.indexUpdate(c, name, text)
    }
'''

NEW_NK_ADD = '''    fun addDoc(c: Context, name: String, text: String): Boolean {
        // v9.33.121: only a write that actually happened may invalidate the
        // learned cache and move the index line - a refused save must leave
        // every derived view exactly as it was
        if (!Knowledge.addDoc(c, name, text)) return false
        NcieLearn.invalidate()
        // v9.6.0 "Engine Pack": the FlashMap index - refresh this
        // source's chunk-count line as the grounding layer sees it
        // (callers are already off the main thread, like the write above).
        NcieGround.indexUpdate(c, name, text)
        return true
    }
'''

OLD_NK_REMOVE = '''    fun removeDoc(c: Context, name: String) {
        Knowledge.removeDoc(c, name)
        NcieLearn.invalidate()
        // v9.6.0 "Engine Pack": the FlashMap index drops the source's line.
        NcieGround.indexUpdate(c, name, null)
    }
'''

NEW_NK_REMOVE = '''    fun removeDoc(c: Context, name: String): Boolean {
        if (!Knowledge.removeDoc(c, name)) return false
        NcieLearn.invalidate()
        // v9.6.0 "Engine Pack": the FlashMap index drops the source's line.
        NcieGround.indexUpdate(c, name, null)
        return true
    }
'''

nk = patch(NCIE_KNOWLEDGE, [
    (OLD_NK_ADD, NEW_NK_ADD),
    (OLD_NK_REMOVE, NEW_NK_REMOVE),
])

# ------------------------------------------------------------ the caller
OLD_CARDS = '''                        val n = Study.parseAndAdd(this@MainActivity, replyMsg.text)
                        toast(if (n > 0) "Saved $n cards - open Study in the menu" else "No cards found")
'''

NEW_CARDS = '''                        val n = Study.parseAndAdd(this@MainActivity, replyMsg.text)
                        // v9.33.121: -1 is a storage failure, not "no cards"
                        toast(if (n < 0) "Could not read your card deck, so nothing was saved"
                            else if (n > 0) "Saved $n cards - open Study in the menu"
                            else "No cards found")
'''

main = patch(MAIN, [(OLD_CARDS, NEW_CARDS)])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 288") != 1 or gradle.count("versionName '9.33.120'") != 1:
    print("FATAL: build.gradle is not at 9.33.120 (288)")
    sys.exit(1)
gradle = gradle.replace("versionCode 288", "versionCode 289")
gradle = gradle.replace("versionName '9.33.120'", "versionName '9.33.121'")
write(GRADLE, gradle)

# ------------------------------------------------- teach the verifier
OLD_V120 = '''check("v9.33.120: version is at least 9.33.120 (288)",
      version_at_least("9.33.120", 288))
'''

NEW_V120 = '''check("v9.33.120: version is at least 9.33.120 (288)",
      version_at_least("9.33.120", 288))

# v9.33.121 "The rest of the sweep": v9.33.119 swept four stores and stopped,
# and the two it left are the ones the user cannot re-create - the notes
# library and the card deck. Both are read-modify-write over the whole file.
_kn121 = load("Knowledge.kt")
_st121 = load("Study.kt")
check("v9.33.121: the notes store can say a read failed",
      _kn121.count("private fun loadOrNull(ctx: Context): ArrayList<Chunk>?") == 1 and
      _kn121.count("unreadable notes - not overwriting them") == 2 and
      _kn121.count("f.readText()") == 0)
check("v9.33.121: adding and removing a note answer their caller",
      _kn121.count("fun addDoc(ctx: Context, name: String, text: String): Boolean") == 1 and
      _kn121.count("fun removeDoc(ctx: Context, name: String): Boolean") == 1)
check("v9.33.121: the card deck can say a read failed",
      _st121.count("internal fun loadOrNull(ctx: Context): MutableList<Card>?") == 1 and
      _st121.count("unreadable card deck - not overwriting it") == 1 and
      _st121.count("f.readText()") == 0)
check("v9.33.121: the notes boundary only follows a real write",
      load("ncie/android/NcieKnowledge.kt").count("if (!Knowledge.addDoc(c, name, text)) return false") == 1 and
      load("ncie/android/NcieKnowledge.kt").count("if (!Knowledge.removeDoc(c, name)) return false") == 1)
check("v9.33.121: a storage failure is not reported as an empty reply",
      load("MainActivity.kt").count("if (n < 0) \\"Could not read your card deck, so nothing was saved\\"") == 1)
check("v9.33.121: version is at least 9.33.121 (289)",
      version_at_least("9.33.121", 289))
'''

ver = read(VERIFY)
if ver.count(OLD_V120) != 1:
    print("FATAL: the v9.33.120 tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_V120, NEW_V120))

# ---------------------------------------------------------------- verify
print("")
check("Knowledge: one loader that can fail", "private fun loadOrNull" in knowledge)
check("Knowledge: addDoc refuses a failed read", "fun addDoc(ctx: Context, name: String, text: String): Boolean" in knowledge)
check("Knowledge: removeDoc refuses a failed read", "fun removeDoc(ctx: Context, name: String): Boolean" in knowledge)
check("Study: one loader that can fail", "internal fun loadOrNull" in study)
check("Study: parseAndAdd says -1", "return -1" in study)
check("NcieKnowledge: the caches follow a real write", "if (!Knowledge.addDoc(c, name, text)) return false" in nk)
check("MainActivity: the card reply tells the truth", "if (n < 0)" in main)
check("version is 9.33.121 (289)",
      "versionName '9.33.121'" in gradle and "versionCode 289" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.121", 289)' in read(VERIFY))


def balanced(text):
    """Braces and parens outside comments and string literals - the same scan
    verify_patches.py runs, because a naive count is fooled by both."""
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
    return braces == 0 and parens == 0


for name, text in [("Knowledge.kt", knowledge), ("Study.kt", study),
                   ("ncie/android/NcieKnowledge.kt", nk), ("MainActivity.kt", main)]:
    check(name + ": structure is balanced", balanced(text))
    bad = []
    for m in re.finditer(r'"(?:[^"\\]|\\.)*"', text):
        for e in re.finditer(r"\\(.)", m.group(0)):
            if e.group(1) not in "tbnr\"'\\$su":
                bad.append(e.group(1))
    check(name + ": no invalid string escapes", not bad)

print("")
if fails:
    print("PATCH FAILED: %d problem(s)" % len(fails))
    for x in fails:
        print(" - " + x)
    sys.exit(1)
print("PATCH OK: v9.33.121 applied and self-verified")
