#!/usr/bin/env python3
"""NOVA v8.3.0 - the study planner + doubt journal reach the drawer.

Planner.kt and PlannerActivity.kt ship as direct commits; this script
wires them in: manifest registration, the drawer row, the version bump.
Idempotent: exits 0 if already applied.
Run from the repo root. Commits and pushes unless UPDATE_NO_PUSH=1.
"""
import os
import sys

ROOT = os.getcwd()
MA = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")
MF = os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml")
GR = os.path.join(ROOT, "android/app/build.gradle")


def rd(p):
    with open(p, "r", encoding="utf-8") as f:
        return f.read()


def wr(p, s):
    with open(p, "w", encoding="utf-8") as f:
        f.write(s)


def replace_once(text, old, new, what):
    if old not in text:
        sys.exit("FATAL: anchor not found for " + what)
    if text.count(old) != 1:
        sys.exit("FATAL: anchor not unique for " + what)
    return text.replace(old, new)


if ".PlannerActivity" in rd(MF):
    print("v8.3.0 already applied - nothing to do.")
    sys.exit(0)

# ---- sanity: the new files must already be in the tree ----
for f in ["android/app/src/main/java/org/nova/Planner.kt",
          "android/app/src/main/java/org/nova/PlannerActivity.kt"]:
    if not os.path.exists(os.path.join(ROOT, f)):
        sys.exit("FATAL: missing " + f + " - commit the new files first")

# ---- 1. version bump: 8.2.0 (69) -> 8.3.0 (70) ----
g = rd(GR)
g = replace_once(g, "versionCode 69", "versionCode 70", "versionCode")
g = replace_once(g, "versionName '8.2.0'", "versionName '8.3.0'", "versionName")
wr(GR, g)

# ---- 2. manifest: register PlannerActivity ----
mf = rd(MF)
mf = replace_once(
    mf,
    '        <activity android:name=".ExamsActivity" android:exported="false" />',
    '        <activity android:name=".ExamsActivity" android:exported="false" />\n'
    '\n'
    '        <!-- v8.3.0: the study planner screen, from the drawer -->\n'
    '        <activity android:name=".PlannerActivity" android:exported="false" />',
    "manifest activity",
)
wr(MF, mf)

# ---- 3. drawer row right after the Study row ----
ma = rd(MA)
ma = replace_once(
    ma,
    "        drawerPane.addView(studyRow)\n",
    "        drawerPane.addView(studyRow)\n"
    "        drawerPane.addView(drawerRow(\"Study plan\", R.drawable.ic_lightbulb) {\n"
    "            startActivity(Intent(this, PlannerActivity::class.java))\n"
    "        })\n",
    "drawer row",
)
wr(MA, ma)

# ---- 4. verify ----
if ".PlannerActivity" not in rd(MF):
    sys.exit("FATAL: manifest marker missing after patch")
if "PlannerActivity::class.java" not in rd(MA):
    sys.exit("FATAL: drawer marker missing after patch")
if "versionCode 70" not in rd(GR) or "versionName '8.3.0'" not in rd(GR):
    sys.exit("FATAL: version bump missing after patch")
print("VERIFY OK: v8.3.0 planner wired in (manifest + drawer + version)")

# ---- 5. commit + push (source only - never workflow files) ----
if os.environ.get("UPDATE_NO_PUSH") == "1":
    print("simulation - push skipped")
    sys.exit(0)

import subprocess

# this script removes itself from the tree as part of the same commit
self_path = os.path.join(ROOT, "tmp/patch_parts/patch_v830.py")
if os.path.exists(self_path):
    subprocess.run(["git", "rm", "-q", "--ignore-unmatch", "tmp/patch_parts/patch_v830.py"], check=False)

for cmd in [
    ["git", "config", "user.name", "rautshivamxyz-beep"],
    ["git", "config", "user.email", "rautshivamxyz@gmail.com"],
    ["git", "add", "-A"],
    ["git", "commit", "-q", "-m",
     "v8.3.0: study planner + doubt journal - today's plan from exam "
     "dates, weak topics and the card deck (drawer: 'Study plan')."],
]:
    subprocess.run(cmd, check=True)
r = subprocess.run(["git", "push"], capture_output=True, text=True)
if r.returncode != 0:
    sys.exit("git push failed: " + r.stderr)
print("v8.3.0 PUSHED")
