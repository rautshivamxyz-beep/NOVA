#!/usr/bin/env python3
"""NOVA v5.3 engine patch: pin llama.cpp threads to the big cores.

big.LITTLE phones (e.g. Helio G85: 2x A75 + 6x A55) run generation fastest
when only the big cores are used - the little cores fight them for memory
bandwidth. The stock code spreads up to 4 threads over all 8 cores.
"""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "ai_chat.cpp"
src = open(path, encoding="utf-8").read()

if "NOVA v5.3" in src:
    print("already patched")
    sys.exit(0)

NEW = '''    // NOVA v5.3: prefer the strongest cores only (big.LITTLE phones) -
    // little cores slow generation by fighting the big ones for memory
    // bandwidth. Count cores whose max frequency is within 80% of the
    // fastest one; fall back to the old heuristic when detection fails.
    int big_cores = 0;
    {
        int max_freq = 0;
        int freqs[64];
        int nfreq = 0;
        for (int c = 0; c < 64; ++c) {
            char cpufreq_path[96];
            snprintf(cpufreq_path, sizeof(cpufreq_path),
                     "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
            FILE *f = fopen(cpufreq_path, "r");
            if (!f) break;
            int v = 0;
            if (fscanf(f, "%d", &v) != 1) v = 0;
            fclose(f);
            if (v > 0 && nfreq < 64) {
                freqs[nfreq++] = v;
                if (v > max_freq) max_freq = v;
            }
        }
        if (max_freq > 0) {
            for (int i = 0; i < nfreq; ++i) {
                if (freqs[i] >= max_freq * 4 / 5) ++big_cores;
            }
        }
    }
    int n_threads = big_cores > 0 ? big_cores :
        std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                     N_THREADS_HEADROOM));
    if (n_threads > 8) n_threads = 8;'''

pat = re.compile(r"const int n_threads = std::max\(N_THREADS_MIN[^;]*\);")
src, n1 = pat.subn(NEW, src)
assert n1 == 1, "thread block not found (%d)" % n1

if "#include <cstdio>" not in src:
    old = "#include <cmath>"
    assert src.count(old) == 1
    src = src.replace(old, old + "\n#include <cstdio>", 1)

open(path, "w", encoding="utf-8").write(src)
print("patched ai_chat.cpp: n_threads -> big-core detection")
