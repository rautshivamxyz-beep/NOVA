#!/usr/bin/env python3
"""NOVA v8.0 engine patch: expose sampling-parameter setters.

The engine's sampler was hard-coded: temperature 0.3, the v6.1.0
anti-repetition profile (penalty_repeat 1.12 / last_n 128 + DRY) and the
llama.cpp common_params_sampling defaults for top_k / top_p / min_p
(40 / 0.95 / 0.05). The app could not tune any of it per model.

This patch adds a setSampling() API through the whole stack:

    Kotlin (interface + impl)  ->  JNI setSamplingParams  ->  the
    common_params_sampling values used by common_sampler_init.

Design (follows the existing v5.7.0/v6.x patch structure):
- The five values live in globals next to the engine globals.
- new_sampler() reads the globals, so a sampler created later (prepare())
  picks them up automatically; if the sampler already exists when
  setSampling() is called, it is rebuilt immediately so the NEXT
  generation uses the new values. Rebuilding resets the repeat-penalty
  history, same as a fresh conversation.
- Backwards compatible: when setSampling() is never called, the globals
  hold exactly the values the shipped v6.1.0-v7.5 engine used
  (temp 0.3, top_k 40, top_p 0.95, min_p 0.05, penalty_repeat 1.12).

llama.cpp conventions the caller should know:
    topK    <= 0 disables top-k filtering (uses the full vocab)
    topP    1.0 disables top-p filtering
    minP    0.0 disables min-p filtering
    repeatPenalty 1.0 disables the repeat penalty (DRY stays active)

Runs AFTER patch_engine53/55/56/62/70/71 on the llama.cpp pinned tree
(ggml-org/llama.cpp @ 6e60f35608ec6918b44a9839c0c433687165f086).

Takes the llama.android root path as argv[1] (like patch_engine56/62).
Idempotent - safe to run on every build.
"""
import os
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "."
CPP = os.path.join(root, "lib/src/main/cpp/ai_chat.cpp")
IMPL = os.path.join(root, "lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt")
IFACE = os.path.join(root, "lib/src/main/java/com/arm/aichat/InferenceEngine.kt")

def rep(path, old, new):
    src = open(path, encoding="utf-8").read()
    n = src.count(old)
    assert n == 1, "anchor not unique (%d) in %s:\n%s" % (n, path, old[:120])
    open(path, "w", encoding="utf-8").write(src.replace(old, new))
    print("patched: " + path)

def main():
    # idempotency guard (same style as patch_engine70/71)
    if "NOVA v8.0" in open(CPP, encoding="utf-8").read():
        print("ai_chat.cpp: v8.0 engine patch already applied")
        return

    # ---------------------------------------------------------------- ai_chat.cpp
    # 1) sampling globals next to the engine globals (after the v5.7.0 draft
    #    globals, so they sit with the other engine state). Defaults are the
    #    exact values the shipped engine used when setSampling is never called.
    rep(CPP, '''static std::vector<llama_token>           g_spec_queue;
''', '''static std::vector<llama_token>           g_spec_queue;

// NOVA v8.0: sampling parameters. Defaults are the values the shipped
// v6.1.0-v7.5 engine used, so never calling setSampling() changes nothing.
// top_k: 0 = disabled (full vocab); top_p: 1.0 = disabled; min_p: 0.0 =
// disabled; penalty_repeat: 1.0 = disabled.
static float g_samp_temp          = DEFAULT_SAMPLER_TEMP;
static int   g_samp_top_k         = 40;     // llama.cpp common_params_sampling default
static float g_samp_top_p         = 0.95f;  // llama.cpp common_params_sampling default
static float g_samp_min_p         = 0.05f;  // llama.cpp common_params_sampling default
static float g_samp_penalty_repeat = 1.12f; // v6.1.0 anti-repetition default
''')

    # 2) new_sampler() reads the globals (temp argument kept for the
    #    existing prepare() call site; the global always wins)
    rep(CPP, '''static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    // NOVA v6.1.0: anti-repetition. A mild classic repeat penalty plus the
    // DRY sampler kills runaway loops like 'published in 1948... 1951...
    // 1952...' while leaving normal text untouched.
    sparams.penalty_repeat      = 1.12f;
    sparams.penalty_last_n      = 128;
    sparams.dry_multiplier      = 0.8f;
    sparams.dry_base            = 1.75f;
    sparams.dry_allowed_length  = 2;
    sparams.dry_penalty_last_n  = 256;
    return common_sampler_init(g_model, sparams);
}
''', '''static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    // NOVA v8.0: sample from the settable globals (defaults reproduce the
    // shipped engine exactly - see the globals above).
    sparams.temp = g_samp_temp;
    sparams.top_k = g_samp_top_k;
    sparams.top_p = g_samp_top_p;
    sparams.min_p = g_samp_min_p;
    // NOVA v6.1.0: anti-repetition. A mild classic repeat penalty plus the
    // DRY sampler kills runaway loops like 'published in 1948... 1951...
    // 1952...' while leaving normal text untouched.
    sparams.penalty_repeat      = g_samp_penalty_repeat;
    sparams.penalty_last_n      = 128;
    sparams.dry_multiplier      = 0.8f;
    sparams.dry_base            = 1.75f;
    sparams.dry_allowed_length  = 2;
    sparams.dry_penalty_last_n  = 256;
    return common_sampler_init(g_model, sparams);
}
''')

    # 3) the setSamplingParams JNI, right after prepare() (the sampler is
    #    created there, so prepare() picks the globals up for free)
    rep(CPP, '''Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}
''', '''Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}

// NOVA v8.0: sampling-parameter setters. The values are stored globally and
// read whenever a sampler is created; calling this while a model is loaded
// rebuilds the sampler immediately so the next generation uses the new
// values. Rebuilding resets the repeat-penalty history, exactly like a
// fresh conversation. When never called, the defaults above reproduce the
// shipped engine bit-for-bit.
extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setSamplingParams(
        JNIEnv * /*env*/, jobject /*unused*/,
        jfloat temperature, jfloat top_p, jint top_k, jfloat min_p, jfloat repeat_penalty) {
    g_samp_temp           = temperature;
    g_samp_top_p          = top_p;
    g_samp_top_k          = top_k;
    g_samp_min_p          = min_p;
    g_samp_penalty_repeat = repeat_penalty;
    LOGi("%s: sampling set - temp=%.3f top_p=%.3f top_k=%d min_p=%.3f repeat_penalty=%.3f",
         __func__, temperature, top_p, top_k, min_p, repeat_penalty);
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = new_sampler(temperature);
    }
}
''')

    # ------------------------------------------------------- InferenceEngineImpl.kt
    # 4) the JNI declaration, next to the other externals
    rep(IMPL, '''    @FastNative
    private external fun unloadDraft()
''', '''    @FastNative
    private external fun unloadDraft()

    // NOVA v8.0: sampling parameters (applied on the next sampler init,
    // or immediately when a model is already loaded)
    @FastNative
    private external fun setSamplingParams(temperature: Float, topP: Float, topK: Int, minP: Float, repeatPenalty: Float)
''')

    # 5) the public suspend setter, in the loadDraftModel style
    rep(IMPL, '''    /**
     * Process the plain text system prompt
''', '''    /**
     * NOVA v8.0: set the sampler parameters used for generation. Apply
     * before the next conversation for a clean penalty history. Defaults
     * when never called are the shipped engine values
     * (temp 0.3, topP 0.95, topK 40, minP 0.05, repeatPenalty 1.12).
     *
     * Conventions: topK <= 0 disables top-k, topP 1.0 disables top-p,
     * minP 0.0 disables min-p, repeatPenalty 1.0 disables the repeat
     * penalty (DRY anti-repetition stays active either way).
     */
    override suspend fun setSampling(
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
    ) = withContext(llamaDispatcher) {
        check(_state.value is InferenceEngine.State.ModelReady) {
            "Sampling request discarded due to: ${_state.value.javaClass.simpleName}"
        }
        setSamplingParams(temperature, topP, topK, minP, repeatPenalty)
    }

    /**
     * Process the plain text system prompt
''')

    # ---------------------------------------------------------- InferenceEngine.kt
    # 6) the interface method
    rep(IFACE, '''    fun unloadDraftModel()
''', '''    fun unloadDraftModel()

    /**
     * NOVA v8.0: set the sampling parameters for generation (optional;
     * defaults keep the shipped engine behavior).
     *
     * @param temperature   softmax temperature (<= 0 samples greedily)
     * @param topP          nucleus probability threshold (1.0 = disabled)
     * @param topK          top-k token count (<= 0 = disabled / full vocab)
     * @param minP          min-p token probability relative to the best (0.0 = disabled)
     * @param repeatPenalty classic repeat penalty (1.0 = disabled)
     */
    suspend fun setSampling(
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
    )
''')

    print("OK - v8.0 engine patch applied (sampling setters: temp, top_p, top_k, min_p, repeat penalty)")

if __name__ == "__main__":
    main()
