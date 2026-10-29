#!/usr/bin/env python3
"""NOVA one-shot patch step B for v9.33.83 "lookup quotes the line the user meant".

Run this BEFORE tmp/patch_engine86.py.

The v9.33.23 check pins the Devanagari function words to NcieGround.kt, which is
where the list lived. v9.33.83 moved the list to Tokens.STOP, so the check has
to follow it or the release would ship with a guard that no longer guards the
place the list is. This step does only that; the final verify_patches run at
the end of patch_engine86.py is what proves both halves landed together.

Usage: patch_engine86b.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
p = os.path.join(ROOT, "tmp/verify_patches.py")
s = open(p, encoding="utf-8").read()

OLD = '      load("ncie/android/NcieGround.kt").count(\'"का"\') == 1)'
NEW = '      load("ncie/Tokens.kt").count(\'"का"\') == 1)'

got = s.count(OLD)
if got != 1:
    raise SystemExit("FATAL: the v9.33.23 stopword check was not found exactly once (found %d)" % got)
open(p, "w", encoding="utf-8").write(s.replace(OLD, NEW))
print("patched tmp/verify_patches.py (v9.33.23 check follows the list to Tokens.kt)")
