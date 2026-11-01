#!/usr/bin/env python3
"""NOVA v7.5 engine patch: all-core prefill.

Reading (prompt prefill / batch decode) is compute-parallel - the little
A55 cores genuinely contribute there, unlike single-token generation where
they only fight the big cores for memory bandwidth. Keep the tuned 6
threads for generation, but give the batch decoder every core so notes
questions stop spending 45+ seconds before the first word.

Usage: patch_engine71.py <path to ai_chat.cpp>
"""
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "ai_chat.cpp"
src = open(path, encoding="utf-8").read()

if "NOVA v7.5" in src:
    print("ai_chat.cpp: v7.5 engine patch already applied")
    sys.exit(0)

OLD = "    ctx_params.n_threads_batch = n_threads;"
NEW = """    // NOVA v7.5: prompt prefill (batch decode) is compute-parallel - the
    // little cores genuinely help there, unlike single-token generation.
    // Use every core for reading; generation keeps the tuned thread count.
    int all_cores = (int) sysconf(_SC_NPROCESSORS_CONF);
    ctx_params.n_threads_batch = all_cores > 0 ? all_cores : n_threads;"""

n = src.count(OLD)
assert n == 1, "anchor found %dx (expected 1x)" % n
src = src.replace(OLD, NEW)
open(path, "w", encoding="utf-8").write(src)
print("ai_chat.cpp: v7.5 engine patch applied (all-core prefill)")
