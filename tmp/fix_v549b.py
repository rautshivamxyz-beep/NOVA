#!/usr/bin/env python3
# v5.4.9b: inside TextView.apply{}, the unqualified 'text' resolves to the
# enclosing local val, not the TextView property - qualify the assignment.
import hashlib, sys

P = "android/app/src/main/java/org/nova/KnowledgeActivity.kt"
src = open(P, encoding="utf-8").read()

old = '            text = text.ifBlank { "(nothing was extracted from this document)" }'
new = '            this.text = text.ifBlank { "(nothing was extracted from this document)" }'
n = src.count(old)
if n != 1:
    print("ANCHOR COUNT %d (expected 1)" % n)
    sys.exit(1)
src = src.replace(old, new)
open(P, "w", encoding="utf-8").write(src)
print("md5:", hashlib.md5(open(P, "rb").read()).hexdigest())
print("OK")
