#!/usr/bin/env python3
"""v9.33.112 "A list that failed to load is not an empty list".

The tool library (recipes.json) is a READ-MODIFY-WRITE store: save() reads
every saved recipe, adds or replaces one, and writes the whole list back.

Two ways that turned a display problem into lost data:

  * stepOf read each argument with JSONObject.getString. A model-authored
    recipe writes numbers the way JSON numbers are written - "take" with
    {"n": 5} - and getString throws on that. The throw escaped readOne and
    escaped all(), whose catch answers emptyList(), so ONE numeric argument
    made the entire library look empty: "run tool x" said there was no such
    tool, though the user had built several.
  * save() then did its read-modify-write over that empty list. The library
    was not merely hidden, it was REPLACED by the one recipe being saved -
    every other tool the user had built was gone, with nothing said.

The fix separates the two meanings all() had flattened into one: allOrNull()
returns null when the file exists but could not be read, all() still answers
an empty list for the display-only readers, and save() refuses to write back
a load that failed - a failed read can no longer erase a good file. Arguments
are taken as their text, so a numeric argument is a valid argument rather
than a fatal one. save() answers, and its one caller says what actually
happened.

Self-verifying: every anchor must match exactly once, and the finished tree is
checked for the new markers before the script exits 0. Run from the repo root
(or pass it): python3 tmp/patch_v933112.py .
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = os.path.join(ROOT, "android/app/src/main/java/org/nova/")

fails = []


def check(name, cond):
    print(("PASS  " if cond else "FAIL  ") + name)
    if not cond:
        fails.append(name)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def patch(path, pairs):
    text = read(path)
    for old, new in pairs:
        n = text.count(old)
        if n != 1:
            print("FATAL: anchor matched %d times in %s:\n%s" % (n, path, old))
            sys.exit(1)
        text = text.replace(old, new)
    write(path, text)
    return text


RECIPES = BASE + "ncie/android/NcieRecipes.kt"
CHATEXT = BASE + "ncie/android/NcieChatExt.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")

recipes = patch(RECIPES, [
    # ---- all() vs allOrNull(): "could not read" is not "nothing there"
    (
        r'''    fun all(ctx: Context): List<Recipe> = try {
        val arr = JSONArray(file(ctx).readText())
        // v9.33.101: a row that is not an object is skipped, not fatal for the list
        (0 until arr.length()).mapNotNull { i -> readOne(arr.optJSONObject(i) ?: return@mapNotNull null) }
    } catch (e: Exception) { emptyList() }
''',
        r'''    fun all(ctx: Context): List<Recipe> = allOrNull(ctx) ?: emptyList()

    /**
     * v9.33.112 "A list that failed to load is not an empty list": the stored
     * recipes, or null when the file exists but could not be read.
     *
     * [all] flattens both cases into an empty list, which is right for the
     * readers that only display. It is wrong for [save], which rewrites the
     * list from what it just read - see there.
     */
    private fun allOrNull(ctx: Context): List<Recipe>? {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            // v9.33.101: a row that is not an object is skipped, not fatal for the list
            (0 until arr.length()).mapNotNull { i -> readOne(arr.optJSONObject(i) ?: return@mapNotNull null) }
        } catch (e: Exception) {
            NovaLog.quiet("NcieRecipes", e)
            null
        }
    }
'''),
    # ---- a numeric argument is an argument, not a fatal error
    (
        r'''        so.optJSONObject("args")?.let { a -> for (k in a.keys()) args[k] = a.getString(k) }
''',
        r'''        // v9.33.112: getString threw on an argument that was not a string -
        // the model writes numbers the way JSON writes them, {"n": 5} - and
        // that throw escaped all() as "the list is empty". Every primitive
        // reads its arguments as text anyway, so take the text.
        val a = so.optJSONObject("args")
        if (a != null) for (k in a.keys()) {
            val v = a.opt(k)
            if (v != null && v != JSONObject.NULL) args[k] = v.toString()
        }
'''),
    # ---- the save never writes back a load that failed
    (
        r'''    fun save(ctx: Context, r: Recipe) {
        try {
            val arr = JSONArray()
            for (x in all(ctx).filter { it.name != r.name } + r) {
''',
        r'''    /** Saves [r], replacing any recipe of the same name. False when the
     *  save failed, or when the stored list could not be read: a
     *  read-modify-write must never write back a load that failed. */
    fun save(ctx: Context, r: Recipe): Boolean {
        // v9.33.112: this is a read-modify-write. When the read failed, all()
        // handed back an empty list and the write that followed erased every
        // other saved recipe - the model-authored tool library, silently, in
        // one turn. Refuse to overwrite a list NOVA could not read.
        val existing = allOrNull(ctx) ?: run {
            NovaLog.quiet("NcieRecipes", java.io.IOException("unreadable recipe list - not overwriting it"))
            return false
        }
        return try {
            val arr = JSONArray()
            for (x in existing.filter { it.name != r.name } + r) {
'''),
    (
        r'''            NcieFiles.write(file(ctx), arr.toString())
        } catch (e: Exception) { NovaLog.quiet("NcieRecipes", e) }
    }
''',
        r'''            NcieFiles.write(file(ctx), arr.toString())
        } catch (e: Exception) {
            NovaLog.quiet("NcieRecipes", e)
            false
        }
    }
'''),
])

# ------------------------------------------------------- the one caller
chatext = patch(CHATEXT, [
    (
        r'''                                .setPositiveButton("Save") { _, _ ->
                                    self.scope.launch(Dispatchers.IO) { NcieRecipes.save(self, recipe) }
                                    reply("Saved. Say \"run tool ${recipe.name}\" any time.")
                                }
''',
        r'''                                .setPositiveButton("Save") { _, _ ->
                                    // v9.33.112: save() answers now, so the
                                    // reply is written after the answer is
                                    // known instead of promising a save that
                                    // may never have happened.
                                    self.scope.launch(Dispatchers.IO) {
                                        val ok = NcieRecipes.save(self, recipe)
                                        withContext(Dispatchers.Main) {
                                            reply(if (ok) "Saved. Say \"run tool ${recipe.name}\" any time."
                                                else "Couldn't save \"" + recipe.name + "\" - NOVA could not " +
                                                    "write the tool library. It still runs this time.")
                                        }
                                    }
                                }
'''),
])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 279") != 1 or gradle.count("versionName '9.33.111'") != 1:
    print("FATAL: build.gradle is not at 9.33.111 (279)")
    sys.exit(1)
gradle = gradle.replace("versionCode 279", "versionCode 280")
gradle = gradle.replace("versionName '9.33.111'", "versionName '9.33.112'")
write(GRADLE, gradle)

# ------------------------------------------------- teach the verifier
OLD_TAIL = '''check("v9.33.111: version is at least 9.33.111 (279)",
      version_at_least("9.33.111", 279))
'''
NEW_TAIL = '''# v9.33.112 "A list that failed to load is not an empty list": the recipe
# store is a read-modify-write. A model-authored argument written as a JSON
# number threw inside getString, the throw escaped all() as "no recipes", and
# the save that followed rewrote the library from that empty list - every tool
# the user had built, gone in one turn. getString is gone, "could not read" is
# now distinct from "nothing there", and a save refuses to write back a load
# that failed.
check("v9.33.112: a recipe argument that is not a string is not fatal",
      _rec.count("args[k] = v.toString()") == 1 and
      _rec.count("args[k] = a.getString(k)") == 0)
check("v9.33.112: a load that failed is not an empty list",
      _rec.count("private fun allOrNull(ctx: Context): List<Recipe>?") == 1 and
      _rec.count("fun all(ctx: Context): List<Recipe> = allOrNull(ctx) ?: emptyList()") == 1)
check("v9.33.112: the save never writes back a load that failed",
      _rec.count("val existing = allOrNull(ctx) ?: run {") == 1 and
      _rec.count("fun save(ctx: Context, r: Recipe): Boolean") == 1)
check("v9.33.112: the caller reports the save's answer",
      nc.count("val ok = NcieRecipes.save(self, recipe)") == 1 and
      nc.count("NcieRecipes.save(self, recipe) }") == 0)
check("v9.33.112: version is at least 9.33.112 (280)",
      version_at_least("9.33.112", 280))
'''
ver = read(VERIFY)
if ver.count(OLD_TAIL) != 1:
    print("FATAL: the verifier tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_TAIL, NEW_TAIL))

# ---------------------------------------------------------------- verify
print("")
check("recipes: the throw on a non-string argument is gone",
      'a.getString(k)' not in recipes and "args[k] = v.toString()" in recipes)
check("recipes: all() and allOrNull() are separate meanings",
      "private fun allOrNull(ctx: Context): List<Recipe>?" in recipes and
      "fun all(ctx: Context): List<Recipe> = allOrNull(ctx) ?: emptyList()" in recipes)
check("recipes: the save refuses an unreadable list",
      "val existing = allOrNull(ctx) ?: run {" in recipes and
      "fun save(ctx: Context, r: Recipe): Boolean" in recipes)
check("recipes: the save answers", "NcieFiles.write(file(ctx), arr.toString())" in recipes and
      "            false\n        }\n    }" in recipes)
check("chat: the caller reports the answer",
      "val ok = NcieRecipes.save(self, recipe)" in chatext and
      "NcieRecipes.save(self, recipe) }" not in chatext)
check("version is 9.33.112 (280)",
      "versionName '9.33.112'" in gradle and "versionCode 280" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.112", 280)' in read(VERIFY))
for name, text in [("NcieRecipes.kt", recipes), ("NcieChatExt.kt", chatext)]:
    check("%s: braces balance" % name, text.count("{") == text.count("}"))
    check("%s: parens balance" % name, text.count("(") == text.count(")"))
    bad = []
    for m in re.finditer(r'"(?:[^"\\]|\\.)*"', text):
        for e in re.finditer(r"\\(.)", m.group(0)):
            if e.group(1) not in "tbnr\"'\\$su":
                bad.append(e.group(1))
    check("%s: no invalid string escapes" % name, not bad)

print("")
if fails:
    print("PATCH FAILED: %d problem(s)" % len(fails))
    for x in fails:
        print(" - " + x)
    sys.exit(1)
print("PATCH OK: v9.33.112 applied and self-verified")
