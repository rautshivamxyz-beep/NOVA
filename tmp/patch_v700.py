#!/usr/bin/env python3
"""NOVA v7.0.0 app patch: ready-app polish pack.

1. Instant exact calculator: pure arithmetic ("12*(3+4)/2", "sqrt(144)",
   "sin(30)+2^3") is computed deterministically - no model call, no wrong
   answers, no waiting. Word problems and algebra still go to the AI.
2. Help & Tips: a drawer entry that explains every NOVA feature in one
   place (models, image input, notes/RAG, phone commands, study tools).
3. Quick follow-up chips after each answer (Explain simply / Give an
   example / Quiz me / Summarize) - rule-based, so they appear instantly.

Idempotent - safe to run on every CI build.
Usage: patch_v700.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
MA = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")

def rep(src, old, new, what):
    n = src.count(old)
    assert n == 1, "%s: anchor found %dx (expected 1x)" % (what, n)
    return src.replace(old, new)

# ---- 1) field for the follow-up chips row ----
F_OLD = '    private var lastNotesHit: List<Knowledge.Chunk> = emptyList()\n'
F_NEW = '''    private var lastNotesHit: List<Knowledge.Chunk> = emptyList()

    /** v7.0.0: quick follow-up buttons shown after each answer. */
    private var chipsRow: LinearLayout? = null
'''

# ---- 2) layout: chips row above the input pill ----
L_OLD = '''        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        updateSendLook()
'''
L_NEW = '''        // v7.0.0: quick follow-up chips, shown above the input pill
        val chipsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), 0, dp(12), dp(2))
            visibility = View.GONE
        }
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipsScroll.addView(chipsRow)
        root.addView(chipsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        updateSendLook()
'''

# ---- 3) drawer: Help & Tips entry ----
D_OLD = '        drawerPane.addView(drawerRow("New chat", R.drawable.ic_add) { newConversation() })\n'
D_NEW = '''        drawerPane.addView(drawerRow("Help & Tips", R.drawable.ic_lightbulb) { showHelpTips() })
        drawerPane.addView(drawerRow("New chat", R.drawable.ic_add) { newConversation() })
'''

# ---- 4) send(): exact calculator runs first ----
S_OLD = '        if (tryPhoneCommand(text)) return\n'
S_NEW = '''        if (solveArithmetic(text)) return
        if (tryPhoneCommand(text)) return
'''

# ---- 5) completion hook: show follow-up chips after each answer ----
C_OLD = '''                    if (tFirstToken > 0L) status.text =
                        "first word " + String.format(java.util.Locale.US, "%.1f", (tFirstToken - tStart) / 1000.0) +
                            "s - total " + String.format(java.util.Locale.US, "%.1f", (tEnd - tStart) / 1000.0) + "s"
'''
C_NEW = '''                    if (tFirstToken > 0L) status.text =
                        "first word " + String.format(java.util.Locale.US, "%.1f", (tFirstToken - tStart) / 1000.0) +
                            "s - total " + String.format(java.util.Locale.US, "%.1f", (tEnd - tStart) / 1000.0) + "s"
                    // v7.0.0: quick follow-up chips after each completed answer
                    if (newBubble) showFollowUps()
'''

# ---- 6) the new functions, after ocrImage ----
V_OLD = '''        } catch (e: Exception) {
            toast("Couldn't open image")
        }
    }
'''
V_NEW = '''        } catch (e: Exception) {
            toast("Couldn't open image")
        }
    }

    /** v7.0.0: every NOVA feature explained in one place. */
    private fun showHelpTips() {
        android.app.AlertDialog.Builder(this)
            .setTitle("How to use NOVA")
            .setMessage(("HOW TO CHAT\\n" +
                "\\u2022 LFM 1.2B Instruct = fast everyday chat. Qwen3 1.7B or LFM Thinking = smarter for study and maths (slower).\\n" +
                "\\u2022 Tap \\u221ax for math symbols, the mic for voice, and NOVA can read answers aloud.\\n\\n" +
                "PHOTO TO ANSWER\\n" +
                "\\u2022 Take a photo of a printed question, tap the attach button and pick it. NOVA reads the text on-device and offers Solve it.\\n\\n" +
                "YOUR NOTES\\n" +
                "\\u2022 Add PDFs in the Knowledge screen, then ask things like: summarise federalism, or quiz me on power sharing.\\n" +
                "\\u2022 Strict mode answers from your notes when they cover the topic; otherwise it answers from general knowledge and says so.\\n\\n" +
                "MATHS\\n" +
                "\\u2022 Pure calculations like 12*(3+4)/2 or sqrt(144) are computed exactly, instantly.\\n" +
                "\\u2022 For hard problems switch to Qwen3 1.7B or LFM Thinking first.\\n\\n" +
                "PHONE COMMANDS (short messages only)\\n" +
                "\\u2022 torch on / torch off\\n" +
                "\\u2022 call <name>\\n\\u2022 text <name> <message>\\n\\u2022 open whatsapp and say hi to <name>\\n" +
                "\\u2022 set alarm 6:30am\\n\\u2022 open youtube / chrome / camera\\n" +
                "\\u2022 wifi / bluetooth / hotspot (opens settings)").trim())
            .setPositiveButton("Close", null)
            .show()
    }

    /** v7.0.0: tappable follow-ups after each answer - rule-based, so they
     *  appear instantly with no model call. */
    private fun showFollowUps() {
        val line = chipsRow ?: return
        line.removeAllViews()
        val picks = listOf(
            "Explain simply" to "Explain that more simply, like I am 12 years old.",
            "Give an example" to "Give me one clear real-life example of that.",
            "Quiz me" to "Quiz me on this topic with 3 questions, one at a time.",
            "3-point summary" to "Summarize that in exactly 3 short bullet points."
        )
        for ((label, prompt) in picks) {
            line.addView(Button(this).apply {
                text = label; textSize = 12f; isAllCaps = false
                setTextColor(NovaTheme.text)
                minWidth = 0; minimumWidth = 0
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    setColor(NovaTheme.pill); cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), NovaTheme.border)
                }
                setOnClickListener {
                    (line.parent as? View)?.visibility = View.GONE
                    input.setText(prompt)
                    send()
                }
            })
        }
        (line.parent as? View)?.visibility = View.VISIBLE
    }

    /** v7.0.0: pure arithmetic gets an EXACT instant answer - no model
     *  needed, no wrong results. Anything with words (word problems,
     *  algebra) still goes to the AI. Trig is in degrees, log is base 10. */
    private fun solveArithmetic(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3 || t.length > 150 || t.contains('\\n')) return false
        val s = t.lowercase()
            .replace("\\u00d7", "*").replace("\\u00f7", "/")
            .replace("\\u2212", "-").replace("\\u2013", "-")
            .replace(",", "").replace(" ", "")
            .replace("sqrt", "q").replace("sin", "s").replace("cos", "c")
            .replace("tan", "t").replace("log", "g").replace("ln", "n")
            .replace("pi", "p")
        if (!Regex("^[0-9+\\\\-*/^%().qsctgnpe]+").matches(s)) return false
        // a word made only of function letters ("ten") is not arithmetic
        if (!Regex("[0-9]").containsMatchIn(s)) return false
        if (!Regex("[+\\\\-*/^%]").containsMatchIn(s) && !Regex("[qsctgnp]").containsMatchIn(s)) return false
        return try {
            val p = object {
                var i = 0
                fun peek(): Char = if (i < s.length) s[i] else ' '
                fun expr(): Double {
                    var r = term()
                    while (peek() == '+' || peek() == '-') {
                        val op = s[i++]; val b = term()
                        r = if (op == '+') r + b else r - b
                    }
                    return r
                }
                fun term(): Double {
                    var r = pw()
                    while (peek() == '*' || peek() == '/' || peek() == '%') {
                        val op = s[i++]; val b = pw()
                        r = when (op) { '*' -> r * b; '/' -> r / b; else -> r % b }
                    }
                    return r
                }
                fun pw(): Double {
                    val r = unary()
                    if (peek() == '^') { i++; return Math.pow(r, pw()) }
                    return r
                }
                fun unary(): Double {
                    if (peek() == '-') { i++; return -unary() }
                    if (peek() == '+') { i++ }
                    return atom()
                }
                fun atom(): Double {
                    val ch = peek()
                    if (ch == '(') { i++; val r = expr(); if (peek() == ')') i++; return r }
                    if (ch == 'q') { i++; return Math.sqrt(inner()) }
                    if (ch == 's') { i++; return Math.sin(Math.toRadians(inner())) }
                    if (ch == 'c') { i++; return Math.cos(Math.toRadians(inner())) }
                    if (ch == 't') { i++; return Math.tan(Math.toRadians(inner())) }
                    if (ch == 'g') { i++; return Math.log10(inner()) }
                    if (ch == 'n') { i++; return Math.log(inner()) }
                    if (ch == 'p') { i++; return Math.PI }
                    if (ch == 'e') { i++; return Math.E }
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    if (i == start) throw ArithmeticException("bad token")
                    return s.substring(start, i).toDouble()
                }
                fun inner(): Double {
                    if (peek() == '(') { i++; val r = expr(); if (peek() == ')') i++; return r }
                    return atom()
                }
            }
            val v = p.expr()
            // the whole input must be part of the math - no leftovers
            if (p.i != s.length) return false
            if (!v.isFinite()) return false
            val shown = if (Math.abs(v - Math.round(v)) < 1e-9)
                Math.round(v).toString()
            else String.format(java.util.Locale.US, "%.6g", v)
            val um = Msg(Role.USER, t)
            currentChat.messages.add(um); adapter.add(um)
            val reply = Msg(Role.ASSISTANT,
                "**= $shown**\\n\\n(Exact calculation - instant and never wrong. Word problems still go to the AI.)")
            currentChat.messages.add(reply); adapter.add(reply)
            scrollToEnd()
            scope.launch(Dispatchers.IO) {
                try { ChatStore.save(this@MainActivity, currentChat) } catch (e: Exception) { }
            }
            true
        } catch (e: Exception) { false }
    }
'''

src = open(MA, encoding="utf-8").read()
if "v7.0.0" in src:
    print("MainActivity.kt: v7.0.0 polish pack already applied")
else:
    src = rep(src, F_OLD, F_NEW, "chips field")
    src = rep(src, L_OLD, L_NEW, "chips layout")
    src = rep(src, D_OLD, D_NEW, "drawer help row")
    src = rep(src, S_OLD, S_NEW, "calculator routing")
    src = rep(src, C_OLD, C_NEW, "completion hook")
    src = rep(src, V_OLD, V_NEW, "new functions")
    open(MA, "w", encoding="utf-8").write(src)
    print("MainActivity.kt: v7.0.0 polish pack applied (calculator + help + follow-ups)")
