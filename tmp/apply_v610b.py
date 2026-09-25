#!/usr/bin/env python3
"""NOVA v6.1.0 build fix: correct Markwon LaTeX artifact + class + init.

The first attempt used io.noties.markwon:latex (does not exist).
Correct coordinates verified on Maven Central:
  io.noties.markwon:ext-latex:4.6.2
  class io.noties.markwon.ext.latex.JLatexMathPlugin.create(float)
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

    # 1. correct artifact name
    rep(BG,
        "    implementation 'io.noties.markwon:latex:4.6.2'",
        "    implementation 'io.noties.markwon:ext-latex:4.6.2'")

    # 2. correct import
    rep(MA,
        "import io.noties.markwon.latex.LatexPlugin",
        "import io.noties.markwon.ext.latex.JLatexMathPlugin")

    # 3. explicit init + correct plugin call
    rep(MA,
        "            val prism4j = Prism4j(NovaGrammarLocator)",
        "            ru.noties.jlatexmath.JLatexMathAndroid.init(ctx)\n" +
        "            val prism4j = Prism4j(NovaGrammarLocator)")

    rep(MA,
        "                .usePlugin(LatexPlugin.create(ctx))",
        "                .usePlugin(JLatexMathPlugin.create(15.5f))")

    print("OK - v6.1.0 latex fix applied")

if __name__ == "__main__":
    main()
