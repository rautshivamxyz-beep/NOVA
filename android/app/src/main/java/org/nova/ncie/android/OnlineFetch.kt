package org.nova.ncie.android

import android.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.nova.MainActivity
import org.nova.WikiCore
import org.nova.ncie.knowledge.HtmlText
import org.nova.ncie.verify.Coverage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v8.5.0 (stage 1): LIVE learning - ask first, always. When a study
 * question has nothing local behind it, NOVA offers one fetch:
 *
 *  1. Wikipedia (curated, clean) - the API intro extract
 *  2. if nothing there passes the kernel's Coverage gate: the OPEN WEB -
 *     DuckDuckGo's no-JS endpoint for links, then the first page that
 *     passes the gate, fetched and stripped by the kernel's HtmlText -
 *     text only, zero scripts executed, code blocks kept VERBATIM
 *
 * The privacy line is absolute: only the question's KEYWORDS are sent
 * (never the raw question, never the name, never notes or memory), the
 * user sees exactly what will be searched before it happens, and a
 * mismatched page never enters the store. Everything saved is offline
 * forever and logged to fetch_log.txt - the Fetches screen shows the
 * log and can delete any article.
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
                "this.\n\nNOVA will search Wikipedia and, if that is not " +
                "enough, the open web for:\n\"$q\"\n\n" +
                "Nothing else leaves your phone. Whatever it finds stays " +
                "offline forever.")
            .setPositiveButton("Look it up") { _, _ -> fetch(act, text, q) }
            .setNegativeButton("Answer anyway") { _, _ -> act.ncieSend(text, offered = true) }
            .setOnCancelListener { act.ncieSend(text, offered = true) }
            .show()
    }

    private fun fetch(act: MainActivity, text: String, q: String) {
        act.scope.launch(Dispatchers.IO) {
            // 1. Wikipedia first - curated, clean, no page parsing needed
            val wiki = try { fetchWiki(q) } catch (e: Exception){ null }
            var saved: String? = null
            if (wiki != null && Coverage.ratio(text, wiki.second) >= 0.3) {
                if (WikiCore.appendArticle(act, wiki.first, wiki.second)) {
                    logFetch(act, wiki.first, "wikipedia")
                    saved = wiki.first
                }
            }
            // 2. the open web - only when Wikipedia had nothing usable
            if (saved == null) {
                val page = try { fetchWeb(q) } catch (e: Exception) { null }
               if (page != null) {
                    // code blocks ride along verbatim, so fetched examples
                    // land exactly as written
                    val body = page.text + (if (page.code.isNotEmpty())
                          "\n\nCode from the page:\n" + page.code.joinToString("\n---\n") { it }
                        else "")
                    if (Coverage.ratio(text, body) >= 0.3 &&
                        WikiCore.appendArticle(act, page.title, body)) {
                        logFetch(act, page.title, page.url)
                        saved = page.title
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (saved != null) {
                    act.toast("Saved \"$saved\" - now offline forever")
                } else {
                    act.toast("Couldn't find anything good - answering without it")
                }
                act.ncieSend(text, offered = true)
            }
        }
    }

    /** (title, intro extract) from Wikipedia, or null. Two requests:
     *  the search API for the best title, then the extract API for its
     *   intro paragraphs. */
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

    /** A fetched web page: title, reading text, verbatim code blocks. */
    private class WebPage(val title: String, val text: String,
                          val code: List<String>, val url: String)

    /** Search the open web and fetch the first page worth keeping.
     *  DuckDuckGo's no-JS endpoint for the links, then the kernel's
     *  HtmlText for the clean text - up to 3 candidates, first one
     *  with real content wins. */
    private fun fetchWeb(q: String): WebPage? {
        val html = http("https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(q, "UTF-8"))
        val links = parseDdgLinks(html)
        for ((url, title) in links.take(3)) {
            val pageHtml = try { http(url) } catch (e: Exception) { continue }
            val text = HtmlText.toText(pageHtml)
            if (text.length < 200) continue
            return WebPage(title, text, HtmlText.codeBlocks(pageHtml), url)
        }
        return null
    }

    /** (url, title) pairs from DuckDuckGo lite's plain result HTML -
    *  ad and internal links filtered, uddg redirects unwrapped. */
    private fun parseDdgLinks(html: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in Regex("(?is)<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>").findAll(html)) {
            var url = m.groupValues[1]
            val uddg = Regex("[?&]uddg=([^&]+)").find(url)
            if (uddg != null) {
                url = try {
                    java.net.URLDecoder.decode(uddg.groupValues[1], "UTF-8")
                } catch (e: Exception) { continue }
            }
            if (url.startsWith("//")) url = "https:" + url
            val title = HtmlText.decodeEntities(
                Regex("(?s)<[^>]*>").replace(m.groupValues[2], " ")).trim()
            // keep only real result pages: external http(s), not ads, with
            // a real title - the rest is the engine's own chrome
            if (url.startsWith("http") && !url.contains("duckduckgo.com") &&
                title.length > 3) out.add(url to title)
        }
        return out.distinctBy { it.first }
    }

    /** Every fetch, in plain sight: fetch_log.txt feeds the Fetches
     *  screen, and deleting an entry deletes the article with it. */
    private fun logFetch(act: MainActivity, title: String, url: String) {
        try {
            File(act.filesDir, "fetch_log.txt").appendText(
                System.currentTimeMillis().toString() + "\t" +
                    title.replace("\t", " ").replace("\n", " ") + "\t" + url + "\n")
        } catch (e: Exception) { }
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
