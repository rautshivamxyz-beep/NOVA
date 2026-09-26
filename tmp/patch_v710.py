#!/usr/bin/env python3
"""NOVA v7.1.0 app patch: launch polish - onboarding, chat export, speed readout.

1. First-run onboarding: a brand-new user (no model, never loaded one) gets
   a welcome dialog pointing at the model download, then Help & Tips.
2. Share this chat: a drawer item that exports the current conversation as
   plain text through Android's share sheet (WhatsApp, email, notes...).
3. Approximate generation speed (~tok/s) added to the reply status line,
   so performance is visible without a debug console.

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

# ---- 2) drawer: Share this chat ----
A2_OLD = '        drawerPane.addView(drawerRow("Help & Tips", R.drawable.ic_lightbulb) { showHelpTips() })\n'
A2_NEW = '''        drawerPane.addView(drawerRow("Help & Tips", R.drawable.ic_lightbulb) { showHelpTips() })
        drawerPane.addView(drawerRow("Share this chat", R.drawable.ic_send) { shareChat() })
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

# ---- 4) the new functions, after solveArithmetic ----
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

    /** v7.1: share the current conversation as plain text. */
    private fun shareChat() {
        try {
            if (currentChat.messages.isEmpty()) { toast("Nothing to share yet"); return }
            val sb = StringBuilder("NOVA - " + currentChat.name + "\\n\\n")
            for (m in currentChat.messages) {
                val who = if (m.role == Role.USER) "You" else "NOVA"
                sb.append(who).append(": ").append(stripThinking(m.text).trim()).append("\\n\\n")
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sb.toString().trim())
            }
            startActivity(Intent.createChooser(send, "Share chat"))
        } catch (e: Exception) { toast("Couldn't share the chat") }
    }
'''

src = open(MA, encoding="utf-8").read()
if "v7.1" in src:
    print("MainActivity.kt: v7.1.0 patch already applied")
else:
    src = rep(src, A1_OLD, A1_NEW, "onboarding hook")
    src = rep(src, A2_OLD, A2_NEW, "share drawer row")
    src = rep(src, A3_OLD, A3_NEW, "tok/s status")
    src = rep(src, A4_OLD, A4_NEW, "new functions")
    open(MA, "w", encoding="utf-8").write(src)
    print("MainActivity.kt: v7.1.0 launch polish applied (onboarding + share + tok/s)")
