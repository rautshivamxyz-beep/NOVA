#!/usr/bin/env python3
# v5.4.8: photo notes (on-device OCR of photographed pages) + chat search.
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

# ============ KnowledgeActivity.kt: photos -> OCR -> notes ===================
P = "android/app/src/main/java/org/nova/KnowledgeActivity.kt"
s = load(P)

# 1) allow images in the picker
s = rep(s, r'''putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/plain"))''',
r'''putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/plain",
                            "image/jpeg", "image/png", "image/webp"))''', P)

# 2) route images to OCR before the byte checks
s = rep(s, r'''    private fun readText(uri: Uri): String = try {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""''',
r'''    private fun readText(uri: Uri): String = try {
        // v5.4.8: photos of handwritten/printed pages -> on-device OCR
        val mime = try { contentResolver.getType(uri) ?: "" } catch (e: Exception) { "" }
        if (mime.startsWith("image/")) return ocrImage(uri)
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""''', P)

# 3) the OCR helper (ML Kit is already bundled - fully offline)
s = rep(s, r'''    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()''',
r'''    /** v5.4.8: OCR a photo on-device (ML Kit, fully offline). */
    private fun ocrImage(uri: Uri): String = try {
        val img = com.google.mlkit.vision.common.InputImage.fromFilePath(this, uri)
        val rec = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            com.google.android.gms.tasks.Tasks.await(rec.process(img)).text
        } catch (e: Exception) { "" }
    } catch (e: Exception) { "" }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()''', P)
save(P, s)

# ============ ChatsActivity.kt: search across all chats =====================
P = "android/app/src/main/java/org/nova/ChatsActivity.kt"
s = load(P)

# 1) state
s = rep(s, r'''    private var exportChat: Chat? = null''',
r'''    private var exportChat: Chat? = null
    private var query: String = ""''', P)

# 2) the search box (below the header divider)
s = rep(s, r'''        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#1A2030")) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))''',
r'''        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#1A2030")) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

        // v5.4.8: search across all chats - names and message text
        val search = EditText(this).apply {
            hint = "Search chats"
            setTextColor(textMain)
            setHintTextColor(textDim)
            textSize = 14f
            singleLine = true
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString() ?: ""
                    refresh()
                }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(10)
        })''', P)

# 3) filter the list
s = rep(s, r'''    private fun refresh() {
        listInner.removeAllViews()
        val chats = ChatStore.list(this)
        val currentId = settings.currentChatId''',
r'''    private fun refresh() {
        listInner.removeAllViews()
        var chats = ChatStore.list(this)
        val currentId = settings.currentChatId

        // v5.4.8: filter by chat name OR any message inside it
        val q = query.trim()
        if (q.isNotEmpty()) {
            val ql = q.lowercase()
            chats = chats.filter {
                it.name.lowercase().contains(ql) ||
                    it.messages.any { m -> m.text.lowercase().contains(ql) }
            }
        }''', P)

# 4) different empty text when searching
s = rep(s, r'''                text = "No saved chats yet.\nEverything you talk about is kept on this phone only."''',
r'''                text = if (q.isNotEmpty()) "No chats match \"$q\""
                       else "No saved chats yet.\nEverything you talk about is kept on this phone only."''', P)
save(P, s)
print("OK - v5.4.8 patch applied to both files")
