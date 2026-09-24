#!/usr/bin/env python3
# v5.4.9: Knowledge document actions - view extracted text, summarize shortcut,
# export as .txt. Plus the MainActivity hand-off that runs the summarize.
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

# ============ Knowledge.kt: docText helper ===================================
P = "android/app/src/main/java/org/nova/Knowledge.kt"
s = load(P)
s = rep(s, r'''    fun docs(ctx: Context): List<Pair<String, Int>> {
        val seen = LinkedHashMap<String, Int>()
        for (c in load(ctx)) seen[c.doc] = (seen[c.doc] ?: 0) + 1
        return seen.map { it.key to it.value }
    }''',
r'''    fun docs(ctx: Context): List<Pair<String, Int>> {
        val seen = LinkedHashMap<String, Int>()
        for (c in load(ctx)) seen[c.doc] = (seen[c.doc] ?: 0) + 1
        return seen.map { it.key to it.value }
    }

    /** v5.4.9: full extracted text of one document (view / export). */
    fun docText(ctx: Context, name: String): String =
        load(ctx).filter { it.doc == name }.joinToString("\n\n") { it.text }''', P)
save(P, s)

# ============ KnowledgeActivity.kt: doc menu =================================
P = "android/app/src/main/java/org/nova/KnowledgeActivity.kt"
s = load(P)

# 1) export state field
s = rep(s, r'''    private lateinit var wikiBtn: Button''',
r'''    private lateinit var wikiBtn: Button
    private var exportName: String? = null''', P)

# 2) doc name becomes tappable
s = rep(s, r'''                text = name
                textSize = 14f; setTextColor(NovaTheme.text)
                setSingleLine(true)''',
r'''                text = name
                textSize = 14f; setTextColor(NovaTheme.text)
                setSingleLine(true)
                setOnClickListener { docMenu(name) }''', P)

# 3) doc menu functions + export handling in onActivityResult
s = rep(s, r'''    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7800 || resultCode != RESULT_OK || data == null) return''',
r'''    /** v5.4.9: per-document actions. */
    private fun docMenu(name: String) {
        val options = arrayOf("View text", "Summarize in chat", "Export as .txt", "Remove")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewDocText(name)
                    1 -> {
                        // hand off to the chat - it already has the full
                        // section-by-section summarize pipeline
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            putExtra("nova_autosend", "Summarize $name")
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    2 -> exportDoc(name)
                    3 -> AlertDialog.Builder(this)
                        .setMessage("Remove \"$name\" from knowledge?")
                        .setPositiveButton("Remove") { _, _ ->
                            Knowledge.removeDoc(this, name)
                            rebuildList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    /** v5.4.9: show what was actually extracted (OCR/PDF import check). */
    private fun viewDocText(name: String) {
        val text = Knowledge.docText(this, name)
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val tv = TextView(this).apply {
            text = text.ifBlank { "(nothing was extracted from this document)" }
            textSize = 13f; setTextColor(NovaTheme.text)
            setPadding(dp(18), dp(10), dp(6), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("$name  ($words words)")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .show()
    }

    /** v5.4.9: export the extracted text of a document. */
    private fun exportDoc(name: String) {
        exportName = name
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE,
                name.replace(Regex("[^A-Za-z0-9 ._-]"), "_") + ".txt")
        }
        startActivityForResult(intent, 7801)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // v5.4.9: document text export
        if (requestCode == 7801 && resultCode == RESULT_OK && data != null) {
            val uri = data.data
            val nm = exportName
            if (uri != null && nm != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(Knowledge.docText(this, nm).toByteArray())
                    }
                    toast("Saved")
                } catch (e: Exception) { toast("Export failed: ${e.message}") }
            }
            exportName = null
        }
        if (requestCode != 7800 || resultCode != RESULT_OK || data == null) return''', P)
save(P, s)

# ============ MainActivity.kt: autosend hand-off =============================
P = "android/app/src/main/java/org/nova/MainActivity.kt"
s = load(P)

# 1) pending state
s = rep(s, r'''    private var pendingCards = false''',
r'''    private var pendingCards = false
    private var pendingAutosend: String? = null''', P)

# 2) onNewIntent handles the hand-off
s = rep(s, r'''    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText()
    }''',
r'''    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText()
        // v5.4.9: Knowledge screen handed us a command (e.g. Summarize X)
        val auto = intent?.getStringExtra("nova_autosend")
        if (!auto.isNullOrBlank() && !generating) {
            if (NovaEngine.isModelLoaded) { input.setText(auto); send() }
            else pendingAutosend = auto
        }
    }''', P)

# 3) model-ready: run the command that had to wait
s = rep(s, r'''            NovaEngine.LoadState.Ready -> {
                NovaEngine.acknowledgeLoad()
                setStatus()
            }''',
r'''            NovaEngine.LoadState.Ready -> {
                NovaEngine.acknowledgeLoad()
                setStatus()
                // v5.4.9: a handed-off command that waited for the model
                pendingAutosend?.let {
                    if (!generating) { input.setText(it); send() }
                    pendingAutosend = null
                }
            }''', P)
save(P, s)
print("OK - v5.4.9 patch applied to 3 files")
