#!/usr/bin/env python3
# v5.7.0 app side: speculative decoding toggle + draft model management.
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

# ============ NovaEngine.kt: draft model management ==========================
P = B + "NovaEngine.kt"
s = load(P)
s = rep(s, r'''        activeModelPath = path
        activeModelLabel = label
        contextDirty = false
    }''',
r'''        activeModelPath = path
        activeModelLabel = label
        contextDirty = false

        // v5.7.0: speculative decoding - a small draft model proposes
        // tokens that the main model verifies in batches. Only Qwen3
        // targets (the draft must share the vocabulary). The binding
        // swallows failures, so speculation just stays off if anything
        // is missing.
        try {
            if (Settings(context.applicationContext).specDecoding) {
                findDraftModel(context.applicationContext)?.let {
                    engine.loadDraftModel(it.absolutePath)
                }
            } else {
                engine.unloadDraftModel()
            }
        } catch (e: Exception) { }
    }

    /** v5.7.0: the Qwen3 0.6B file, when a Qwen3 target is active. */
    private fun findDraftModel(ctx: Context): java.io.File? {
        val target = (activeModelLabel + " " + (activeModelPath ?: "")).lowercase()
        if (!target.contains("qwen3")) return null
        return ModelCatalog.modelsDir(ctx).listFiles { f: java.io.File ->
            f.extension == "gguf" && f.name.lowercase().contains("qwen3-0.6b")
        }?.firstOrNull()
    }''', P)
save(P, s)

# ============ Settings.kt: the flag ==========================================
P = B + "Settings.kt"
s = load(P)
s = rep(s, r'''    /** v5.5.0: strict mode - refuse instead of inventing when the
     *  answer is not in the user's notes or offline Wikipedia. */
    var strictMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT, false)
        set(value) = prefs.edit().putBoolean(KEY_STRICT, value).apply()''',
r'''    /** v5.5.0: strict mode - refuse instead of inventing when the
     *  answer is not in the user's notes or offline Wikipedia. */
    var strictMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT, false)
        set(value) = prefs.edit().putBoolean(KEY_STRICT, value).apply()

    /** v5.7.0: speculative decoding - Qwen3 0.6B drafts tokens that the
     *  main model verifies in batches. Off by default: measure with the
     *  built-in speed timers before trusting it. */
    var specDecoding: Boolean
        get() = prefs.getBoolean(KEY_SPEC, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEC, value).apply()''', P)
s = rep(s, r'''        private const val KEY_STRICT = "strict_mode"''',
r'''        private const val KEY_STRICT = "strict_mode"
        private const val KEY_SPEC = "spec_decoding"''', P)
save(P, s)

# ============ SettingsActivity.kt: the toggle ================================
P = B + "SettingsActivity.kt"
s = load(P)
s = rep(s, r'''        answers.addView(switchRow("Strict answers (notes & Wikipedia only)", settings.strictMode) {
            settings.strictMode = it
            // v5.5.0: answers cached under the other mode must not be served
            Knowledge.clearQaCache(this)
        })''',
r'''        answers.addView(switchRow("Strict answers (notes & Wikipedia only)", settings.strictMode) {
            settings.strictMode = it
            // v5.5.0: answers cached under the other mode must not be served
            Knowledge.clearQaCache(this)
        })
        answers.addView(switchRow("Speculative decoding (Qwen3 only)", settings.specDecoding) {
            settings.specDecoding = it
            // takes effect the next time a model loads
            android.widget.Toast.makeText(this,
                if (it) "Needs a Qwen3 model + Qwen3 0.6B downloaded - active on next model load"
                else "Off after the next model load",
                android.widget.Toast.LENGTH_LONG).show()
        })''', P)
save(P, s)
print("OK - v5.7.0 app-side patch applied")
