#!/usr/bin/env python3
# v5.5.0: (1) MiniCPM5-2B in the model catalog - the accuracy jump;
# (2) Strict answers mode - refuse instead of inventing facts.
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

B = "android/app/src/main/java/org/nova/"

# ============ ModelCatalog.kt: MiniCPM5-2B ====================================
P = B + "ModelCatalog.kt"
s = load(P)
s = rep(s, r'''        Entry(
            "qwen3-1.7b", "Qwen 3 1.7B", "Alibaba", "1.7B", "Q4_K_M",
            0x42000000L /* ~1.03 GB */, 3,
            "Your quality pick — best for study, documents and quizzes. Slower but smarter.",
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
        ),''',
r'''        Entry(
            "qwen3-1.7b", "Qwen 3 1.7B", "Alibaba", "1.7B", "Q4_K_M",
            0x42000000L /* ~1.03 GB */, 3,
            "Your quality pick — best for study, documents and quizzes. Slower but smarter.",
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
        ),
        Entry(
            "minicpm5-2b", "MiniCPM5 2B", "OpenBMB", "2B", "Q4_K_M",
            1_620_000_000L /* ~1.62 GB */, 3,
            "v5.5.0: smartest small model — far fewer made-up answers than any 1B. Reasoning model: thinks before answering, so the first word takes longer. ~25% slower than 1B. English only. Big download — use Wi-Fi.",
            "https://huggingface.co/bartowski/MiniCPM5-2B-GGUF/resolve/main/MiniCPM5-2B-Q4_K_M.gguf"
        ),''', P)
save(P, s)

# ============ NovaEngine.kt: thinking hint covers MiniCPM5 ==================
P = B + "NovaEngine.kt"
s = load(P)
s = rep(s, r'''        if ("think" !in n) return ""''',
r'''        if ("think" !in n && "minicpm" !in n) return ""''', P)
save(P, s)

# ============ Settings.kt: strictMode flag ===================================
P = B + "Settings.kt"
s = load(P)
s = rep(s, r'''    /** Offline Wikipedia: attach matching articles as background facts. */
    var wikiEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIKI, true)
        set(value) = prefs.edit().putBoolean(KEY_WIKI, value).apply()''',
r'''    /** Offline Wikipedia: attach matching articles as background facts. */
    var wikiEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIKI, true)
        set(value) = prefs.edit().putBoolean(KEY_WIKI, value).apply()

    /** v5.5.0: strict mode - refuse instead of inventing when the
     *  answer is not in the user's notes or offline Wikipedia. */
    var strictMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT, false)
        set(value) = prefs.edit().putBoolean(KEY_STRICT, value).apply()''', P)
s = rep(s, r'''        private const val KEY_WIKI = "wiki_enabled"''',
r'''        private const val KEY_WIKI = "wiki_enabled"
        private const val KEY_STRICT = "strict_mode"''', P)
save(P, s)

# ============ SettingsActivity.kt: the toggle ================================
P = B + "SettingsActivity.kt"
s = load(P)
s = rep(s, r'''        answers.addView(switchRow("Use offline Wikipedia", settings.wikiEnabled) {
            settings.wikiEnabled = it
        })''',
r'''        answers.addView(switchRow("Use offline Wikipedia", settings.wikiEnabled) {
            settings.wikiEnabled = it
        })
        answers.addView(switchRow("Strict answers (notes & Wikipedia only)", settings.strictMode) {
            settings.strictMode = it
            // v5.5.0: answers cached under the other mode must not be served
            Knowledge.clearQaCache(this)
        })''', P)
save(P, s)

# ============ MainActivity.kt: inject the strict guard ======================
P = B + "MainActivity.kt"
s = load(P)
s = rep(s, r'''    private fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true) {''',
r'''    /** v5.5.0: strict mode - grounded answers only, no invented facts. */
    private fun effectivePrompt(p: String): String =
        if (settings.strictMode)
            "STRICT MODE: Answer ONLY from the user's notes and the Wikipedia extracts " +
                "in this conversation. If they do not contain the answer, say exactly: " +
                "'My notes don't cover this.' Never invent facts, names, dates or numbers.\n\n" + p
        else p

    private fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true) {''', P)
s = rep(s, r'''                NovaEngine.send(prompt, settings.predictLength)''',
r'''                NovaEngine.send(effectivePrompt(prompt), settings.predictLength)''', P)
save(P, s)
print("OK - v5.5.0 patch applied to 5 files")
