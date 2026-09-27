#!/usr/bin/env python3
"""One-shot v7.7.0 UI refresh applier (scaffold; deleted after the cycle).

Backslash-free by construction: every embedded text block uses real
newlines and binary writes, so the API commit channel cannot halve
anything. The extended guard is BUILT from the repo's current guard with
anchored insertions, never embedded.
"""
import hashlib
import io
import os
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SKIP_GIT = os.environ.get("NOVA_SKIP_GIT") == "1"
NL = chr(10)

def die(msg):
    print("FATAL: " + msg)
    sys.exit(1)

def rep(text, old, new, n):
    c = text.count(old)
    if c != n:
        die("anchor x%d (want %d): %r" % (c, n, old[:60]))
    return text.replace(old, new)

# ---- 1. Inter font family (download, sha-verified, res/font) ----
ZIP_URL = "https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip"
ZIP_SHA = "9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e"
FONTS = [
    ("extras/ttf/Inter-Regular.ttf", "inter_regular.ttf",
     "40d692fce188e4471e2b3cba937be967878f631ad3ebbbdcd587687c7ebe0c82"),
    ("extras/ttf/Inter-Medium.ttf", "inter_medium.ttf",
     "97ad806f526e41546d46365bb3a393145f75b7b1568913db74549ad8b8dba872"),
    ("extras/ttf/Inter-SemiBold.ttf", "inter_semibold.ttf",
     "78a843fade9d4612a5567302fb595b56976eb5fcebf4fea5a5912d638bafcde3"),
    ("extras/ttf/Inter-Bold.ttf", "inter_bold.ttf",
     "288316099b1e0a47a4716d159098005eef7c0066921f34e3200393dbdb01947f"),
]
print("downloading Inter 4.1 ...")
cache = os.environ.get("INTER_ZIP_CACHE")
if cache:
    data = open(cache, "rb").read()
else:
    data = urllib.request.urlopen(ZIP_URL, timeout=600).read()
if hashlib.sha256(data).hexdigest() != ZIP_SHA:
    die("inter zip sha mismatch")
zf = zipfile.ZipFile(io.BytesIO(data))
font_dir = os.path.join(ROOT, "android/app/src/main/res/font")
os.makedirs(font_dir, exist_ok=True)
for member, dest, sha in FONTS:
    b = zf.read(member)
    if hashlib.sha256(b).hexdigest() != sha:
        die("font sha mismatch: " + dest)
    open(os.path.join(font_dir, dest), "wb").write(b)
    print("font ok:", dest)

# ---- 2. static files, byte-exact ----
INTER_XML = '''<?xml version="1.0" encoding="utf-8"?>
<font-family xmlns:android="http://schemas.android.com/apk/res/android">
    <font android:font="@font/inter_regular" android:fontStyle="normal" android:fontWeight="400" />
    <font android:font="@font/inter_medium" android:fontStyle="normal" android:fontWeight="500" />
    <font android:font="@font/inter_semibold" android:fontStyle="normal" android:fontWeight="600" />
    <font android:font="@font/inter_bold" android:fontStyle="normal" android:fontWeight="700" />
</font-family>
'''
STYLES_XML = '''<resources>
    <style name="AppTheme" parent="android:style/Theme.Material.NoActionBar">
        <item name="android:fontFamily">@font/inter</item>
        <item name="android:colorAccent">#7C87FF</item>
        <item name="android:windowBackground">#0A0B0E</item>
        <item name="android:statusBarColor">#0A0B0E</item>
        <item name="android:navigationBarColor">#0A0B0E</item>
    </style>
</resources>
'''
NOVATHEME_KT = '''package org.nova

import android.graphics.Color

/**
 * App-wide colors, switchable between dark (default) and light.
 * Every screen reads from here so the light theme works everywhere.
 *
 * v7.7.0 "graphite + iris" refresh: the old navy-blue scheme is replaced
 * with a neutral graphite dark surface and a single vivid iris accent.
 * Property names are unchanged so every existing call site keeps working;
 * only the values moved.
 */
object NovaTheme {

    var dark = true
    var bg = Color.parseColor("#0A0B0E")
    var pill = Color.parseColor("#16181D")
    var surface = Color.parseColor("#1D2026")
    var border = Color.parseColor("#292C34")
    var divider = Color.parseColor("#1E2025")
    var text = Color.parseColor("#F3F4F8")
    var dim = Color.parseColor("#9BA1AD")
    var accent = Color.parseColor("#7C87FF")
    var accentDeep = Color.parseColor("#5A5FE0")
    var bubble = Color.parseColor("#5A5FE0")
    var sendDim = Color.parseColor("#26272E")
    var sendDimText = Color.parseColor("#5B6170")
    var scrim = Color.parseColor("#99000000")

    fun apply(darkTheme: Boolean) {
        dark = darkTheme
        if (darkTheme) {
            bg = Color.parseColor("#0A0B0E")
            pill = Color.parseColor("#16181D")
            surface = Color.parseColor("#1D2026")
            border = Color.parseColor("#292C34")
            divider = Color.parseColor("#1E2025")
            text = Color.parseColor("#F3F4F8")
            dim = Color.parseColor("#9BA1AD")
            accent = Color.parseColor("#7C87FF")
            accentDeep = Color.parseColor("#5A5FE0")
            bubble = Color.parseColor("#5A5FE0")
            sendDim = Color.parseColor("#26272E")
            sendDimText = Color.parseColor("#5B6170")
            scrim = Color.parseColor("#99000000")
        } else {
            bg = Color.parseColor("#FAFAFC")
            pill = Color.parseColor("#F0F1F5")
            surface = Color.parseColor("#E8EAF0")
            border = Color.parseColor("#DEE1E9")
            divider = Color.parseColor("#E9EBF1")
            text = Color.parseColor("#16181D")
            dim = Color.parseColor("#687082")
            accent = Color.parseColor("#5A5FE0")
            accentDeep = Color.parseColor("#4C51CE")
            bubble = Color.parseColor("#5A5FE0")
            sendDim = Color.parseColor("#E3E5EC")
            sendDimText = Color.parseColor("#A8AEBB")
            scrim = Color.parseColor("#66000000")
        }
    }
}
'''
for p, c in [("android/app/src/main/res/font/inter.xml", INTER_XML),
             ("android/app/src/main/res/values/styles.xml", STYLES_XML),
             ("android/app/src/main/java/org/nova/NovaTheme.kt", NOVATHEME_KT)]:
    fp = os.path.join(ROOT, p)
    os.makedirs(os.path.dirname(fp), exist_ok=True)
    open(fp, "wb").write(c.encode("utf-8"))
    print("written:", p)

# ---- 3. anchored edits (asserted counts; a miss aborts) ----
EDITS = {
"android/app/src/main/java/org/nova/MainActivity.kt": [
('''            setStroke(dp(1), Color.parseColor("#28314A"))
            cornerRadius = dp(17).toFloat()''',
 '''            setStroke(dp(1), NovaTheme.border)
            cornerRadius = dp(18).toFloat()''', 1),
('''            textSize = 19f
            letterSpacing = 0.14f''',
 '''            textSize = 19f
            letterSpacing = 0.18f''', 1),
("}, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(7) })",
 "}, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(7) })", 1),
("}, LinearLayout.LayoutParams(dp(34), dp(34)))",
 "}, LinearLayout.LayoutParams(dp(36), dp(36)))", 1),
("setPadding(dp(16), dp(10), dp(16), dp(6))",
 "setPadding(dp(16), dp(12), dp(16), dp(8))", 1),
("textSize = 34f", "textSize = 38f", 1),
("textSize = 20f", "textSize = 21f", 1),
('''                text = "Your private AI. Runs 100% on this phone."
                textSize = 12f''',
 '''                text = "Your private AI. Runs 100% on this phone."
                textSize = 13f''', 1),
("cornerRadius = dp(26).toFloat()", "cornerRadius = dp(28).toFloat()", 1),
("cornerRadius = dp(22).toFloat()", "cornerRadius = dp(26).toFloat()", 1),
("LinearLayout.LayoutParams.MATCH_PARENT, dp(44)",
 "LinearLayout.LayoutParams.MATCH_PARENT, dp(48)", 1),
('''            background = rippleOverlay(GradientDrawable().apply {
                setColor(accentDeep)
                cornerRadius = dp(20).toFloat()
            })''',
 '''            background = rippleOverlay(GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(accent, accentDeep)).apply {
                cornerRadius = dp(21).toFloat()
            })''', 1),
("pill.addView(sendBtn, LinearLayout.LayoutParams(dp(40), dp(40)))",
 "pill.addView(sendBtn, LinearLayout.LayoutParams(dp(42), dp(42)))", 1),
("val r = dp(ctx, 20).toFloat()", "val r = dp(ctx, 22).toFloat()", 1),
("holder.bubble.setPadding(dp(ctx, 15), dp(ctx, 11), dp(ctx, 15), dp(ctx, 11))",
 "holder.bubble.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))", 1),
("setBackgroundColor(NovaTheme.pill)", "setBackgroundColor(NovaTheme.surface)", 1),
("dp(292)", "dp(304)", 2),
('''            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(NovaTheme.text)
            background = null''',
 '''            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(NovaTheme.text)
            background = rippleOverlay(GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
            }, dp(14).toFloat())''', 1),
("typeface = Typeface.DEFAULT_BOLD", "setTypeface(typeface, Typeface.BOLD)", 2),
("""            sendBtn.background = rippleOverlay(GradientDrawable().apply {
                setColor(accentDeep); cornerRadius = dp(20).toFloat()
            })""",
 """            sendBtn.background = rippleOverlay(GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(accent, accentDeep)).apply {
                cornerRadius = dp(21).toFloat()
            })""", 1),
("""            sendBtn.background = rippleOverlay(GradientDrawable().apply {
                setColor(NovaTheme.sendDim); cornerRadius = dp(20).toFloat()
            })""",
 """            sendBtn.background = rippleOverlay(GradientDrawable().apply {
                setColor(NovaTheme.sendDim); cornerRadius = dp(21).toFloat()
            })""", 1),
],
"android/app/src/main/java/org/nova/ChatsActivity.kt": [
("typeface = Typeface.DEFAULT_BOLD", "setTypeface(typeface, Typeface.BOLD)", 2),
('setBackgroundColor(Color.parseColor("#1A2030"))',
 'setBackgroundColor(NovaTheme.divider)', 1),
('setBackgroundColor(Color.parseColor("#1F2635"))',
 'setBackgroundColor(NovaTheme.surface)', 1),
],
"android/app/src/main/java/org/nova/KnowledgeActivity.kt": [
("typeface = Typeface.DEFAULT_BOLD", "setTypeface(typeface, Typeface.BOLD)", 1),
],
"android/app/src/main/java/org/nova/ExamsActivity.kt": [
("typeface = Typeface.DEFAULT_BOLD", "setTypeface(typeface, Typeface.BOLD)", 2),
],
"android/app/src/main/java/org/nova/ModelsActivity.kt": [
("typeface = Typeface.DEFAULT_BOLD", "setTypeface(typeface, Typeface.BOLD)", 4),
],
"android/app/src/main/java/org/nova/SettingsActivity.kt": [
("typeface = Typeface.DEFAULT_BOLD", "setTypeface(typeface, Typeface.BOLD)", 2),
('parseColor("#FF6B6B")', 'parseColor("#F87171")', 1),
],
}
for p, edits in EDITS.items():
    fp = os.path.join(ROOT, p)
    src = open(fp, encoding="utf-8").read()
    for old, new, n in edits:
        src = rep(src, old, new, n)
    open(fp, "wb").write(src.encode("utf-8"))
    print("edited:", p, "(%d edits)" % len(edits))

# ---- 4. build guard v4 from the repo's current guard ----
NEW_SECTION = '''# ---- v7.7.0: UI refresh (graphite + iris palette, Inter typeface, all screens) ----
sty = open(os.path.join(ROOT, "android/app/src/main/res/values/styles.xml"), encoding="utf-8").read()
nt = load("NovaTheme.kt")
check("v7.7.0: Inter applied app-wide via the theme", sty.count("@font/inter") == 1)
check("v7.7.0: theme chrome matches graphite dark", sty.count("#0A0B0E") == 3)
fam = open(os.path.join(ROOT, "android/app/src/main/res/font/inter.xml"), encoding="utf-8").read()
check("v7.7.0: font family ships 4 weights", fam.count("inter_") == 4)
for w in ("inter_regular", "inter_medium", "inter_semibold", "inter_bold"):
    check("v7.7.0: %s.ttf present" % w,
          os.path.exists(os.path.join(ROOT, "android/app/src/main/res/font", w + ".ttf")))
check("v7.7.0: graphite + iris palette installed",
      nt.count("#7C87FF") == 2 and nt.count("#0A0B0E") == 2)
check("v7.7.0: old navy accent gone from the palette",
      nt.count("#5B9BFF") == 0 and nt.count("#2E6BE6") == 0)
check("v7.7.0: send button is an iris gradient",
      ma.count("GradientDrawable.Orientation.TL_BR") == 2)
check("v7.7.0: drawer widened (hide + layout)", ma.count("dp(304)") == 2)
check("v7.7.0: header buttons enlarged", ma.count("dp(36), dp(36)") == 2)
check("v7.7.0: no hardcoded navy stroke left", ma.count("#28314A") == 0)
check("v7.7.0: bold resolves inside the Inter family",
      ma.count("setTypeface(typeface, Typeface.BOLD)") == 2)
check("v7.7.0: rounded ripples on chips, round buttons and drawer rows",
      ma.count("rippleOverlay(GradientDrawable().apply {") == 5)
check("v7.7.0: ChatsActivity navy remnants gone",
      ca.count("#1A2030") == 0 and ca.count("#1F2635") == 0
      and ca.count("Typeface.DEFAULT_BOLD") == 0)
check("v7.7.0: SettingsActivity legacy red replaced",
      sa.count("#FF6B6B") == 0 and sa.count("Typeface.DEFAULT_BOLD") == 0)
check("v7.7.0: Knowledge/Exams/Models screens on Inter bold",
      ka.count("Typeface.DEFAULT_BOLD") == 0 and ea.count("Typeface.DEFAULT_BOLD") == 0
      and moa.count("Typeface.DEFAULT_BOLD") == 0)

'''
OLD_V = '''check("v7.6.6: version bumped for the v0.8.1 sync",
      bg.count("versionName '7.6.6'") == 1 and bg.count("versionCode 58") == 1)'''
NEW_V = '''check("v7.7.0: version bumped for the UI refresh",
      bg.count("versionName '7.7.0'") == 1 and bg.count("versionCode 59") == 1)'''
LOADS = 'ka = load("KnowledgeActivity.kt")'
MORE_LOADS = ('ca = load("ChatsActivity.kt")' + NL
              + 'sa = load("SettingsActivity.kt")' + NL
              + 'ea = load("ExamsActivity.kt")' + NL
              + 'moa = load("ModelsActivity.kt")')
GONE = "# ---- old code that must be GONE (a skipped patch leaves these behind) ----"
gp = os.path.join(ROOT, "tmp/verify_patches.py")
g = open(gp, encoding="utf-8").read()
g = rep(g, "from v6.2.3 .. v7.6.0", "from v6.2.3 .. v7.7.0", 1)
g = rep(g, LOADS, LOADS + NL + MORE_LOADS, 1)
g = rep(g, OLD_V, NEW_V, 1)
g = rep(g, GONE, NEW_SECTION + GONE, 1)
open(gp, "wb").write(g.encode("utf-8"))
print("guard extended to v7.7.0")

# ---- 5. version bump ----
gradle = os.path.join(ROOT, "android/app/build.gradle")
g2 = open(gradle, encoding="utf-8").read()
g2 = rep(g2, "versionCode 58", "versionCode 59", 1)
g2 = rep(g2, "versionName '7.6.6'", "versionName '7.7.0'", 1)
open(gradle, "wb").write(g2.encode("utf-8"))
print("build.gradle: 7.7.0 / 59")

# ---- 6. guard BEFORE commit ----
r = subprocess.run([sys.executable, os.path.join(ROOT, "tmp/verify_patches.py"), ROOT],
                   cwd=ROOT)
if r.returncode != 0:
    die("verify_patches.py failed - nothing committed")

# ---- 7. commit + push ----
if SKIP_GIT:
    print("NOVA_SKIP_GIT=1 - local rehearsal done, no commit")
    sys.exit(0)

def git(*cmd):
    r = subprocess.run(["git"] + list(cmd), cwd=ROOT, capture_output=True, text=True)
    out = r.stdout + r.stderr
    if "GITHUB_TOKEN" in os.environ:
        out = out.replace(os.environ["GITHUB_TOKEN"], "***")
    if out.strip():
        print(out.strip())
    if r.returncode != 0:
        die("git failed: git " + " ".join(cmd))

git("config", "user.name", "nova-patch-bot")
git("config", "user.email", "actions@users.noreply.github.com")
git("add", "-A")
MESSAGE = '''v7.7.0: UI refresh - graphite+iris palette, Inter typeface, all screens

Fonts: Inter 4.1 (SIL OFL). Palette: neutral graphite dark + iris accent.
All screens pick up Inter via the theme fontFamily; bold now resolves
inside the family. Bubbles/pill/chips rounder, send button is an iris
gradient circle, drawer rows get rounded ripples, navy remnants purged.
Guard extended to 98 checks.'''
git("commit", "-m", MESSAGE)
token = os.environ.get("GITHUB_TOKEN")
repo = os.environ.get("GITHUB_REPOSITORY")
if not token or not repo:
    die("GITHUB_TOKEN / GITHUB_REPOSITORY missing")
git("push", "https://x-access-token:" + token + "@github.com/" + repo + ".git",
    "HEAD:main")
print("v7.7.0 UI refresh committed and pushed")
