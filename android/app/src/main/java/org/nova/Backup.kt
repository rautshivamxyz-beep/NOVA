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
        // v7.6: exams and reminders are part of the backup - a reinstall
        // used to silently lose both
        val exams = JSONArray()
        for (e in Exams.load(ctx)) exams.put(JSONObject().put("name", e.name).put("date", e.dateMs))
        val reminders = JSONArray()
        for ((at, t, rep) in ReminderStore.load(ctx))
            reminders.put(JSONObject().put("at", at).put("t", t).put("rep", rep))
        val kFile = File(ctx.filesDir, "knowledge.json")
        val kJson = if (kFile.exists()) try { kFile.readText() } catch (e: Exception) { "" } else ""
        JSONObject()
            .put("nova_backup", 1)
            .put("memory", s.memory)
            .put("system_prompt", s.systemPrompt)
            .put("predict_length", s.predictLength)
            .put("knowledge", kJson)
            .put("wiki", s.wikiEnabled)
            .put("knowledge_enabled", s.knowledgeEnabled)
            .put("study", study)
            .put("exams", exams)
            .put("reminders", reminders)
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
        // v7.4: "knowledge" used to hold the boolean toggle (old backups),
        // which silently overwrote the documents - fallback only
        s.knowledgeEnabled = o.optBoolean("knowledge_enabled", o.optBoolean("knowledge", true))
        // v5.4.7: restore the whole knowledge base, not just the toggle
        val kJson = o.optString("knowledge")
        if (kJson.isNotEmpty() && kJson.trimStart().startsWith("{")) Knowledge.restoreAll(ctx, kJson)
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
        // v7.6: restore exams and reminders too
        val exams = o.optJSONArray("exams")
        if (exams != null) {
            val list = mutableListOf<Exams.Exam>()
            for (i in 0 until exams.length()) {
                val eo = exams.getJSONObject(i)
                list.add(Exams.Exam(eo.getString("name"), eo.optLong("date", 0L)))
            }
            if (list.isNotEmpty()) Exams.save(ctx, list)
        }
        val rem = o.optJSONArray("reminders")
        if (rem != null) {
            val now = System.currentTimeMillis()
            for (i in 0 until rem.length()) {
                val ro = rem.getJSONObject(i)
                val at = ro.optLong("at", 0L)
                val rep = ro.optLong("rep", 0L)
                val t = ro.optString("t", "")
                if (t.isNotEmpty() && (at > now || rep > 0L)) {
                    ReminderStore.add(ctx, at, t, rep)
                    Reminder.schedule(ctx, at, t, rep)
                }
            }
        }
        var n = 0
        val chats = o.optJSONArray("chats") ?: JSONArray()
        for (i in 0 until chats.length()) {
            val c = chats.getJSONObject(i)
            val id = c.optString("id")
            // v7.6: a crafted backup could write outside the chats folder
            // via "../" in the id - plain names only
            if (id.isNotBlank() && Regex("[A-Za-z0-9_-]+").matches(id)) {
                File(File(ctx.filesDir, "chats").apply { mkdirs() }, "$id.json")
                    .writeText(c.toString())
                n++
            }
        }
        n
    } catch (e: Exception) { -1 }
}
