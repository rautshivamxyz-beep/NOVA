package org.nova.ncie.android

import org.nova.NovaLog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v9.29.3 "Tool recipes": the recipe store and the authoring prompt.
 *
 * A recipe is data (a name + a list of steps). NOVA authors one from a
 * request, it is validated against the primitive catalogue, previewed to
 * the user once, then saved and reused. Nothing here executes code - only
 * the fixed, offline primitives in [NcieTools].
 */
object NcieRecipes {

    class Recipe(val name: String, val steps: List<NcieTools.Step>)

    /** The primitives a recipe may use - anything else is dropped. */
    val KNOWN = setOf(
        "list", "read", "filter", "search", "take",
        "count", "summarise", "match", "answer", "write"
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "recipes.json")

    fun all(ctx: Context): List<Recipe> = allOrNull(ctx) ?: emptyList()

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

    private fun readOne(o: JSONObject): Recipe? {
        val st = o.optJSONArray("steps") ?: return null
        val steps = (0 until st.length()).mapNotNull { j -> stepOf(st.optJSONObject(j) ?: return@mapNotNull null) }
        return if (steps.isEmpty()) null else Recipe(o.optString("name", "recipe"), steps)
    }

    private fun stepOf(so: JSONObject): NcieTools.Step? {
        val op = so.optString("op").lowercase()
        if (op !in KNOWN) return null
        val args = HashMap<String, String>()
        // v9.33.112: getString threw on an argument that was not a string -
        // the model writes numbers the way JSON writes them, {"n": 5} - and
        // that throw escaped all() as "the list is empty". Every primitive
        // reads its arguments as text anyway, so take the text.
        val a = so.optJSONObject("args")
        if (a != null) for (k in a.keys()) {
            val v = a.opt(k)
            if (v != null && v != JSONObject.NULL) args[k] = v.toString()
        }
        return NcieTools.Step(op, args)
    }

    /** Saves [r], replacing any recipe of the same name. False when the
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
                val o = JSONObject().put("name", x.name)
                val st = JSONArray()
                for (s in x.steps) {
                    val so = JSONObject().put("op", s.op)
                    if (s.args.isNotEmpty()) {
                        val a = JSONObject()
                        for ((k, v) in s.args) a.put(k, v)
                        so.put("args", a)
                    }
                    st.put(so)
                }
                o.put("steps", st)
                arr.put(o)
            }
            NcieFiles.write(file(ctx), arr.toString())
        } catch (e: Exception) {
            NovaLog.quiet("NcieRecipes", e)
            false
        }
    }

    /** Parses a model-authored JSON recipe, keeping only known primitives. */
    fun parse(json: String): Recipe? = try {
        val s = json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1)
        readOne(JSONObject(s))
    } catch (e: Exception) { null }

    /** A short human preview of a recipe, for the one-time confirmation. */
    fun preview(r: Recipe): String =
        r.name + "\n" + r.steps.joinToString("\n") { s ->
            val a = s.args.entries.joinToString(", ") { "${it.key}=${it.value}" }
            "  - ${s.op}" + (if (a.isEmpty()) "" else "  ($a)")
        }

    /** The prompt that asks the local model to author a recipe. */
    fun authorPrompt(request: String): String =
        "You are NOVA's tool builder. Build a RECIPE - a JSON list of steps - " +
            "that does what the user asked, using ONLY these primitives:\n\n" +
            NcieTools.CATALOGUE +
            "\n\nRules: output ONLY JSON, no prose, no code fences. " +
            "Shape: {\"name\":\"short name\",\"steps\":[{\"op\":\"list\",\"args\":{\"ext\":\".pdf\"}}, {\"op\":\"read\"}]}. " +
            "At most 10 steps. If it cannot be done with these primitives, output {\"steps\":[]}.\n\n" +
            "User request: " + request
}
