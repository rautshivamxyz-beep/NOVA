#!/usr/bin/env python3
"""NOVA v6.1.0 engine patch: anti-repetition sampling + q8_0 K-cache.

Runs AFTER patch_engine53.py + patch_engine55.py on the llama.cpp
examples/llama.android tree (llama.cpp pinned at 6e60f35608).

1. Sampling: mild repeat penalty + DRY (Don't-Repeat-Yourself) sampler.
   Fixes the 'published in 1948... 1951... 1952...' generation loops.
   Mild values: quality unchanged for normal text, loops die.
2. KV cache: K stored as q8_0 instead of f16 - ~25% less KV RAM,
   near-zero speed impact (V stays f16 so decode speed is untouched).

Takes the llama.android root path as argv[1].
"""
import sys

def rep(path, old, new):
    src = open(path, encoding="utf-8").read()
    n = src.count(old)
    assert n == 1, "anchor not unique (%d) in %s:\n%s" % (n, path, old[:120])
    open(path, "w", encoding="utf-8").write(src.replace(old, new))
    print("patched: " + path)

def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    CPP = root + "/lib/src/main/cpp/ai_chat.cpp"

    # ---- 1. anti-repetition sampling (classic penalty + DRY) ----
    old_sampler = """static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}"""
    new_sampler = """static common_sampler *new_sampler(float temp) {
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
}"""
    rep(CPP, old_sampler, new_sampler)

    # ---- 2. q8_0 K-cache ----
    old_ctx = """    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);"""
    new_ctx = """    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    // NOVA v6.1.0: store the K cache as q8_0 (~25% less KV RAM, near-zero
    // speed cost). V stays f16 so decode bandwidth is untouched.
    ctx_params.type_k = GGML_TYPE_Q8_0;
    auto *context = llama_init_from_model(g_model, ctx_params);"""
    rep(CPP, old_ctx, new_ctx)

    print("OK - v6.1.0 engine patch applied to ai_chat.cpp")

if __name__ == "__main__":
    main()
