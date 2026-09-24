#!/usr/bin/env python3
# v5.6.0b: two compile fixes in runJs - explicit return in the block-body
# override, and fully qualified ScrollView (not imported in MainActivity).
import hashlib, sys

P = "android/app/src/main/java/org/nova/MainActivity.kt"
src = open(P, encoding="utf-8").read()

fixes = [
    ("                out.append(m.message()).append('\\n')\n                true\n",
     "                out.append(m.message()).append('\\n')\n                return true\n"),
    (".setView(ScrollView(this).apply { addView(tv) })",
     ".setView(android.widget.ScrollView(this).apply { addView(tv) })"),
]
for old, new in fixes:
    n = src.count(old)
    if n != 1:
        print("ANCHOR COUNT %d (expected 1): %r" % (n, old[:50]))
        sys.exit(1)
    src = src.replace(old, new)
open(P, "w", encoding="utf-8").write(src)
print("md5:", hashlib.md5(open(P, "rb").read()).hexdigest())
print("OK")
