#!/usr/bin/env python3
"""NOVA v6.2 engine patch: flash attention + resettable system prompt.

1. ai_chat.cpp  - enable flash attention in the context params. The KV
                  cache needs much less RAM, long-context inference gets
                  faster and power drops - output quality is identical.

2. InferenceEngineImpl.kt - allow setSystemPrompt() any time the model is
                  ready, not only right after load. The native side
                  (processSystemPrompt) already clears the KV cache and
                  chat history; the Kotlin guard was the only thing
                  blocking an instant "new chat" without reloading the
                  model file from flash.
"""
import sys


def patch(path, subs, marker):
    src = open(path, encoding="utf-8").read()
    if marker in src:
        print(f"{path}: already patched")
        return
    for old, new in subs:
        n = src.count(old)
        assert n == 1, f"{path}: pattern found {n}x (expected 1x): {old[:70]!r}"
        src = src.replace(old, new)
    open(path, "w", encoding="utf-8").write(src)
    print(f"{path}: patched")


cpp = sys.argv[1] if len(sys.argv) > 1 else "ai_chat.cpp"
kt = sys.argv[2] if len(sys.argv) > 2 else "InferenceEngineImpl.kt"

patch(cpp, [(
    "    ctx_params.n_threads = n_threads;\n"
    "    ctx_params.n_threads_batch = n_threads;",
    "    ctx_params.n_threads = n_threads;\n"
    "    ctx_params.n_threads_batch = n_threads;\n"
    "    // NOVA v6.2: flash attention - smaller KV cache, faster long-context\n"
    "    // inference, lower power; same output quality.\n"
    "    ctx_params.flash_attn = true;"
)], "NOVA v6.2")

patch(kt, [(
    'check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }',
    "// NOVA v6.2: allow re-setting the system prompt any time the model is\n"
    "            // ready - the native side clears the KV cache and chat\n"
    "            // history, giving an instant fresh conversation without\n"
    "            // reloading the model file from flash."
)], "NOVA v6.2")
