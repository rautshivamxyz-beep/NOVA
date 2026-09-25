#!/usr/bin/env python3
"""NOVA v6.1.0 app patch: year-loop guard + LaTeX math rendering + version bump.

Files changed:
  android/app/build.gradle                      (deps + version 38 / 6.1.0)
  android/app/src/main/java/org/nova/MainActivity.kt

Run from the repo root.
"""
import sys

def rep(path, old, new):
    src = open(path, encoding="utf-8").read()
    n = src.count(old)
    assert n == 1, "anchor not unique (%d) in %s:\n%s" % (n, path, old[:150])
    open(path, "w", encoding="utf-8").write(src.replace(old, new))
    print("patched: " + path)

def main():
    MA = "android/app/src/main/java/org/nova/MainActivity.kt"
    BG = "android/app/build.gradle"

    # ---- 1. import the LaTeX plugin ----
    rep(MA,
        "import io.noties.markwon.syntax.SyntaxHighlightPlugin",
        "import io.noties.markwon.syntax.SyntaxHighlightPlugin\n" +
        "import io.noties.markwon.latex.LatexPlugin")

    # ---- 2. register it in the Markwon builder ----
    rep(MA,
        "                .usePlugin(SyntaxHighlightPlugin.create(prism4j, Prism4jThemeDefault.create()))\n" +
        "                .build()",
        "                .usePlugin(SyntaxHighlightPlugin.create(prism4j, Prism4jThemeDefault.create()))\n" +
        "                .usePlugin(LatexPlugin.create(ctx))\n" +
        "                .build()")

    # ---- 3. year-loop fix in isDegenerateReply ----
    rep(MA,
        "            if (line.length >= 15) {\n" +
        "                val c = (counts[line] ?: 0) + 1\n" +
        "                counts[line] = c\n" +
        "                if (c >= 3) return true\n" +
        "            }",
        "            if (line.length >= 15) {\n" +
        "                // v6.1.0: numbers normalized so 'published in 1948 /\n" +
        "                // 1951 / 1952 ...' counts as one repeated line\n" +
        "                val key = Regex(\"\\\\d+\").replace(line, \"#\")\n" +
        "                val c = (counts[key] ?: 0) + 1\n" +
        "                counts[key] = c\n" +
        "                if (c >= 3) return true\n" +
        "            }")

    # ---- 4. mathPrompt helper + apply it to the chat send ----
    rep(MA,
        "    private fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true) {",
        "    /** v6.1.0: ask for LaTeX so formulas render like a textbook. */\n" +
        "    private fun mathPrompt(p: String): String =\n" +
        "        p + \"\\n(If your answer includes mathematical formulas, write each formula in LaTeX, wrapped in dollar signs.)\"\n" +
        "\n" +
        "    private fun startGeneration(prompt: String, userText: String?, newBubble: Boolean = true) {")

    rep(MA,
        "                NovaEngine.send(effectivePrompt(prompt), settings.predictLength)",
        "                NovaEngine.send(mathPrompt(effectivePrompt(prompt)), settings.predictLength)")

    # ---- 5. build.gradle: math rendering deps + version 6.1.0 ----
    rep(BG,
        "    implementation 'io.noties:prism4j:2.0.0'",
        "    implementation 'io.noties:prism4j:2.0.0'\n" +
        "    // v6.1.0: textbook math rendering\n" +
        "    implementation 'io.noties.markwon:latex:4.6.2'\n" +
        "    implementation 'ru.noties:jlatexmath-android:0.2.0'")

    rep(BG,
        "        versionCode 37\n" +
        "        versionName '6.0.0'",
        "        versionCode 38\n" +
        "        versionName '6.1.0'")

    print("OK - v6.1.0 app patch applied")

if __name__ == "__main__":
    main()
