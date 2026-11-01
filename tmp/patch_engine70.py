#!/usr/bin/env python3
"""NOVA v7.2 engine patch: thread tuning + generation-stop fix.

1. Big-core detection was too loose: cores at >= 80% of the fastest clock
   counted as "big". On the Helio G85 the little A55 cores run at 1.8 GHz,
   which is 90% of the 2.0 GHz A75 clock - so ALL 8 cores were called big
   and llama.cpp ran 8 threads. The little cores fight the big ones for
   memory bandwidth and generation slows down. Tighten the threshold to 95%
   (19/20), then use ~3 threads per big core capped at 6: 6 beats both 2
   and 8 on big.LITTLE chips like the G85.

2. Upstream llama.cpp bug (still present at our pin): after decoding the
   user prompt, current_position already includes user_prompt_size, but the
   stop position added user_prompt_size AGAIN - so the model generated up
   to n_predict + prompt-size tokens. Long prompts (knowledge documents)
   made NOVA ramble far past the chosen response length, wasting seconds
   per reply. Now it stops at exactly n_predict.

Idempotent - safe to run on every build.
Usage: patch_engine70.py <path to ai_chat.cpp>
"""
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "ai_chat.cpp"
src = open(path, encoding="utf-8").read()

def rep(src, old, new, what):
    n = src.count(old)
    assert n == 1, "%s: anchor found %dx (expected 1x)" % (what, n)
    return src.replace(old, new)

if "NOVA v7.2" in src:
    print("ai_chat.cpp: v7.2 engine patch already applied")
    sys.exit(0)

# ---- 1a) big-core detection: 80% -> 95% threshold ----
A_OLD = '''        if (max_freq > 0) {
            for (int i = 0; i < nfreq; ++i) {
                if (freqs[i] >= max_freq * 4 / 5) ++big_cores;
            }
        }
'''
A_NEW = '''        if (max_freq > 0) {
            // NOVA v7.2: 80% let same-generation little cores sneak in
            // (Helio G85: A55@1.8GHz = 90% of A75@2.0GHz) and we ended up
            // with all 8 cores counted as big. 95% keeps only real big
            // cores.
            for (int i = 0; i < nfreq; ++i) {
                if (freqs[i] >= max_freq * 19 / 20) ++big_cores;
            }
        }
'''

# ---- 1b) thread count: big_cores -> ~3 per big core, capped at 6 ----
B_OLD = '''    int n_threads = big_cores > 0 ? big_cores :
        std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                     N_THREADS_HEADROOM));
'''
B_NEW = '''    // NOVA v7.2: 2 big cores alone under-use the chip, 8 threads
    // over-subscribe it. ~3 threads per big core, clamped to [4, 6], is
    // the sweet spot on big.LITTLE phones like the Helio G85.
    int n_threads = big_cores > 0 ? std::min(6, std::max(4, big_cores * 3)) :
        std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                     N_THREADS_HEADROOM));
'''

# ---- 2) stop position: stop double-counting the prompt size ----
C_OLD = '''    // Update position
    current_position += user_prompt_size;
    stop_generation_position = current_position + user_prompt_size + n_predict;
'''
C_NEW = '''    // Update position
    current_position += user_prompt_size;
    // NOVA v7.2: upstream bug - user_prompt_size was added twice, so the
    // model generated up to n_predict + prompt-size tokens (rambling,
    // slow replies that ignored the response-length setting).
    // current_position already includes the prompt; stop at n_predict.
    stop_generation_position = current_position + n_predict;
'''

src = rep(src, A_OLD, A_NEW, "big-core threshold")
src = rep(src, B_OLD, B_NEW, "thread count")
src = rep(src, C_OLD, C_NEW, "stop position")
open(path, "w", encoding="utf-8").write(src)
print("ai_chat.cpp: v7.2 engine patch applied (6 threads on G85 + honest n_predict stop)")
