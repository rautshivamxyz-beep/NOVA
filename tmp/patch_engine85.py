#!/usr/bin/env python3
"""NOVA one-shot patch for v9.33.17 "one-phrase modes, honest about a failed save".

Deterministic, idempotent, self-verifying (ends by running verify_patches.py).
Usage: patch_engine85.py <NOVA repo root>

What it does
  1. build.gradle : versionCode 184 -> 185, versionName 9.33.16 -> 9.33.17
  2. NcieModes.kt : away()/back() no longer report the new state when the
                    Settings write was swallowed - they say so instead
                    (audit finding P2; the v9.33.16 treatment of NcieFocus)
  3. verify_patches.py : the version pin moves to 9.33.17 + one new check
"""
import os
import sys
import subprocess

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = "android/app/src/main/java/org/nova/"


def edit(rel, pairs):
    p = os.path.join(ROOT, rel)
    s = open(p, encoding="utf-8").read()
    for old, new, n in pairs:
        got = s.count(old)
        if got != n:
            raise SystemExit("FATAL %s: expected %d of %r, found %d" % (rel, n, old[:60], got))
        s = s.replace(old, new)
    open(p, "w", encoding="utf-8").write(s)
    print("patched", rel)


# 1. the version bump
edit("android/app/build.gradle", [
    ("versionCode 184", "versionCode 185", 1),
    ("versionName '9.33.16'", "versionName '9.33.17'", 1),
])

# 2. the one-phrase modes stop claiming success on a failed save
AWAY_OLD = ('        try { Settings(ctx).autoReply = true } catch (e: Exception) '
            '{ NovaLog.quiet("NcieModes", e) }\n')
AWAY_NEW = ('        // v9.33.17: report honestly when the setting could not be\n'
            '        // saved - v9.33.15 recorded swallowed failures, v9.33.16/17\n'
            '        // make the deterministic commands stop claiming success.\n'
            '        val saved = try {\n'
            '            Settings(ctx).autoReply = true\n'
            '            true\n'
            '        } catch (e: Exception) {\n'
            '            NovaLog.quiet("NcieModes", e)\n'
            '            false\n'
            '        }\n')
BACK_OLD = ('        try { Settings(ctx).autoReply = false } catch (e: Exception) '
            '{ NovaLog.quiet("NcieModes", e) }\n')
BACK_NEW = ('        val saved = try {\n'
            '            Settings(ctx).autoReply = false\n'
            '            true\n'
            '        } catch (e: Exception) {\n'
            '            NovaLog.quiet("NcieModes", e)\n'
            '            false\n'
            '        }\n')
edit(BASE + "ncie/android/NcieModes.kt", [
    (AWAY_OLD, AWAY_NEW, 1),
    (BACK_OLD, BACK_NEW, 1),
    ('        return "Away mode ON - NOVA is auto-replying',
     '        if (!saved) return "Couldn\'t switch away mode on - NOVA could not save the setting."\n'
     '        return "Away mode ON - NOVA is auto-replying', 1),
    ('        return "Welcome back - auto-replies off',
     '        if (!saved) return "Couldn\'t switch away mode off - NOVA could not save the setting."\n'
     '        return "Welcome back - auto-replies off', 1),
])

# 3. verify_patches: the version pin + the new check
edit("tmp/verify_patches.py", [
    (
        'check("v9.33.16: version bumped for the proven-core pass", '
        'bg.count("versionName \'9.33.16\'") == 1 and bg.count("versionCode 184") == 1)',
        'check("v9.33.17: version bumped for the honest-modes pass", '
        'bg.count("versionName \'9.33.17\'") == 1 and bg.count("versionCode 185") == 1)\n'
        '\n'
        '# ---- v9.33.17 "one-phrase modes, honest about a failed save" ----\n'
        'check("v9.33.17: the one-phrase modes report a failed save honestly",\n'
        '      load("ncie/android/NcieModes.kt").count("Couldn\'t switch away mode") == 2)',
        1,
    ),
])

# self-verify
print("running verify_patches ...")
rc = subprocess.call([sys.executable, os.path.join(ROOT, "tmp/verify_patches.py"), ROOT])
if rc != 0:
    raise SystemExit("FATAL: verify_patches failed after patching")
print("patch_engine85 OK")
