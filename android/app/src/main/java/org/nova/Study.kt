package org.nova

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Spaced-repetition flashcards ("Study").
 *
 * Cards are created by long-pressing a NOVA answer and choosing
 * "Make study cards". Each card is graded on review: correct answers
 * push the next review further out (2, 4, 8, 16, 30 days), a miss
 * resets the card to tomorrow.
 */
object Study {

    data class Card(val q: String, val a: String, var interval: Int, var due: Long)

    private fun file(ctx: Context): File = File(ctx.filesDir, "study.json")

    fun load(ctx: Context): MutableList<Card> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val out = mutableListOf<Card>()
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Card(o.getString("q"), o.getString("a"),
                    o.optInt("iv", 1), o.optLong("due", 0L)))
            }
        } catch (e: Exception) { }
        return out
    }

    fun save(ctx: Context, cards: List<Card>) {
        try {
            val arr = JSONArray()
            for (c in cards) {
                arr.put(JSONObject().put("q", c.q).put("a", c.a)
                    .put("iv", c.interval).put("due", c.due))
            }
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) { }
    }

    fun totalCards(ctx: Context): Int = load(ctx).size

    /** Deletes the whole deck. */
    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    fun dueCount(ctx: Context): Int =
        load(ctx).count { it.due <= System.currentTimeMillis() }

    /**
     * Parses "Q: ... / A: ..." pairs from a model reply and adds them as
     * cards. Returns how many were added.
     */
    fun parseAndAdd(ctx: Context, text: String): Int {
        val cards = load(ctx)
        val before = cards.size
        var q: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("Q:") -> q = line.drop(2).trim()
                line.startsWith("A:") && q != null -> {
                    val a = line.drop(2).trim()
                    if (q.length > 3 && a.isNotEmpty()) cards.add(Card(q, a, 1, 0L))
                    q = null
                }
            }
        }
        if (cards.size > before) save(ctx, cards)
        return cards.size - before
    }

    /** Starts the one-card-at-a-time review session (or explains why not). */
    fun review(ctx: Context) {
        val all = load(ctx)
        if (all.isEmpty()) {
            Toast.makeText(ctx, "No study cards yet — long-press an answer " +
                "and pick 'Make study cards'", Toast.LENGTH_LONG).show()
            return
        }
        val due = all.filter { it.due <= System.currentTimeMillis() }
        if (due.isEmpty()) {
            Toast.makeText(ctx, "All ${all.size} cards reviewed — come back later!",
                Toast.LENGTH_SHORT).show()
            return
        }
        showCard(ctx, all, due, 0)
    }

    private fun showCard(ctx: Context, all: MutableList<Card>, due: List<Card>, i: Int) {
        if (i >= due.size) {
            save(ctx, all)
            Toast.makeText(ctx, "Review done — ${due.size} cards graded",
                Toast.LENGTH_SHORT).show()
            return
        }
        val c = due[i]
        AlertDialog.Builder(ctx)
            .setTitle("Study  ${i + 1}/${due.size}")
            .setMessage(c.q)
            .setPositiveButton("Show answer") { _, _ ->
                AlertDialog.Builder(ctx)
                    .setTitle("Answer")
                    .setMessage(c.a)
                    .setPositiveButton("Got it") { _, _ ->
                        grade(ctx, all, c, true)
                        showCard(ctx, all, due, i + 1)
                    }
                    .setNegativeButton("Missed") { _, _ ->
                        grade(ctx, all, c, false)
                        showCard(ctx, all, due, i + 1)
                    }
                    .show()
            }
            .setNeutralButton("Skip") { _, _ -> showCard(ctx, all, due, i + 1) }
            .show()
    }

    /** Spaced repetition: correct doubles the interval (max 30 days),
     *  a miss resets to tomorrow. */
    private fun grade(ctx: Context, all: MutableList<Card>, c: Card, correct: Boolean) {
        c.interval = if (correct) (if (c.interval >= 30) 30 else c.interval * 2) else 1
        c.due = System.currentTimeMillis() + c.interval * 86_400_000L
        save(ctx, all)
    }
}
