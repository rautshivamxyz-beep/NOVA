package org.nova.ncie.android

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

    fun all(ctx: Context): List<Recipe> = try {
        val arr = JSONArray(file(ctx).readText())
        (0 until arr.length()).mapNotNull { i -> readOne(arr.getJSONObject(i)) }
    } catch (e: Exception) { emptyList() }

    private fun readOne(o: JSONObject): Recipe? {
        val st = o.optJSONArray("steps") ?: return null
        val steps = (0 until st.length()).mapNotNull { j -> stepOf(st.optJSONObject(j) ?: return@mapNotNull null) }
        return if (steps.isEmpty()) null else Recipe(o.optString("name", "recipe"), steps)
    }

    private fun stepOf(so: JSONObject): NcieTools.Step? {
        val op = so.optString("op").lowercase()
        if (op !in KNOWN) return null
        val args = HashMap<String, String>()
        so.optJSONObject("args")?.let { a -> for (k in a.keys()) args[k] = a.getString(k) }
        return NcieTools.Step(op, args)
    }

    fun save(ctx: Context, r: Recipe) {
        try {
            val arr = JSONArray()
            for (x in all(ctx).filter { it.name != r.name } + r) {
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
            val f = file(ctx)
            val tmp = File(ctx.filesDir, "recipes.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { }
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
