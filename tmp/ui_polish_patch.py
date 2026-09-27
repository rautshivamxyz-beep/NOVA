#!/usr/bin/env python3
"""NOVA v7.6.5 (#1): UI polish - pressed-state feedback everywhere.

1. rippleOverlay(bg, radiusPx): a file-private helper that wraps any
   background (or none) in an accent-tinted bounded RippleDrawable (API
   21+, same floor as the tinted ProgressBar already in use), with an
   optional round mask so icon-only buttons ripple in their own shape.
2. Applied to every custom-drawn control: roundButton (header add/menu,
   doc button), mic, doc-clear, send (initial + both updateSendLook
   branches), the welcome suggestion cards, the follow-up chips (which
   also gain 6dp spacing), the math symbol row, and the copy/regen
   action icons in MessageAdapter.
3. versionCode 56 -> 57, versionName '7.6.4' -> '7.6.5' so the polished
   build is identifiable in the APK repo.
4. Guard update (ripple marker + version check), then run it - fails
   loud BEFORE any commit happens.
"""
import subprocess
import sys

MA = "android/app/src/main/java/org/nova/MainActivity.kt"
BG = "android/app/build.gradle"
GUARD = "tmp/verify_patches.py"

src = open(MA, encoding="utf-8").read()
assert src.count("rippleOverlay(") == 0, "MainActivity already patched"

def swap(old, new, what):
    assert src.count(old) == 1, "anchor not exactly once (" + what + "): " + old[:60]
    return src.replace(old, new)

# 1. the helper, file-private, before MessageAdapter
src = swap(
    "class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {",
    "/** v7.6.5: pressed-state feedback for custom-drawn controls. Wraps any
 *  background (or none) in a bounded ripple tinted with the theme accent;
 *  radiusPx adds a round mask so icon-only buttons ripple in their own
 *  shape instead of a rectangle. */
private fun rippleOverlay(
    bg: android.graphics.drawable.Drawable?,
    radiusPx: Float? = null,
): android.graphics.drawable.RippleDrawable {
    val tint = android.content.res.ColorStateList.valueOf(Color.argb(
        46, Color.red(NovaTheme.accent), Color.green(NovaTheme.accent), Color.blue(NovaTheme.accent)))
    return android.graphics.drawable.RippleDrawable(
        tint, bg,
        if (radiusPx == null) null
        else GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radiusPx })
}

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {",
    "helper insertion")

# 2. roundButton (header add/menu + doc button)
src = swap(
    """        background = GradientDrawable().apply {
            setColor(surface)
            setStroke(dp(1), Color.parseColor("#28314A"))
            cornerRadius = dp(17).toFloat()
        }""",
    """        background = rippleOverlay(GradientDrawable().apply {
            setColor(surface)
            setStroke(dp(1), Color.parseColor("#28314A"))
            cornerRadius = dp(17).toFloat()
        })""",
    "roundButton")

# 3. mic button (icon-only, no background)
src = swap(
    "gravity = Gravity.CENTER\n            background = null",
    "gravity = Gravity.CENTER\n            background = rippleOverlay(null, dp(19).toFloat())",
    "mic button")

# 4. doc-clear (icon-only)
src = swap(
    "setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_close, textDim), null, null, null)\n            background = null",
    "setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_close, textDim), null, null, null)\n            background = rippleOverlay(null, dp(16).toFloat())",
    "doc clear")

# 5. send button (initial look)
src = swap(
    """            background = GradientDrawable().apply {
                setColor(accentDeep)
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener { send() }""",
    """            background = rippleOverlay(GradientDrawable().apply {
                setColor(accentDeep)
                cornerRadius = dp(20).toFloat()
            })
            setOnClickListener { send() }""",
    "send initial")

# 6-7. send button (updateSendLook, both branches)
src = swap(
    """sendBtn.background = GradientDrawable().apply {
                setColor(accentDeep); cornerRadius = dp(20).toFloat()
            }""",
    """sendBtn.background = rippleOverlay(GradientDrawable().apply {
                setColor(accentDeep); cornerRadius = dp(20).toFloat()
            })""",
    "send look active")
src = swap(
    """sendBtn.background = GradientDrawable().apply {
                setColor(NovaTheme.sendDim); cornerRadius = dp(20).toFloat()
            }""",
    """sendBtn.background = rippleOverlay(GradientDrawable().apply {
                setColor(NovaTheme.sendDim); cornerRadius = dp(20).toFloat()
            })""",
    "send look idle")

# 8. welcome suggestion cards
src = swap(
    """                    background = GradientDrawable().apply {
                        setColor(NovaTheme.pill)
                        cornerRadius = dp(22).toFloat()
                        setStroke(dp(1), NovaTheme.border)
                    }""",
    """                    background = rippleOverlay(GradientDrawable().apply {
                        setColor(NovaTheme.pill)
                        cornerRadius = dp(22).toFloat()
                        setStroke(dp(1), NovaTheme.border)
                    })""",
    "suggestion cards")

# 9. follow-up chips: ripple + 6dp spacing
src = swap(
    """                background = GradientDrawable().apply {
                    setColor(NovaTheme.pill); cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), NovaTheme.border)
                }
                setOnClickListener {
                    (line.parent as? View)?.visibility = View.GONE
                    input.setText(prompt)
                    send()
                }
            })""",
    """                background = rippleOverlay(GradientDrawable().apply {
                    setColor(NovaTheme.pill); cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), NovaTheme.border)
                })
                setOnClickListener {
                    (line.parent as? View)?.visibility = View.GONE
                    input.setText(prompt)
                    send()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })""",
    "follow-up chips")

# 10. math symbol row (icon-only, no background)
src = swap(
    "setTextColor(NovaTheme.text); background = null",
    "setTextColor(NovaTheme.text); background = rippleOverlay(null, dp(16).toFloat())",
    "math symbols")

# 11. copy/regen action icons in MessageAdapter
src = swap(
    "val copyBtn = TextView(ctx).apply {\n            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 18), dp(ctx, 6))",
    "val copyBtn = TextView(ctx).apply {\n            background = rippleOverlay(null, dp(ctx, 14).toFloat())\n            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 18), dp(ctx, 6))",
    "copy icon")
src = swap(
    "val regenBtn = TextView(ctx).apply {\n            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))",
    "val regenBtn = TextView(ctx).apply {\n            background = rippleOverlay(null, dp(ctx, 14).toFloat())\n            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))",
    "regen icon")

assert src.count("rippleOverlay(") >= 12, "expected rippleOverlay at every site, got %d" % src.count("rippleOverlay(")
open(MA, "w", encoding="utf-8").write(src)

# 12. version bump
bg = open(BG, encoding="utf-8").read()
assert bg.count("versionCode 56") == 1 and bg.count("versionName '7.6.4'") == 1, "version anchors drifted"
bg = bg.replace("versionCode 56", "versionCode 57").replace("versionName '7.6.4'", "versionName '7.6.5'")
open(BG, "w", encoding="utf-8").write(bg)

# 13. guard: ripple marker + version check
g = open(GUARD, encoding="utf-8").read()
assert g.count("v7.6.5") == 0, "guard already patched"
old_ma = '    ("NCIE polish: all generation through the kernel engine", "NovaEngineAdapter.stream(", 6),'
assert g.count(old_ma) == 1
g = g.replace(old_ma, old_ma +
    '\n    ("v7.6.5: pressed-state ripple feedback", "fun rippleOverlay(", 1),')
old_bg = '''check("build.gradle: jniLibs srcDirs intact (no typo corruption)",
      bg.count("jniLibs.srcDirs") == 1 and bg.count("srdDirs") == 0)'''
assert g.count(old_bg) == 1
g = g.replace(old_bg, old_bg + '''
check("v7.6.5: version bumped for the polish build",
      bg.count("versionName '7.6.5'") == 1 and bg.count("versionCode 57") == 1)''')
open(GUARD, "w", encoding="utf-8").write(g)

r = subprocess.run([sys.executable, GUARD, "."], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
