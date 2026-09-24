#!/usr/bin/env python3
# v5.4.8b: fix Kotlin compile error - singleLine is not a Kotlin property
# on EditText; the method setSingleLine() is the correct programmatic API.
import hashlib, sys

P = "android/app/src/main/java/org/nova/ChatsActivity.kt"
src = open(P, encoding="utf-8").read()

old = "            singleLine = true"
new = "            setSingleLine()"
n = src.count(old)
if n != 1:
    print("ANCHOR COUNT %d (expected 1)" % n)
    sys.exit(1)
src = src.replace(old, new)
open(P, "w", encoding="utf-8").write(src)
print("md5:", hashlib.md5(open(P, "rb").read()).hexdigest())
print("OK - singleLine fix applied")
