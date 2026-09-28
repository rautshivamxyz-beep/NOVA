#!/usr/bin/env python3
"""Teach verify_patches.py the v8.3.0 version + planner markers.

The verifier pins the exact versionName/versionCode; every release
updates that check. This also adds a marker so the planner drawer row
can never be silently lost. The script then RUNS the verifier itself
and only commits if it passes. Removes itself from the tree.
"""
import os
import subprocess
import sys

ROOT = os.getcwd()
VP = os.path.join(ROOT, "tmp/verify_patches.py")

with open(VP, "r", encoding="utf-8") as f:
    s = f.read()

OLD = (
    'check("v8.2.0: version bumped for the adaptive rules + sourced memory",\n'
    "      bg.count(\"versionName '8.2.0'\") == 1 and bg.count(\"versionCode 69\") == 1)"
)
NEW = (
    'check("v8.3.0: version bumped for the study planner + doubt journal",\n'
    "      bg.count(\"versionName '8.3.0'\") == 1 and bg.count(\"versionCode 70\") == 1)\n"
    "\n"
    "# ---- v8.3.0: the study planner + doubt journal (drawer row) ----\n"
    'check("v8.3.0: planner drawer row present (MainActivity)",\n'
    "      ma.count(\"PlannerActivity::class.java\") == 1)"
)

if 'check("v8.3.0: version bumped' in s:
    print("already taught - nothing to do.")
    sys.exit(0)
if s.count(OLD) != 1:
    sys.exit("FATAL: v8.2.0 version-check anchor not found exactly once")

with open(VP, "w", encoding="utf-8") as f:
    f.write(s.replace(OLD, NEW))
print("verify_patches.py updated for v8.3.0")

# self-validate: the full verifier must pass before we commit anything
r = subprocess.run(["python3", "tmp/verify_patches.py", "."],
                   capture_output=True, text=True)
sys.stdout.write(r.stdout)
if r.returncode != 0:
    sys.exit("FATAL: verifier failed after the edit - not committing")

# commit + push (source only)
subprocess.run(["git", "rm", "-q", "--ignore-unmatch",
                "tmp/patch_parts/patch_v830b.py"], check=False)
for cmd in [
    ["git", "config", "user.name", "rautshivamxyz-beep"],
    ["git", "config", "user.email", "rautshivamxyz@gmail.com"],
    ["git", "add", "-A"],
    ["git", "commit", "-q", "-m",
     "ci: teach the source verifier v8.3.0 (study planner release)."],
]:
    subprocess.run(cmd, check=True)
r = subprocess.run(["git", "push"], capture_output=True, text=True)
if r.returncode != 0:
    sys.exit("git push failed: " + r.stderr)
print("VERIFY FIX PUSHED")
