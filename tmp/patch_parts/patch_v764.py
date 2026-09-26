#!/usr/bin/env python3
"""NOVA v7.6.4 - updater points at the public download repo.

The source repo is going private, so the in-app updater now reads
version.txt from the public NOVA-APK repo and downloads NOVA-latest.apk
from there. Idempotent: exits 0 if already applied.
Run from the repo root. Commits and pushes unless UPDATE_NO_PUSH=1.
"""
import os
import sys

ROOT = os.getcwd()
KOTLIN = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")
GRADLE = os.path.join(ROOT, "android/app/build.gradle")


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


src = rd(KOTLIN)

if "NOVA-APK/main/version.txt" in src:
    print("v7.6.4 already applied - nothing to do.")
    sys.exit(0)

# ---- 1. version bump ----
g = rd(GRADLE)
g = replace_once(g, "versionCode 55", "versionCode 56", "versionCode")
g = replace_once(g, "versionName '7.6.3'", "versionName '7.6.4'", "versionName")
wr(GRADLE, g)

# ---- 2. updater: public download repo instead of the (soon private) NOVA repo ----
OLD = (
    "    /** Latest GitHub release as (version, apkUrl), or null if none found. */\n"
    "    private fun fetchLatestRelease(): Pair<String, String>? {\n"
    "        val conn = java.net.URL(\n"
    "            \"https://api.github.com/repos/rautshivamxyz-beep/NOVA/releases/latest\"\n"
    "        ).openConnection() as java.net.HttpURLConnection\n"
    "        conn.connectTimeout = 10000\n"
    "        conn.readTimeout = 15000\n"
    "        conn.setRequestProperty(\"Accept\", \"application/vnd.github+json\")\n"
    "        try {\n"
    "            if (conn.responseCode != 200) throw RuntimeException(\"HTTP \" + conn.responseCode)\n"
    "            val obj = org.json.JSONObject(conn.inputStream.bufferedReader().readText())\n"
    "            val tag = obj.optString(\"tag_name\", \"\").removePrefix(\"v\")\n"
    "            val assets = obj.optJSONArray(\"assets\") ?: return null\n"
    "            for (i in 0 until assets.length()) {\n"
    "                val a = assets.getJSONObject(i)\n"
    "                if (a.optString(\"name\").endsWith(\".apk\")) {\n"
    "                    return Pair(tag, a.optString(\"browser_download_url\"))\n"
    "                }\n"
    "            }\n"
    "        } finally {\n"
    "            conn.disconnect()\n"
    "        }\n"
    "        return null\n"
    "    }"
)
NEW = (
    "    /** Latest published version from the public download repo, as (version, apkUrl). */\n"
    "    private fun fetchLatestRelease(): Pair<String, String>? {\n"
    "        val conn = java.net.URL(\n"
    "            \"https://raw.githubusercontent.com/rautshivamxyz-beep/NOVA-APK/main/version.txt\"\n"
    "        ).openConnection() as java.net.HttpURLConnection\n"
    "        conn.connectTimeout = 10000\n"
    "        conn.readTimeout = 15000\n"
    "        try {\n"
    "            if (conn.responseCode != 200) throw RuntimeException(\"HTTP \" + conn.responseCode)\n"
    "            val tag = conn.inputStream.bufferedReader().readText().trim().removePrefix(\"v\")\n"
    "            if (tag.isEmpty()) return null\n"
    "            return Pair(\n"
    "                tag,\n"
    "                \"https://raw.githubusercontent.com/rautshivamxyz-beep/NOVA-APK/main/NOVA-latest.apk\"\n"
    "            )\n"
    "        } finally {\n"
    "            conn.disconnect()\n"
    "        }\n"
    "    }"
)
src = replace_once(src, OLD, NEW, "fetchLatestRelease")

# ---- 3. honest download size ----
src = replace_once(
    src,
    '"Download and install now? The download is about 24 MB."',
    '"Download and install now? The download is about 35 MB."',
    "download size hint",
)
wr(KOTLIN, src)

# ---- 4. verify ----
checks = [
    (KOTLIN, "NOVA-APK/main/version.txt"),
    (KOTLIN, "NOVA-APK/main/NOVA-latest.apk"),
    (GRADLE, "versionCode 56"),
    (GRADLE, "versionName '7.6.4'"),
]
for path, marker in checks:
    if marker not in rd(path):
        sys.exit("FATAL: post-patch marker missing: " + marker + " in " + path)
if "api.github.com/repos/rautshivamxyz-beep/NOVA/releases" in rd(KOTLIN):
    sys.exit("FATAL: old private-repo update URL still present")
print("VERIFY OK: v7.6.4 updater now points at the public download repo")

# ---- 5. commit + push ----
if os.environ.get("UPDATE_NO_PUSH") == "1":
    print("simulation - push skipped")
    sys.exit(0)

import subprocess

for cmd in [
    ["git", "config", "user.name", "rautshivamxyz-beep"],
    ["git", "config", "user.email", "rautshivamxyz@gmail.com"],
    ["git", "add", "-A"],
    ["git", "commit", "-q", "-m",
     "v7.6.4: updater reads the public NOVA-APK download repo (version.txt + "
     "NOVA-latest.apk) - the source repo is going private."],
]:
    subprocess.run(cmd, check=True)
r = subprocess.run(["git", "push"], capture_output=True, text=True)
if r.returncode != 0:
    sys.exit("git push failed: " + r.stderr)
print("v7.6.4 PUSHED")
