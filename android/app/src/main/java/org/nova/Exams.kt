package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exam countdown: exam names + dates stored locally, shown in a screen
 * and injected into the model's prompts so NOVA always knows what's coming.
 */
object Exams {

    data class Exam(val name: String, val dateMs: Long)

    private fun file(ctx: Context): File = File(ctx.filesDir, "exams.json")

    fun load(ctx: Context): MutableList<Exam> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val out = mutableListOf<Exam>()
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Exam(o.getString("name"), o.optLong("date", 0L)))
            }
        } catch (e: Exception) { }
        return out
    }

    fun save(ctx: Context, exams: List<Exam>) {
        try {
            val arr = JSONArray()
            for (e in exams) arr.put(JSONObject().put("name", e.name).put("date", e.dateMs))
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) { }
    }

    fun add(ctx: Context, name: String, dateMs: Long) {
        val all = load(ctx)
        all.add(Exam(name, dateMs))
        save(ctx, all)
    }

    fun daysLeft(dateMs: Long): Int =
        ((dateMs - System.currentTimeMillis()) / 86_400_000L).toInt() + 1

    /** "Physics in 12 days (14 May)" - nearest first, or null. */
    fun promptLine(ctx: Context): String? {
        val upcoming = load(ctx)
            .filter { it.dateMs > System.currentTimeMillis() }
            .sortedBy { it.dateMs }
        if (upcoming.isEmpty()) return null
        return upcoming.joinToString("; ") { e ->
            "${e.name} in ${daysLeft(e.dateMs)} days " +
                "(${SimpleDateFormat("d MMM", Locale.US).format(Date(e.dateMs))})"
        }
    }
}
