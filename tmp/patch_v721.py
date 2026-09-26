#!/usr/bin/env python3
"""NOVA v7.2.1 app patch: honest tok/s readout.

The v7.1 speed readout divided generated tokens by the FULL time since send -
which includes prefill (reading the question, and for notes/PDF answers,
reading the matched document sections). On a Helio G85 that reading phase
alone can take 20-40s, which made a healthy ~5 tok/s generation look like
~1.9 tok/s. Measure pure writing speed instead: tokens generated after the
first word arrived.

Idempotent - safe to run on every CI build.
Usage: patch_v721.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
MA = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")

def rep(src, old, new, what):
    n = src.count(old)
    assert n == 1, "%s: anchor found %dx (expected 1x)" % (what, n)
    return src.replace(old, new)

A_OLD = '''                        // v7.1: rough generation speed (tokens ~= chars/4)
                        val genChars = stripThinking(replyMsg.text).length
                        if (tEnd > tStart && genChars > 60) {
                            val tps = genChars / 4.0 / ((tEnd - tStart) / 1000.0)
'''
A_NEW = '''                        // v7.2.1: pure WRITING speed - measured after the first
                        // word, so slow question/notes reading (prefill) no longer
                        // drags the tok/s down (tokens ~= chars/4)
                        val genChars = stripThinking(replyMsg.text).length
                        if (tEnd > tFirstToken && genChars > 60) {
                            val tps = genChars / 4.0 / ((tEnd - tFirstToken) / 1000.0)
'''

src = open(MA, encoding="utf-8").read()
if "v7.2.1" in src:
    print("MainActivity.kt: v7.2.1 patch already applied")
else:
    src = rep(src, A_OLD, A_NEW, "tok/s window")
    open(MA, "w", encoding="utf-8").write(src)
    print("MainActivity.kt: v7.2.1 honest tok/s readout applied")
