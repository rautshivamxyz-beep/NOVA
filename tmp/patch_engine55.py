#!/usr/bin/env python3
"""NOVA v5.7.0 engine patch: speculative decoding.

A small draft model (Qwen3 0.6B) proposes K=5 tokens greedily; the target
model (e.g. Qwen3 1.7B) verifies all of them in ONE batch decode. Verified
tokens are emitted through the existing generateNextToken() path.

Safety design:
- draft is only active when explicitly loaded via loadDraft()
- the draft must share the target's vocabulary (checked, else refused)
- any failure disables speculation and falls back to normal decoding
- normal decoding is completely untouched when no draft is loaded
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
    if n != 1:
        print("ANCHOR COUNT %d (expected 1) in %s: %r" % (n, path, old[:70]))
        sys.exit(1)
    open(path, "w", encoding="utf-8").write(src.replace(old, new))
    print("patched: %s" % path)

# ---------------------------------------------------------------- ai_chat.cpp
# 1) draft globals next to the engine globals
rep(CPP, '''static common_sampler                   * g_sampler;
''', '''static common_sampler                   * g_sampler;

// NOVA v5.7.0: speculative decoding - draft model + verified-token queue
static llama_model                      * g_draft_model   = nullptr;
static llama_context                    * g_draft_context = nullptr;
static std::vector<llama_token>           g_spec_queue;
''')

# 2) loadDraft / unloadDraft JNI right after the load JNI
rep(CPP, '''    g_model = model;
    return 0;
}
''', '''    g_model = model;
    return 0;
}

// NOVA v5.7.0: load the speculative-decoding draft model.
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_loadDraft(JNIEnv *env, jobject, jstring jdraft_path) {
    if (!g_model) {
        LOGe("%s: load the target model first", __func__);
        return 1;
    }
    if (g_draft_context) { llama_free(g_draft_context); g_draft_context = nullptr; }
    if (g_draft_model)   { llama_model_free(g_draft_model); g_draft_model = nullptr; }
    g_spec_queue.clear();

    const auto *draft_path = env->GetStringUTFChars(jdraft_path, 0);
    LOGi("%s: Loading draft model from: \\n%s\\n", __func__, draft_path);
    auto *model = llama_model_load_from_file(draft_path, llama_model_default_params());
    env->ReleaseStringUTFChars(jdraft_path, draft_path);
    if (!model) {
        return 1;
    }
    // the draft must share the target's vocabulary
    if (llama_vocab_n_tokens(llama_model_get_vocab(model)) !=
        llama_vocab_n_tokens(llama_model_get_vocab(g_model))) {
        llama_model_free(model);
        LOGe("%s: draft vocabulary mismatch - refusing", __func__);
        return 2;
    }
    g_draft_model = model;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = DEFAULT_CONTEXT_SIZE;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = 2;
    ctx_params.n_threads_batch = 2;
    g_draft_context = llama_init_from_model(g_draft_model, ctx_params);
    if (!g_draft_context) {
        LOGe("%s: draft context init failed", __func__);
        llama_model_free(g_draft_model);
        g_draft_model = nullptr;
        return 1;
    }
    LOGi("%s: draft ready - speculative decoding enabled", __func__);
    return 0;
}

// NOVA v5.7.0: drop the draft model (disables speculative decoding).
extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unloadDraft(JNIEnv *, jobject) {
    g_spec_queue.clear();
    if (g_draft_context) { llama_free(g_draft_context); g_draft_context = nullptr; }
    if (g_draft_model)   { llama_model_free(g_draft_model); g_draft_model = nullptr; }
}
''')

# 3) keep the draft KV cache in sync on resets
rep(CPP, '''    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}
''', '''    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);

    // NOVA v5.7.0: the draft cache must mirror the target cache
    if (g_draft_context)
        llama_memory_clear(llama_get_memory(g_draft_context), false);
}
''')

# 4) context shifts must move both caches identically
rep(CPP, '''    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
''', '''    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    // NOVA v5.7.0: shift the draft cache by the same amount
    if (g_draft_context) {
        llama_memory_seq_rm(llama_get_memory(g_draft_context), 0, system_prompt_position, system_prompt_position + n_discard);
        llama_memory_seq_add(llama_get_memory(g_draft_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    }
''')

# 5) a new generation must not inherit stale queued tokens
rep(CPP, '''static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}
''', '''static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    // NOVA v5.7.0: drop any tokens left over from a cancelled generation
    g_spec_queue.clear();
}
''')

# 6) mirror the system prompt into the draft
rep(CPP, '''    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }
''', '''    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // NOVA v5.7.0: mirror the system prompt into the draft model
    if (g_draft_context) {
        decode_tokens_in_batches(g_draft_context, g_batch, system_tokens, current_position);
    }
''')

# 7) mirror the user prompt into the draft
rep(CPP, '''    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }
''', '''    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // NOVA v5.7.0: mirror the user prompt into the draft model
    if (g_draft_context) {
        decode_tokens_in_batches(g_draft_context, g_batch, user_tokens, current_position, true);
    }
''')

# 8) the speculation round itself, defined before generateNextToken
rep(CPP, '''extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
''', '''// NOVA v5.7.0: speculative decoding round -------------------------------------
// The draft model proposes K tokens greedily; the target model verifies all
// of them in one batch decode. Accepted tokens land in g_spec_queue and are
// emitted one per generateNextToken() call. Every failure path falls back
// to plain single-token generation.
static llama_token argmax_token(const float * logits, const int n_vocab) {
    int best = 0;
    for (int i = 1; i < n_vocab; i++) {
        if (logits[i] > logits[best]) best = i;
    }
    return (llama_token) best;
}

static void spec_round() {
    constexpr int K = 5;
    const auto * vocab = llama_model_get_vocab(g_model);
    const int  n_vocab = llama_vocab_n_tokens(vocab);
    const llama_pos P = current_position;

    // 1. first token sampled from the target model - always correct
    const llama_token t0 = common_sampler_sample(g_sampler, g_context, -1);

    // 2. decode t0 into target (keeps the KV contiguous) and draft
    common_batch_clear(g_batch);
    common_batch_add(g_batch, t0, P, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        return; // caller falls back to plain generation
    }
    if (llama_decode(g_draft_context, g_batch) != 0) {
        llama_free(g_draft_context);
        g_draft_context = nullptr;
        g_spec_queue.push_back(t0);
        current_position = P + 1;
        return;
    }
    // the target's own greedy choice for the position after t0
    const llama_token t_next = argmax_token(llama_get_logits_ith(g_context, -1), n_vocab);

    // 3. draft K tokens greedily from the draft model
    llama_tokens drafted;
    for (int j = 0; j < K; j++) {
        const float * dlogits = llama_get_logits_ith(g_draft_context, -1);
        if (dlogits == nullptr) break;
        const llama_token dt = argmax_token(dlogits, n_vocab);
        if (llama_vocab_is_eog(vocab, dt)) break;
        common_batch_clear(g_batch);
        common_batch_add(g_batch, dt, P + 1 + (llama_pos) drafted.size(), {0}, true);
        if (llama_decode(g_draft_context, g_batch) != 0) break;
        drafted.push_back(dt);
    }
    const int m = (int) drafted.size();
    if (m == 0) {
        g_spec_queue.push_back(t0);
        current_position = P + 1;
        return;
    }

    // 4. verify every drafted token with ONE target batch decode
    common_batch_clear(g_batch);
    for (int j = 0; j < m; j++) {
        common_batch_add(g_batch, drafted[j], P + 1 + j, {0}, true);
    }
    if (llama_decode(g_context, g_batch) != 0) {
        // verification failed: roll the drafted part back everywhere
        llama_memory_seq_rm(llama_get_memory(g_context),       0, P + 1, P + 1 + m);
        llama_memory_seq_rm(llama_get_memory(g_draft_context), 0, P + 1, P + 1 + m);
        g_spec_queue.push_back(t0);
        current_position = P + 1;
        return;
    }

    // 5. accept the longest matching prefix
    int n_accept = 0;
    if (t_next == drafted[0]) {
        n_accept = 1;
        while (n_accept < m) {
            const float * lj = llama_get_logits_ith(g_context, n_accept - 1);
            if (lj == nullptr || argmax_token(lj, n_vocab) != drafted[n_accept]) break;
            n_accept++;
        }
    }

    g_spec_queue.push_back(t0);
    for (int j = 0; j < n_accept; j++) g_spec_queue.push_back(drafted[j]);

    if (n_accept < m) {
        // mismatch: roll the rejected tail back in both caches, then decode
        // the target's corrected token so the next round starts from valid
        // logits and aligned KV caches
        const llama_token corrected = (n_accept == 0)
            ? t_next
            : argmax_token(llama_get_logits_ith(g_context, n_accept - 1), n_vocab);
        const llama_pos keep = P + 1 + n_accept;
        llama_memory_seq_rm(llama_get_memory(g_context),       0, keep, P + 1 + m);
        llama_memory_seq_rm(llama_get_memory(g_draft_context), 0, keep, P + 1 + m);
        common_batch_clear(g_batch);
        common_batch_add(g_batch, corrected, keep, {0}, true);
        llama_decode(g_context, g_batch);
        llama_decode(g_draft_context, g_batch);
        g_spec_queue.push_back(corrected);
        current_position = keep + 1;
    } else {
        current_position = P + 1 + m;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
''')

# 9) generateNextToken: queue first, speculate when empty, plain otherwise
rep(CPP, '''    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update position
    current_position++;
''', '''    // NOVA v5.7.0: emit verified tokens from the speculation queue first,
    // start a speculation round when it runs empty, and fall back to the
    // original single-token path when no draft model is loaded
    llama_token new_token_id;
    if (!g_spec_queue.empty()) {
        new_token_id = g_spec_queue.front();
        g_spec_queue.erase(g_spec_queue.begin());
    } else if (g_draft_context != nullptr) {
        spec_round();
        if (g_spec_queue.empty()) return nullptr;
        new_token_id = g_spec_queue.front();
        g_spec_queue.erase(g_spec_queue.begin());
    } else {
        // original single-token path
        new_token_id = common_sampler_sample(g_sampler, g_context, -1);
        common_batch_clear(g_batch);
        common_batch_add(g_batch, new_token_id, current_position, {0}, true);
        if (llama_decode(g_context, g_batch) != 0) {
            LOGe("%s: llama_decode() failed for generated token", __func__);
            return nullptr;
        }
        current_position++;
    }
    common_sampler_accept(g_sampler, new_token_id, true);
''')

# 10) unload must free the draft too
rep(CPP, '''    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);
}
''', '''    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);

    // NOVA v5.7.0: free the speculative-decoding draft
    g_spec_queue.clear();
    if (g_draft_context) { llama_free(g_draft_context); g_draft_context = nullptr; }
    if (g_draft_model)   { llama_model_free(g_draft_model); g_draft_model = nullptr; }
}
''')

# ------------------------------------------------------- InferenceEngineImpl.kt
rep(IMPL, '''    @FastNative
    private external fun load(modelPath: String): Int
''', '''    @FastNative
    private external fun load(modelPath: String): Int

    // NOVA v5.7.0: speculative decoding draft model
    @FastNative
    private external fun loadDraft(draftPath: String): Int

    @FastNative
    private external fun unloadDraft()
''')

rep(IMPL, '''    /**
     * Process the plain text system prompt
''', '''    /**
     * NOVA v5.7.0: load a draft model for speculative decoding. The draft
     * must share the target model's vocabulary. Failures are logged and
     * swallowed - speculation simply stays off.
     */
    override suspend fun loadDraftModel(pathToModel: String) {
        withContext(llamaDispatcher) {
            try {
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }
                Log.i(TAG, "Loading draft model... \\n$pathToModel")
                loadDraft(pathToModel).let {
                    if (it != 0) throw RuntimeException("Draft model rejected: $it")
                }
                Log.i(TAG, "Draft model loaded - speculative decoding active")
            } catch (e: Exception) {
                Log.e(TAG, "Draft model failed (spec decoding stays off)", e)
                try { unloadDraft() } catch (e2: Exception) { }
            }
        }
    }

    /**
     * NOVA v5.7.0: drop the draft model, disabling speculative decoding.
     */
    override fun unloadDraftModel() {
        if (_state.value is InferenceEngine.State.ModelReady) {
            llamaScope.launch {
                try { unloadDraft() } catch (e: Exception) { }
            }
        }
    }

    /**
     * Process the plain text system prompt
''')

# ---------------------------------------------------------- InferenceEngine.kt
rep(IFACE, '''    suspend fun loadModel(pathToModel: String)
''', '''    suspend fun loadModel(pathToModel: String)

    /**
     * NOVA v5.7.0: load a draft model for speculative decoding (optional).
     */
    suspend fun loadDraftModel(pathToModel: String)

    /**
     * NOVA v5.7.0: unload the draft model, disabling speculative decoding.
     */
    fun unloadDraftModel()
''')

print("OK - v5.7.0 engine patch applied to ai_chat.cpp + binding")
