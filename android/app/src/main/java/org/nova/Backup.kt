package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup & restore: every chat, the memory, the personality prompt and the
 * study deck in one JSON file the user can keep anywhere.
 */
object Backup {

    /** Builds the full backup as a JSON string. */
    fun export(ctx: Context): String = try {
        val s = Settings(ctx)
        val chats = JSONArray()
        val dir = File(ctx.filesDir, "chats")
        dir.listFiles { f: File -> f.name.endsWith(".json") }?.forEach {
            try { chats.put(JSONObject(it.readText())) } catch (e: Exception) { }
        }
        val study = JSONArray()
        for (c in Study.load(ctx)) {
            study.put(JSONObject().put("q", c.q).put("a", c.a)
                .put("iv", c.interval).put("due", c.due))
        }
        JSONObject()
            .put("nova_backup", 1)
            .put("memory", s.memory)
            .put("system_prompt", s.systemPrompt)
            .put("predict_length", s.predictLength)
            .put("wiki", s.wikiEnabled)
            .put("knowledge", s.knowledgeEnabled)
            .put("study", study)
            .put("chats", chats)
            .toString()
    } catch (e: Exception) { "" }

    /** True if s looks like one of our backups. */
    fun looksLikeBackup(s: String): Boolean =
        s.trimStart().startsWith("{") && "\"nova_backup\"" in s

    /** Restores from backup text. Returns chats restored, or -1 on error. */
    fun restore(ctx: Context, text: String): Int = try {
        val o = JSONObject(text)
        if (o.optInt("nova_backup") != 1) return -1
        val s = Settings(ctx)
        val mem = o.optString("memory")
        if (mem.isNotEmpty()) s.memory = mem
        val sp = o.optString("system_prompt")
        if (sp.isNotEmpty()) s.systemPrompt = sp
        if (o.has("predict_length")) s.predictLength = o.optInt("predict_length", 512)
        s.wikiEnabled = o.optBoolean("wiki", true)
        s.knowledgeEnabled = o.optBoolean("knowledge", false)
        val study = o.optJSONArray("study")
        if (study != null && study.length() > 0) {
            val cards = mutableListOf<Study.Card>()
            for (i in 0 until study.length()) {
                val sc = study.getJSONObject(i)
                cards.add(Study.Card(sc.getString("q"), sc.getString("a"),
                    sc.optInt("iv", 1), sc.optLong("due", 0L)))
            }
            Study.save(ctx, cards)
        }
        var n = 0
        val chats = o.optJSONArray("chats") ?: JSONArray()
        for (i in 0 until chats.length()) {
            val c = chats.getJSONObject(i)
            val id = c.optString("id")
            if (id.isNotBlank()) {
                File(File(ctx.filesDir, "chats").apply { mkdirs() }, "$id.json")
                    .writeText(c.toString())
                n++
            }
        }
        n
    } catch (e: Exception) { -1 }
}
