#!/usr/bin/env python3
# v5.4.6: Notes filter (subject scope) + save-answer-to-Knowledge +
# cached-answer follow-up fix. Applied by CI (one-shot) to all 3 files.
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

# ============ Settings.kt: knowledgeExcluded preference =====================
P = "android/app/src/main/java/org/nova/Settings.kt"
s = load(P)
s = rep(s, r'''    /** Offline Wikipedia: attach matching articles as background facts. */
    var wikiEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIKI, true)
        set(value) = prefs.edit().putBoolean(KEY_WIKI, value).apply()''',
r'''    /** Offline Wikipedia: attach matching articles as background facts. */
    var wikiEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIKI, true)
        set(value) = prefs.edit().putBoolean(KEY_WIKI, value).apply()

    /** v5.4.6: documents excluded from Knowledge search via the Notes
     *  filter drawer row - "English only" during an English exam. */
    var knowledgeExcluded: MutableSet<String>
        get() = prefs.getStringSet(KEY_KNOWLEDGE_EXCL, emptySet())?.toMutableSet() ?: mutableSetOf()
        set(value) = prefs.edit().putStringSet(KEY_KNOWLEDGE_EXCL, value.toSet()).apply()''', P)
s = rep(s, r'''        private const val KEY_KNOWLEDGE = "knowledge_enabled"
        private const val KEY_WIKI = "wiki_enabled"''',
r'''        private const val KEY_KNOWLEDGE = "knowledge_enabled"
        private const val KEY_WIKI = "wiki_enabled"
        private const val KEY_KNOWLEDGE_EXCL = "knowledge_excluded"''', P)
save(P, s)

# ============ Knowledge.kt: search skips filtered-out documents ============
P = "android/app/src/main/java/org/nova/Knowledge.kt"
s = load(P)
s = rep(s, r'''    private var cache: ArrayList<Chunk>? = null''',
r'''    private var cache: ArrayList<Chunk>? = null

    /** v5.4.6: documents excluded from search by the user's Notes filter. */
    private fun excluded(ctx: Context): Set<String> = Settings(ctx).knowledgeExcluded''', P)
s = rep(s, r'''        val chunks = load(ctx)
        if (chunks.isEmpty()) return emptyList()
        val need = if (terms.size >= 2) 2 else 1
        val scored = ArrayList<Pair<Int, Chunk>>()
        for (c in chunks) {''',
r'''        val chunks = load(ctx)
        if (chunks.isEmpty()) return emptyList()
        val skip = excluded(ctx)
        val need = if (terms.size >= 2) 2 else 1
        val scored = ArrayList<Pair<Int, Chunk>>()
        for (c in chunks) {
            if (c.doc in skip) continue''', P)
s = rep(s, r'''        val chunks = load(ctx)
        if (chunks.isEmpty()) return null
        val docScores = HashMap<String, Int>()
        for (c in chunks) {''',
r'''        val chunks = load(ctx)
        if (chunks.isEmpty()) return null
        val skip = excluded(ctx)
        val docScores = HashMap<String, Int>()
        for (c in chunks) {
            if (c.doc in skip) continue''', P)
s = rep(s, r'''        val chunks = load(ctx)
        if (chunks.isEmpty()) return emptyList()
        // 1) pick the single best document for this query
        val docScores = HashMap<String, Int>()
        val chunkScores = IntArray(chunks.size)
        for ((i, c) in chunks.withIndex()) {''',
r'''        val chunks = load(ctx)
        if (chunks.isEmpty()) return emptyList()
        val skip = excluded(ctx)
        // 1) pick the single best document for this query
        val docScores = HashMap<String, Int>()
        val chunkScores = IntArray(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            if (c.doc in skip) continue''', P)
save(P, s)

# ============ MainActivity.kt: drawer, dialog, cache fix, long-press =======
P = "android/app/src/main/java/org/nova/MainActivity.kt"
s = load(P)
s = rep(s, r'''        drawerPane.addView(drawerRow("Knowledge", R.drawable.ic_doc) {
            startActivity(Intent(this, KnowledgeActivity::class.java))
        })''',
r'''        drawerPane.addView(drawerRow("Knowledge", R.drawable.ic_doc) {
            startActivity(Intent(this, KnowledgeActivity::class.java))
        })
        drawerPane.addView(drawerRow("Notes filter", R.drawable.ic_doc) { showNotesFilter() })''', P)
s = rep(s, r'''    private fun maybeAutoRemember(text: String) {''',
r'''    /** v5.4.6: pick which documents Knowledge searches - e.g. only the
     *  English PDFs during an English exam, so SST can never leak in. */
    private fun showNotesFilter() {
        val names = Knowledge.docs(this).map { it.first }
        if (names.isEmpty()) { toast("Import notes first (Knowledge screen)"); return }
        val excl = settings.knowledgeExcluded.toMutableSet()
        val checked = names.map { it !in excl }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Search these notes")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                val n = names[which]
                if (isChecked) excl.remove(n) else excl.add(n)
            }
            .setPositiveButton("OK") { _, _ ->
                settings.knowledgeExcluded = excl
                toast("Searching ${names.size - excl.size} of ${names.size} document(s)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun maybeAutoRemember(text: String) {''', P)
s = rep(s, r'''                    needsContextCarry = true
                    return''',
r'''                    // v5.4.6: a cached reply skipped the note search above,
                    // so a follow-up ("explain it more") had no notes to
                    // carry. Remember them now, like a fresh answer would.
                    val h2 = Knowledge.search(this, text)
                    if (h2.isNotEmpty()) { lastNotesHit = h2; lastNotesChatId = currentChat.id }
                    needsContextCarry = true
                    return''', P)
s = rep(s, r'''                "Make it sound like me" to "",
                "Regenerate" to ""''',
r'''                "Save to Knowledge" to "",
                "Make it sound like me" to "",
                "Regenerate" to ""''', P)
s = rep(s, r'''                        "Regenerate" -> onRegenerate?.invoke()''',
r'''                        "Save to Knowledge" -> {
                            val nm = "Saved: " + msgText.replace("\n", " ").take(28)
                            Thread { Knowledge.addDoc(ctx, nm, msgText) }.start()
                            Toast.makeText(ctx, "Saved to Knowledge: $nm", Toast.LENGTH_SHORT).show()
                        }
                        "Regenerate" -> onRegenerate?.invoke()''', P)
save(P, s)
print("OK - v5.4.6 patch applied to all 3 files")
