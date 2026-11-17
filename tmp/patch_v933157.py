#!/usr/bin/env python3
"""v9.33.157 "The radar audits itself".

One-shot applier. Every edit below must match EXACTLY ONCE; anything else
aborts the run before a single byte is written, so a stale anchor can never
ship a half-applied release.

What it does
------------
1. Bumps versionCode 324 -> 325 and versionName 9.33.156 -> 9.33.157 in
   android/app/build.gradle (the release gate in nova-apk.yml refuses an
   app-source change that reuses an already-released versionName).
2. Teaches tmp/verify_patches.py the new version.
3. Extends the nightly on-device audit (RegressionLab) from 11 to 15 checks
   with the deadline radar's two gated date parsers.
4. Deletes the stray nova-probe.txt left by a write-access probe.
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
fails = []


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def write(rel, text):
    with open(os.path.join(ROOT, rel), "w", encoding="utf-8") as f:
        f.write(text)


def sub_once(rel, old, new, label):
    """Replace `old` with `new` in `rel`, requiring exactly one match."""
    text = read(rel)
    n = text.count(old)
    if n != 1:
        fails.append("%s: anchor matched %d times (want 1)" % (label, n))
        return
    write(rel, text.replace(old, new))
    print("OK    " + label)


BUILD = "android/app/build.gradle"
VERIFY = "tmp/verify_patches.py"
LAB = "android/app/src/main/java/org/nova/ncie/evaluate/RegressionLab.kt"

# ---------------------------------------------------------------- 1. version
sub_once(
    BUILD,
    "        versionCode 324\n        versionName '9.33.156'\n",
    "        // v9.33.157 \"The radar audits itself\": A2's deadline radar is the\n"
    "        //   one offline feature whose answer the user acts on without a\n"
    "        //   second opinion - \"what's due\" is read once and believed. Its\n"
    "        //   date parsers are pure Kotlin and already pinned by\n"
    "        //   NcieDeadlinesTest, but that suite only runs in CI, so a refactor\n"
    "        //   that silently broke the radar would still ship a green build and\n"
    "        //   reach the device. The two gated parsers - a bare \"d/m\" that has\n"
    "        //   to earn its place next to a scheduling word, and \"may\" that has\n"
    "        //   to do the same - now run in the nightly on-device audit too, so\n"
    "        //   the device itself notices. Four checks, no behaviour change.\n"
    "        versionCode 325\n"
    "        versionName '9.33.157'\n",
    "build.gradle: versionName/versionCode",
)

# ------------------------------------------------------- 2. verify_patches
sub_once(
    VERIFY,
    'check("v9.33.156: version is at least 9.33.156 (324)",\n'
    '      version_at_least("9.33.156", 324))\n',
    'check("v9.33.157: version is at least 9.33.157 (325)",\n'
    '      version_at_least("9.33.157", 325))\n',
    "verify_patches.py: version floor",
)

# --------------------------------------------------------- 3. RegressionLab
sub_once(
    LAB,
    "    const val EXPECTED_CHECKS = 11\n",
    "    const val EXPECTED_CHECKS = 15\n",
    "RegressionLab.kt: EXPECTED_CHECKS 11 -> 15",
)

sub_once(
    LAB,
    "package org.nova.ncie.evaluate\n\n",
    "package org.nova.ncie.evaluate\n\n"
    "import org.nova.ncie.android.NcieDeadlines\n",
    "RegressionLab.kt: import NcieDeadlines",
)

ANCHOR = (
    '            store.search("photosynthesis light energy", 2).any { it.doc == "biology" }\n'
    "        },\n"
    "    )\n"
)
NEW_CHECKS = (
    '            store.search("photosynthesis light energy", 2).any { it.doc == "biology" }\n'
    "        },\n"
    "        // v9.33.157: the deadline radar, on the device, every night.\n"
    '        check("deadline radar refuses an impossible date") {\n'
    "            NcieDeadlines.dateAt(System.currentTimeMillis(), 31, 2, null) == null\n"
    "        },\n"
    '        check("deadline radar gates a bare numeric date behind a scheduling word") {\n'
    "            val today = System.currentTimeMillis()\n"
    '            NcieDeadlines.numericDates(today, "8.5 average score").isEmpty() &&\n'
    '                NcieDeadlines.numericDates(today, "assignment due 8/5").isNotEmpty()\n'
    "        },\n"
    '        check("deadline radar trusts a year-carrying date without a hint") {\n'
    '            NcieDeadlines.numericDates(System.currentTimeMillis(), "12/06/2026").isNotEmpty()\n'
    "        },\n"
    '        check("deadline radar gates \'may\' but trusts another month name") {\n'
    "            val today = System.currentTimeMillis()\n"
    '            NcieDeadlines.monthNameDates(today, "maybe 5 chapters").isEmpty() &&\n'
    '                NcieDeadlines.monthNameDates(today, "5 june").isNotEmpty()\n'
    "        },\n"
    "    )\n"
)
sub_once(LAB, ANCHOR, NEW_CHECKS, "RegressionLab.kt: four deadline checks")

# ----------------------------------------------------------- 4. probe cleanup
probe = os.path.join(ROOT, "nova-probe.txt")
if os.path.exists(probe):
    os.remove(probe)
    print("OK    removed nova-probe.txt")
else:
    print("SKIP  nova-probe.txt already gone")

# ------------------------------------------------------------- self-checks
if not fails:
    lab = read(LAB)
    if "NcieDeadlines.dateAt" not in lab or lab.count("NcieDeadlines.") != 6:
        fails.append("RegressionLab.kt: expected 6 NcieDeadlines references")
    if "EXPECTED_CHECKS = 15" not in lab:
        fails.append("RegressionLab.kt: EXPECTED_CHECKS not bumped")
    b = read(BUILD)
    if "versionName '9.33.157'" not in b or "versionCode 325" not in b:
        fails.append("build.gradle: version not bumped")
    if "version_at_least(\"9.33.157\", 325)" not in read(VERIFY):
        fails.append("verify_patches.py: version floor not taught")

if fails:
    print("\nPATCH FAILED: %d problem(s)" % len(fails))
    for f in fails:
        print(" - " + f)
    sys.exit(1)
print("\nPATCH OK: v9.33.157 applied cleanly")
