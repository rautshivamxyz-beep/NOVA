#!/usr/bin/env python3
"""NOVA v7.1.0 app patch: launch polish - onboarding, chat export fix, speed readout.

1. First-run onboarding: a brand-new user (no model, never loaded one) gets
   a welcome dialog pointing at the model download, then Help & Tips.
2. Chat export (the existing "Share chat" drawer item) now strips hidden
   thinking-block text from thinking models, so exports stay clean.
3. Approximate generation speed (~tok/s) added to the reply status line.

No app lock - deliberately left out on the user's request.

Idempotent - safe to run on every CI build.
Usage: patch_v710.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
MA = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")

def rep(src, old, new, what):
    n = src.count(old)
    assert n == 1, "%s: anchor found %dx (expected 1x)" % (what, n)
    return src.replace(old, new)

# ---- 1) run onboarding after the UI is up ----
A1_OLD = '''        setContentView(buildUi())
        displayChatMessages()
'''
A1_NEW = '''        setContentView(buildUi())
        displayChatMessages()
        // v7.1: welcome brand-new users and point at the model download
        maybeOnboard()
'''

# ---- 2) chat export: strip hidden thinking text (the Share chat item
#         already existed - this just cleans what it exports) ----
A2_OLD = '''        for (msg in currentChat.messages) {
            sb.append(if (msg.role == Role.USER) "You: " else "NOVA: ").append(msg.text).append("\\n\\n")
        }
'''
A2_NEW = '''        for (msg in currentChat.messages) {
            // v7.1: never export hidden thinking-block text
            sb.append(if (msg.role == Role.USER) "You: " else "NOVA: ")
                .append(stripThinking(msg.text).trim()).append("\\n\\n")
        }
'''

# ---- 3) ~tok/s in the status line ----
A3_OLD = '''                    if (tFirstToken > 0L) status.text =
                        "first word " + String.format(java.util.Locale.US, "%.1f", (tFirstToken - tStart) / 1000.0) +
                            "s - total " + String.format(java.util.Locale.US, "%.1f", (tEnd - tStart) / 1000.0) + "s"
'''
A3_NEW = '''                    if (tFirstToken > 0L) {
                        status.text =
                            "first word " + String.format(java.util.Locale.US, "%.1f", (tFirstToken - tStart) / 1000.0) +
                                "s - total " + String.format(java.util.Locale.US, "%.1f", (tEnd - tStart) / 1000.0) + "s"
                        // v7.1: rough generation speed (tokens ~= chars/4)
                        val genChars = stripThinking(replyMsg.text).length
                        if (tEnd > tStart && genChars > 60) {
                            val tps = genChars / 4.0 / ((tEnd - tStart) / 1000.0)
                            status.text = status.text.toString() + "  -  ~" +
                                String.format(java.util.Locale.US, "%.1f", tps) + " tok/s"
                        }
                    }
'''

# ---- 4) the onboarding function, after solveArithmetic ----
A4_OLD = '''        } catch (e: Exception) { false }
    }
'''
A4_NEW = '''        } catch (e: Exception) { false }
    }

    /** v7.1: welcome a brand-new user and point at the model download. */
    private fun maybeOnboard() {
        try {
            val ggufs = ModelCatalog.modelsDir(this)
                .listFiles { f: java.io.File -> f.extension == "gguf" }
            if ((ggufs?.isNotEmpty() == true) || settings.lastModelPath != null) return
            android.app.AlertDialog.Builder(this)
                .setTitle("Welcome to NOVA")
                .setMessage(("NOVA is your private AI. It runs fully offline on this phone - " +
                    "nothing you type ever leaves the device.\\n\\n" +
                    "First, download a model (about 0.4-0.7 GB - use Wi-Fi):\\n\\n" +
                    "1. Open the menu (top-left)\\n" +
                    "2. Tap Models\\n" +
                    "3. Pick LFM 2.5 1.2B Instruct - the fast, smart everyday model\\n\\n" +
                    "Then just chat. For everything NOVA can do, tap Help & Tips in the menu.").trim())
                .setPositiveButton("Got it") { _, _ -> showHelpTips() }
                .setNegativeButton("Later", null)
                .show()
        } catch (e: Exception) { }
    }
'''

src = open(MA, encoding="utf-8").read()
if "v7.1" in src:
    print("MainActivity.kt: v7.1.0 patch already applied")
else:
    src = rep(src, A1_OLD, A1_NEW, "onboarding hook")
    src = rep(src, A2_OLD, A2_NEW, "share strip-thinking")
    src = rep(src, A3_OLD, A3_NEW, "tok/s status")
    src = rep(src, A4_OLD, A4_NEW, "onboarding function")
    open(MA, "w", encoding="utf-8").write(src)
    print("MainActivity.kt: v7.1.0 launch polish applied (onboarding + clean share + tok/s)")
