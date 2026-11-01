#!/usr/bin/env python3
"""NOVA v6.2.0 app patch: instant conversation resets (KV/prompt cache).

Every place that used a full model reload (unload + loadModel from flash +
re-encode system prompt, multi-seconds) just to get a FRESH CONVERSATION now
calls NovaEngine.resetConversationAsync() instead: the model weights stay
resident in RAM and only the short system prompt is re-encoded, because the
v6.2 engine allows setSystemPrompt() any time the model is ready.

- NovaEngine.kt   : new resetConversation()/resetConversationAsync()
- MainActivity.kt : 8 reload sites swapped to instant resets (the
                    model-not-loaded recovery path keeps its full reload)
- build.gradle    : version 6.1.0 -> 6.2.0

Idempotent: re-running on a patched tree is a no-op.
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."


def patch(path, subs, marker):
    p = os.path.join(ROOT, path)
    src = open(p, encoding="utf-8").read()
    if marker in src:
        print(f"{path}: already patched")
        return
    for old, new, cnt in subs:
        n = src.count(old)
        assert n == cnt, f"{path}: pattern found {n}x (expected {cnt}): {old[:70]!r}"
        src = src.replace(old, new)
    open(p, "w", encoding="utf-8").write(src)
    print(f"{path}: patched")


KEEP = "__NOVA_KEEP_RELOAD__"

# ---------------------------------------------------------------- MainActivity
patch("android/app/src/main/java/org/nova/MainActivity.kt", [
    # 1. regenerate(): wait for the instant reset to finish, then re-send
    (
        """        if (NovaEngine.isModelLoaded && NovaEngine.contextDirty) {
            needsContextCarry = true
            NovaEngine.reloadAsync(this, settings.systemPrompt)
            scope.launch {
                val t0 = android.os.SystemClock.elapsedRealtime()
                while (NovaEngine.isLoading &&
                    android.os.SystemClock.elapsedRealtime() - t0 < 120_000) delay(200)
                if (NovaEngine.isModelLoaded) regenerateFrom(lastUser)
                else toast("Reload failed - try again")
            }
        } else {""",
        """        if (NovaEngine.isModelLoaded && NovaEngine.contextDirty) {
            needsContextCarry = true
            // v6.2.0: instant context reset (no model reload) - suspend
            // until the engine is clean, then re-send the question.
            scope.launch {
                if (NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt))
                    regenerateFrom(lastUser)
                else toast("Reload failed - try again")
            }
        } else {""",
        1,
    ),
    # 2. ensureModelReady(): also gate on a pending instant reset
    (
        """    private fun ensureModelReady(): Boolean {
        if (NovaEngine.isModelLoaded) return true
        return when {
            NovaEngine.isLoading -> {
                toast("Model is still loading — one moment"); false
            }""",
        """    private fun ensureModelReady(): Boolean {
        // v6.2.0: also gate on a pending instant reset
        if (NovaEngine.isModelLoaded && !NovaEngine.resetting) return true
        return when {
            NovaEngine.isLoading -> {
                toast("Model is still loading — one moment"); false
            }
            NovaEngine.resetting -> {
                toast("Resetting conversation — one moment"); false
            }""",
        1,
    ),
    # 3. keep the model-not-loaded recovery reload, park it out of the way
    (
        "NovaEngine.reloadAsync(this, settings.systemPrompt); false",
        KEEP + "; false",
        1,
    ),
    # 4. all remaining same-model context resets -> instant reset
    (
        "NovaEngine.reloadAsync(this, settings.systemPrompt)",
        "NovaEngine.resetConversationAsync(this, settings.systemPrompt)",
        3,
    ),
    (
        "NovaEngine.reloadAsync(this@MainActivity, settings.systemPrompt)",
        "NovaEngine.resetConversationAsync(this@MainActivity, settings.systemPrompt)",
        4,
    ),
    # 5. restore the recovery reload
    (
        KEEP + "; false",
        "NovaEngine.reloadAsync(this, settings.systemPrompt); false",
        1,
    ),
], "v6.2.0: also gate on a pending instant reset")

# ------------------------------------------------------------------ NovaEngine
patch("android/app/src/main/java/org/nova/NovaEngine.kt", [
    (
        """    /** Reloads the active model, starting a fresh conversation. */
    fun reloadAsync(context: Context, systemPrompt: String) {
        val path = activeModelPath ?: return
        val label = activeModelLabel
        loadAsync(context, path, label, systemPrompt)
    }
""",
        """    /** Reloads the active model, starting a fresh conversation. */
    fun reloadAsync(context: Context, systemPrompt: String) {
        val path = activeModelPath ?: return
        val label = activeModelLabel
        loadAsync(context, path, label, systemPrompt)
    }

    /**
     * v6.2.0: instant conversation reset ("new chat" without a model reload).
     *
     * The v6.2 engine allows setSystemPrompt() any time the model is ready,
     * not just right after load. Re-processing the system prompt clears the
     * KV cache and chat history inside the engine, so the app no longer has
     * to unload and reload the whole model file from flash (multi-seconds)
     * just to start a fresh conversation. The weights stay resident in RAM
     * and only the short system prompt is re-encoded - the model-in-RAM /
     * KV-cache / prompt-cache optimizations, with no quality change.
     *
     * [resetConversation] suspends until the context is clean and returns
     * success; [resetConversationAsync] is the fire-and-forget wrapper.
     * Both fall back to a full model reload on older engines.
     */
    @Volatile
    var resetting: Boolean = false
        private set

    suspend fun resetConversation(context: Context, systemPrompt: String): Boolean {
        val engine = engineRef ?: return false
        if (!isModelLoaded) return false
        resetting = true
        try {
            // never fight an in-flight generation - wait, like reloadAsync
            val t0 = SystemClock.elapsedRealtime()
            while (isGenerating && SystemClock.elapsedRealtime() - t0 < 60_000) delay(200)
            engine.setSystemPrompt(systemPrompt.ifBlank { " " })
            contextDirty = false
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // engine too old or not in ModelReady state - full reload
            return try {
                load(context, activeModelPath ?: return false, activeModelLabel, systemPrompt)
                true
            } catch (e2: Exception) {
                false
            }
        } finally {
            resetting = false
        }
    }

    fun resetConversationAsync(context: Context, systemPrompt: String) {
        scope.launch { resetConversation(context, systemPrompt) }
    }
""",
        1,
    ),
], "v6.2.0: instant conversation reset")

# ----------------------------------------------------------------- build.gradle
patch("android/app/build.gradle", [
    (
        "versionCode 38\n        versionName '6.1.0'",
        "versionCode 39\n        versionName '6.2.0'",
        1,
    ),
], "versionName '6.2.0'")
