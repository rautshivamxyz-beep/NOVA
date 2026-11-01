#!/usr/bin/env python3
"""NOVA v6.2 engine patch: flash attention + resettable system prompt.

1. ai_chat.cpp  - enable flash attention in the context params. The KV
                  cache needs much less RAM, long-context inference gets
                  faster and power drops - output quality is identical.
                  Handles both llama.cpp APIs: the new
                  `flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED` enum
                  and the old `flash_attn = true` bool (auto-detected from
                  include/llama.h).

2. InferenceEngineImpl.kt - allow setSystemPrompt() any time the model is
                  ready, not only right after load. The native side
                  (processSystemPrompt) already clears the KV cache and
                  chat history; the Kotlin guard was the only thing
                  blocking an instant "new chat" without reloading the
                  model file from flash.

Usage: patch_engine62.py <llama.cpp checkout root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
CPP = os.path.join(ROOT, "examples/llama.android/lib/src/main/cpp/ai_chat.cpp")
KT = os.path.join(ROOT, "examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt")
HDR = os.path.join(ROOT, "include/llama.h")


def patch(path, subs, marker):
    src = open(path, encoding="utf-8").read()
    if marker in src:
        print(f"{os.path.basename(path)}: already patched")
        return
    for old, new in subs:
        n = src.count(old)
        assert n == 1, f"{path}: pattern found {n}x (expected 1x): {old[:70]!r}"
        src = src.replace(old, new)
    open(path, "w", encoding="utf-8").write(src)
    print(f"{os.path.basename(path)}: patched")


hdr = open(HDR, encoding="utf-8").read()
if "llama_flash_attn_type" in hdr:
    fa_set = "    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;"
    print("flash attention API: new (flash_attn_type enum)")
else:
    fa_set = "    ctx_params.flash_attn = true;"
    print("flash attention API: old (bool flash_attn)")

patch(CPP, [(
    "    ctx_params.n_threads = n_threads;\n"
    "    ctx_params.n_threads_batch = n_threads;",
    "    ctx_params.n_threads = n_threads;\n"
    "    ctx_params.n_threads_batch = n_threads;\n"
    "    // NOVA v6.2: flash attention - smaller KV cache, faster long-context\n"
    "    // inference, lower power; same output quality.\n"
    + fa_set,
)], "NOVA v6.2")

patch(KT, [(
    'check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }',
    "// NOVA v6.2: allow re-setting the system prompt any time the model is\n"
    "            // ready - the native side clears the KV cache and chat\n"
    "            // history, giving an instant fresh conversation without\n"
    "            // reloading the model file from flash."
)], "NOVA v6.2")
