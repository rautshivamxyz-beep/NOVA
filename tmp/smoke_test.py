#!/usr/bin/env python3
"""NOVA emulator smoke test (automatic test, level 2).

Runs on a booted emulator (the workflow boots it). Installs the APK and
verifies, on a FRESH install with NO model downloaded:
  1. NOVA launches and stays alive
  2. the onboarding dialog can be dismissed
  3. the calculator answers "2+2" = 4 instantly - the v7.4 no-model-tools
     fix, tested end to end on a real (virtual) phone
  4. nothing crashed (no FATAL EXCEPTION in logcat)

Usage: smoke_test.py <path to app-release.apk>
Exits 1 if anything fails. Leaves screen.png behind for debugging.
"""
import re
import subprocess
import sys
import time

APK = sys.argv[1]


def adb(*args):
    r = subprocess.run(["adb"] + list(args), capture_output=True)
    return (r.stdout + r.stderr).decode(errors="replace").strip()


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def wait_for(pattern, timeout=30, what=""):
    t0 = time.time()
    while time.time() - t0 < timeout:
        x = dump()
        if re.search(pattern, x):
            return x
        time.sleep(2)
    print("TIMEOUT waiting for %r %s" % (pattern, what))
    return None


def tap_text(label):
    x = wait_for('text="%s"' % label, 15, "(button)")
    if not x:
        return False
    m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % label, x)
    if not m:
        return False
    cx = (int(m.group(1)) + int(m.group(3))) // 2
    cy = (int(m.group(2)) + int(m.group(4))) // 2
    adb("shell", "input", "tap", str(cx), str(cy))
    return True


def tap_send_button():
    """Fallback: tap the bottom-right clickable (the send button)."""
    x = dump()
    nodes = re.findall(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*clickable="true"', x) + \
        re.findall(r'clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
    if not nodes:
        return False
    best = max(nodes, key=lambda n: (int(n[1]) + int(n[3]), int(n[0]) + int(n[2])))
    cx = (int(best[0]) + int(best[2])) // 2
    cy = (int(best[1]) + int(best[3])) // 2
    adb("shell", "input", "tap", str(cx), str(cy))
    return True


def screenshot(name):
    try:
        with open(name, "wb") as f:
            f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    except Exception as e:
        print("screenshot failed: %s" % e)


ok = True

print("== 1. install + launch ==")
print(adb("install", "-r", APK))
adb("logcat", "-c")
print(adb("shell", "am", "start", "-W", "-n", "org.nova/.MainActivity"))
time.sleep(10)
pid = adb("shell", "pidof", "org.nova")
print("process pid: %s" % (pid or "NONE"))
if not pid:
    print("FAIL: NOVA is not running after launch")
    screenshot("screen.png")
    sys.exit(1)
print("PASS: launched and alive")

print("== 2. dismiss onboarding (fresh install, no model) ==")
if tap_text("Later"):
    print("PASS: onboarding dismissed via Later")
    time.sleep(2)
else:
    # maybe it never showed (fine) or already has a model (impossible here)
    print("note: onboarding dialog not found - continuing")

print("== 3. calculator with no model: 2+2 ==")
adb("shell", "input", "text", "2+2")
time.sleep(1)
adb("shell", "input", "keyevent", "66")  # IME_ACTION_SEND
x = wait_for("Exact calculation", 25, "(calculator reply)")
if not x:
    # fallback: the ENTER action may not have fired - tap the send button
    print("no reply via keyboard send - trying send-button tap")
    tap_send_button()
    x = wait_for("Exact calculation", 25, "(calculator reply, 2nd try)")
if x:
    if re.search(r"= 4", x):
        print("PASS: calculator answered 2+2 = 4 with no model loaded")
    else:
        print("FAIL: reply appeared but no '= 4' in it")
        ok = False
else:
    print("FAIL: no calculator reply at all (v7.4 no-model tools broken?)")
    ok = False

print("== 4. crash check ==")
screenshot("screen.png")
crash = adb("logcat", "-d", "-s", "AndroidRuntime:E")
if crash:
    print("FAIL: AndroidRuntime errors in logcat:")
    print(crash[:2000])
    ok = False
else:
    print("PASS: no crashes in logcat")

print("")
print("SMOKE TEST " + ("PASSED" if ok else "FAILED"))
sys.exit(0 if ok else 1)
