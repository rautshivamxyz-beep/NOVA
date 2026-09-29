package org.nova.ncie.android

import android.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nova.MainActivity
import org.nova.WikiCore
import org.nova.ncie.verify.Coverage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v8.4.0 (stage 1): learn online - ASK FIRST. When a study question has
 * nothing local behind it (no notes, no offline wiki hit), NOVA offers
 * to fetch the Wikipedia article. The privacy line is absolute:
 *
 *  - only the question's KEYWORDS are sent (the kernel analyzer's terms,
 *    never the raw question, never the name, never notes or memory)
 *  - the user sees exactly what will be searched before it happens
 *  - the fetch is checked by the kernel's Coverage gate before it is
 *    trusted; a mismatched article never enters the store
 *  - the saved article is offline forever after - one fetch, ever
 *
 * The turn then re-runs normally: with the new article in the store,
 * the regular wiki path finds it and answers grounded in it.
 */
object OnlineFetch {

    /** The ask-first dialog. Never called unless the setting is on and
     *  the turn already came up empty locally. */
    fun offer(act: MainActivity, text: String) {
        val kws = NcieKnowledge.keyTerms(text).take(6)
        if (kws.isEmpty()) { act.ncieSend(text, offered = true); return }
        val q = kws.joinToString(" ")
        AlertDialog.Builder(act)
            .setTitle("Look it up online?")
            .setMessage("Nothing in your notes or offline Wikipedia covers " +
                "this.\n\nNOVA will search Wikipedia for:\n\"$q\"\n\n" +
                "Nothing else leaves your phone. The saved article stays " +
                "offline forever.")
            .setPositiveButton("Look it up") { _, _ -> fetch(act, text, q) }
            .setNegativeButton("Answer anyway") { _, _ -> act.ncieSend(text, offered = true) }
            .setOnCancelListener { act.ncieSend(text, offered = true) }
            .show()
    }

    private fun fetch(act: MainActivity, text: String, q: String) {
        act.scope.launch(Dispatchers.IO) {
            val res = try { fetchWiki(q) } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                if (res == null) {
                    act.toast("Couldn't reach Wikipedia - answering without it")
                    act.ncieSend(text, offered = true)
                } else if (Coverage.ratio(text, res.second) < 0.3) {
                    // the kernel's fetched-knowledge gate: a mismatched
                    // article never enters the store
                    act.toast("The article didn't match the question")
                    act.ncieSend(text, offered = true)
                } else {
                    WikiCore.appendArticle(act, res.first, res.second)
                    act.toast("Saved \"${res.first}\" - now offline forever")
                    act.ncieSend(text, offered = true)
                }
            }
        }
    }

    /** (title, intro extract) from Wikipedia, or null. Two requests:
     *  the search API for the best title, then the extract API for its
     *  intro paragraphs. */
    private fun fetchWiki(q: String): Pair<String, String>? {
        val search = http("https://en.wikipedia.org/w/api.php?action=query&format=json" +
            "&list=search&srlimit=1&srsearch=" + URLEncoder.encode(q, "UTF-8"))
        val title = try {
            JSONObject(search).getJSONObject("query").getJSONArray("search")
                .getJSONObject(0).getString("title")
        } catch (e: Exception) { return null }
        val page = http("https://en.wikipedia.org/w/api.php?action=query&format=json" +
            "&prop=extracts&explaintext=1&exintro=1&redirects=1&titles=" +
            URLEncoder.encode(title, "UTF-8"))
        val extract = try {
            val pages = JSONObject(page).getJSONObject("query").getJSONObject("pages")
            val k = pages.keys().next()
            pages.getJSONObject(k).getString("extract")
        } catch (e: Exception) { return null }
        if (extract.length < 80) return null
        return title to extract
    }

    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "NOVA-local-assistant/1.0 (offline study)")
        try {
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
