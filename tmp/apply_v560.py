#!/usr/bin/env python3
# v5.6.0: coding & math toolkit.
#  1) math symbol row above the input (pi, sqrt, powers, operators...)
#  2) long-press code tools: "Explain code", "Find bugs"
#  3) "Run (JavaScript)" - executes the snippet offline in a WebView
import hashlib, sys

def load(p):
    return open(p, encoding="utf-8").read()

def save(p, s):
    open(p, "w", encoding="utf-8").write(s)
    print("md5 %s: %s" % (p, hashlib.md5(open(p, "rb").read()).hexdigest()))

def rep(src, old, new, fname):
    n = src.count(old)
    if n != 1:
        print("ANCHOR COUNT %d (expected 1) in %s: %r" % (n, fname, old[:70]))
        sys.exit(1)
    return src.replace(old, new)

P = "android/app/src/main/java/org/nova/MainActivity.kt"
s = load(P)

# 1) field for the symbol row
s = rep(s, r'''    private lateinit var micBtn: Button''',
r'''    private lateinit var micBtn: Button
    private lateinit var symRow: android.widget.HorizontalScrollView''', P)

# 2) the symbol row itself, above the input row
s = rep(s, r'''        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))''',
r'''        // v5.6.0: math symbol row - tap a symbol to insert it at the cursor
        symRow = android.widget.HorizontalScrollView(this).apply {
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
        }
        val symLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (sym in listOf("π", "√", "²", "³", "½", "×", "÷", "±", "≤", "≥", "≠", "≈", "°", "∑", "θ", "→")) {
            symLine.addView(Button(this).apply {
                text = sym; textSize = 16f; isAllCaps = false
                setTextColor(NovaTheme.text); background = null
                minWidth = 0; minimumWidth = 0
                setPadding(dp(10), dp(2), dp(10), dp(2))
                setOnClickListener {
                    input.text.insert(input.selectionStart, sym)
                    input.requestFocus()
                }
            })
        }
        symRow.addView(symLine)
        root.addView(symRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))''', P)

# 3) toggle button inside the pill (between the doc and mic buttons)
s = rep(s, r'''        pill.addView(docBtn, LinearLayout.LayoutParams(dp(38), dp(38)))''',
r'''        pill.addView(docBtn, LinearLayout.LayoutParams(dp(38), dp(38)))
        val symBtn = Button(this).apply {
            text = "√x"; textSize = 12f; isAllCaps = false
            setTextColor(textDim); background = null
            minWidth = 0; minimumWidth = 0
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener {
                symRow.visibility =
                    if (symRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        pill.addView(symBtn, LinearLayout.LayoutParams(
            dp(38), LinearLayout.LayoutParams.WRAP_CONTENT))''', P)

# 4) adapter callback for running JavaScript
s = rep(s, r'''        adapter.onRegenerate = { regenerateLast() }''',
r'''        adapter.onRegenerate = { regenerateLast() }
        adapter.onRunJs = { runJs(it) }''', P)

# 5) the JS runner
s = rep(s, r'''    /** v5.5.0: strict mode - grounded answers only, no invented facts. */''',
r'''    /** v5.6.0: run a JavaScript snippet offline in a WebView and show its
     *  console output - the only language Android executes on-device
     *  without shipping an extra engine. */
    private fun runJs(code: String) {
        val out = StringBuilder()
        val wv = android.webkit.WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                out.append(m.message()).append('\n')
                true
            }
        }
        val tv = TextView(this).apply {
            text = "running…"
            textSize = 13f; setTextColor(NovaTheme.text)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("JavaScript output")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .show()
        val html = "<html><body><script>try{\n" + code + "\n}catch(e){console.log('Error: '+e.message)}</script></body></html>"
        wv.loadData(html, "text/html", "utf-8")
        // WebView renders asynchronously - poll the captured output briefly
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                tv.text = if (out.isBlank()) "running…" else out.toString()
                if (ticks++ < 8) h.postDelayed(this, 500)
            }
        }
        h.postDelayed(poll, 400)
    }

    /** v5.5.0: strict mode - grounded answers only, no invented facts. */''', P)

# 6) adapter field
s = rep(s, r'''    var onRegenerate: (() -> Unit)? = null''',
r'''    var onRegenerate: (() -> Unit)? = null
    var onRunJs: ((String) -> Unit)? = null''', P)

# 7) code tools in the long-press menu
s = rep(s, r'''            ) else linkedMapOf(
                "Make study cards" to "Create 8 study flashcards from this material. Format each card EXACTLY as:\nQ: <question>\nA: <answer>\nNo numbering, no text before or after.",
                "Save to Knowledge" to "",
                "Make it sound like me" to "",
                "Regenerate" to ""
            )''',
r'''            ) else linkedMapOf(
                "Make study cards" to "Create 8 study flashcards from this material. Format each card EXACTLY as:\nQ: <question>\nA: <answer>\nNo numbering, no text before or after.",
                "Save to Knowledge" to "",
                "Make it sound like me" to "",
                "Regenerate" to ""
            ).apply {
                // v5.6.0: coding tools when the message contains a code block
                if (code.isNotBlank()) {
                    put("Explain code", "Explain this code step by step in simple language for a beginner. Say what each part does and why:\n-----\n$code\n-----")
                    put("Find bugs", "Check this code for bugs, mistakes or bad practices. Explain each issue and how to fix it. If it is correct, say it is correct:\n-----\n$code\n-----")
                    put("Run (JavaScript)", "")
                }
            }''', P)

# 8) handler for the run action
s = rep(s, r'''                        "Copy code" -> copyToClipboard(ctx, code)''',
r'''                        "Copy code" -> copyToClipboard(ctx, code)
                        "Run (JavaScript)" -> onRunJs?.invoke(code)''', P)
save(P, s)
print("OK - v5.6.0 patch applied")
