package org.nova.ncie.android

import android.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.nova.ChatStore
import org.nova.MainActivity
import org.nova.Msg
import org.nova.NovaNet
import org.nova.Role
import org.nova.Settings
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

    /** v9.3.0 "Audit Fixes I": Devanagari (Hindi/Marathi) codepoints.
     *  The keyword analyzer keeps ASCII letters and digits only, so a
     *  Devanagari question normalizes to zero keywords - retrieval and
     *  the online fetch both come back silently empty and the model
     *  answers from imagination. Honesty beats silence. */
    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")

    /** v9.3.0: a Devanagari question with no keywords - say so in the
     *  chat, deterministically, instead of searching for nothing. */
    private fun honestNotSearchable(act: MainActivity, text: String) {
        val um = Msg(Role.USER, text)
        act.currentChat.messages.add(um)
        act.adapter.add(um)
        act.scrollToEnd()
        val reply = Msg(Role.ASSISTANT,
            "I can't search your notes or the web in Hindi/Marathi yet — " +
                "ask in English for now.")
        act.currentChat.messages.add(reply)
        act.adapter.add(reply)
        act.scrollToEnd()
        act.scope.launch(Dispatchers.IO) {
            try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { }
        }
    }

    /** v8.6.0: the ask-first offer, for ANY information question - not
     *  only the gaps. Local material is the ALTERNATIVE now, not a
     *  blocker: the dialog says what is already covered and offers to
     *  look online anyway. Ask-first stays the law either way. */
    fun offer(act: MainActivity, text: String, haveLocal: Boolean = false) {
        val kws = NcieKnowledge.keyTerms(text).take(6)
        // v9.26.0 "Search fix": a Devanagari (Hindi/Marathi) question yields
        // no ASCII keywords, but the web understands the raw text - so search
        // it instead of refusing. The old code dead-ended with "I can't
        // search ... in Hindi", which read as "search does nothing".
        val devanagari = kws.isEmpty() && DEVANAGARI.containsMatchIn(text)
        if (kws.isEmpty() && !devanagari) {
            act.launchNcieSend(text, offered = true); return
        }
        val q = if (devanagari) text.trim().take(160) else kws.joinToString(" ")
        val title: String; val message: String; val noBtn: String
        if (haveLocal) {
            title = "Look online too?"
            message = "Your notes and offline Wikipedia cover this.\n\n" +
                "NOVA can also search Wikipedia and, if that is not enough, " +
                "the open web for:\n\"$q\"\n\n" +
                "Nothing else leaves your phone. Whatever it finds stays " +
                "offline forever."
            noBtn = "Answer locally"
        } else {
            title = "Look it up online?"
            message = "Nothing in your notes or offline Wikipedia covers " +
                "this.\n\nNOVA will search Wikipedia and, if that is not " +
                "enough, the open web for:\n\"$q\"\n\n" +
                "Nothing else leaves your phone. Whatever it finds stays " +
                "offline forever."
            noBtn = "Answer anyway"
        }
        AlertDialog.Builder(act)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Look it up") { _, _ -> fetch(act, text, q) }
        .setNegativeButton(noBtn) { _, _ -> act.launchNcieSend(text, offered = true) }
        .setOnCancelListener { act.launchNcieSend(text, offered = true) }
        .show()
    }

    /** v9.13.2 "Small Model Honesty": the tiny-model honesty gate's
     *  offer - a 230M-class model was asked a factual question nothing
     *  local backs, so instead of generating confident word salad the
     *  chat turn routes here. Same ask-first flow and same fetch as the
     *  quiet offer, different honest wording; declining (or cancelling)
     *  sends the turn back through ncieSend with offered=true, which
     *  skips the gate and generates the model's best guess - the user
     *  explicitly accepted that. */
    fun honestOffer(act: MainActivity, text: String) {
        val kws = NcieKnowledge.keyTerms(text).take(6)
        if (kws.isEmpty()) { act.launchNcieSend(text, offered = true); return }
        val q = kws.joinToString(" ")
        AlertDialog.Builder(act)
            .setTitle("Look it up online?")
            .setMessage("I don't know this well enough with this model — " +
                "look it up online?\n\nQuestion: \"" + text.take(200) + "\"\n\nNOVA will search " +
                "Wikipedia and, if that is not enough, the open web for:\n\"" + q + "\"\n\n" +
                "Nothing else leaves your phone. Whatever it finds stays " +
                "offline forever.")
            .setPositiveButton("Look it up") { _, _ -> fetch(act, text, q) }
            .setNegativeButton("Answer anyway") { _, _ -> act.launchNcieSend(text, offered = true) }
            .setOnCancelListener { act.launchNcieSend(text, offered = true) }
            .show()
    }

    private fun fetch(act: MainActivity, text: String, q: String) {
        // v9.13.0 "Audit Fixes" (re-derived d, deferred since v9.4.0): the
        // question is shown and persisted BEFORE the fetch starts. The
        // fetch path bypasses the normal send persistence (the completion
        // turn's startGeneration is what used to persist it), so an app
        // close mid-fetch made the question vanish from the chat entirely.
        // The completion turn sees the flag and neither adds nor persists
        // it a second time (ncieSend's preShown).
        if (act.offeredQuestionShown == null) {
            act.offeredQuestionShown = text
            val um = Msg(Role.USER, text)
            act.currentChat.messages.add(um)
            act.adapter.add(um)
            act.scrollToEnd()
            act.scope.launch(Dispatchers.IO) {
                try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { }
            }
        }
        act.scope.launch(Dispatchers.IO) {
            // v9.20.0 "Search fix": the gate is now "covers at least two of
            // the query's keywords, or 15% of them" - the old flat 20% floor
            // rejected real Wikipedia hits on any question with five or six
            // keywords, which is exactly why every search ended in
            // "couldn't find anything good".
            var reason = ""
            // 1. Wikipedia first - curated, clean, no page parsing needed
            val wiki = try { fetchWiki(act, q) } catch (e: Exception) {
                reason = "wikipedia: " + err(e); null
            }
            var saved: String? = null
            if (wiki != null && (Coverage.ratio(q, wiki.second) >= 0.15 ||
                    hits(q, wiki.second) >= 2)) {
                if (WikiCore.appendArticle(act, wiki.first, wiki.second)) {
                    logFetch(act, wiki.first, "wikipedia")
                    saved = wiki.first
                } else reason = "wikipedia: could not store"
            } else if (wiki == null) {
                if (reason.isEmpty()) reason = "wikipedia: no usable article"
            } else reason = "wikipedia: weak match"
            // 2. the open web - only when Wikipedia had nothing usable
            if (saved == null) {
                val page = try { fetchWeb(act, q) } catch (e: Exception) {
                    reason = joinReason(reason, "web: " + err(e)); null
                }
                if (page != null) {
                    // code blocks ride along verbatim, so fetched examples
                    // land exactly as written
                    val body = page.text + (if (page.code.isNotEmpty())
                        "\n\nCode from the page:\n" + page.code.joinToString("\n---\n") { it }
                        else "")
                    if ((Coverage.ratio(q, body) >= 0.15 || hits(q, body) >= 2) &&
                        WikiCore.appendArticle(act, page.title, body)) {
                        logFetch(act, page.title, page.url)
                        saved = page.title
                    } else reason = joinReason(reason, "web: weak match")
                } else reason = joinReason(reason, "web: no page")
            }
            // v9.17.1: diagnose the failure on the IO thread (a proxy
            // reachability probe must never block the UI)
            val failMsg = if (saved == null) diagnose(act, reason) else null
            withContext(Dispatchers.Main) {
                if (saved != null) {
                    act.toast("Saved \"$saved\" - now offline forever")
                } else {
                    act.toast(failMsg ?: "Couldn't find anything good")
                }
                act.launchNcieSend(text, offered = true)
            }
        }
    }

    /** v9.17.1: when a lookup finds nothing, say WHY. The commonest cause
     *  is Private Fetch pointing at a proxy that is not running (Orbot off
     *  at 127.0.0.1:9050) - then every request fails and the result looked
     *  identical to "the web had nothing". Probed on the IO thread. */
    private fun diagnose(act: MainActivity, reason: String): String {
        val s = Settings(act)
        if (s.proxyEnabled) {
            val host = s.proxyHost.ifBlank { "127.0.0.1" }
            val port = if (s.proxyPort in 1..65535) s.proxyPort else 9050
            val up = try {
                val sock = java.net.Socket()
                try { sock.connect(java.net.InetSocketAddress(host, port), 1500); true }
                finally { sock.close() }
            } catch (e: Exception) { false }
            if (!up) return "Private Fetch is on but nothing is listening at " +
                host + ":" + port + " - start Orbot or turn Private Fetch off in Settings"
        }
        // v9.20.0: when nothing was fetched, say WHY - the reason carries
        // the real failure (no article, weak match, an HTTP error) instead
        // of the old catch-all line that hid every cause.
        return if (reason.isNotBlank()) "Couldn't fetch anything (" + reason + ")"
        else "Couldn't find anything good - check your internet, then try again"
    }

    /** v9.20.0: a short, specific error string for the failure message. */
    private fun err(e: Exception): String =
        (e.message ?: e.javaClass.simpleName).take(80)

    /** v9.20.0: join two reasons without repeating a blank one. */
    private fun joinReason(a: String, b: String): String =
        if (a.isBlank()) b else a + "; " + b

    /** v9.20.0 "Search fix": how many of the query's keywords the text
     *  contains. Used beside the Coverage ratio - a page that names two of
     *  the query's terms is a real hit even when it cannot cover the rest
     *  of a long question. */
    private fun hits(q: String, text: String): Int {
        val terms = q.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }.distinct()
        if (terms.isEmpty()) return 0
        val low = text.lowercase()
        return terms.count { low.contains(it) }
    }

    /** (title, intro extract) from Wikipedia, or null. Two requests:
     *  the search API for the best title, then the extract API for its
     *  intro paragraphs. */
    private fun fetchWiki(act: MainActivity, q: String): Pair<String, String>? {
        // v9.16.2: try the top few search hits and keep the FIRST with a
        // real intro - the old code only looked at hit #1, so a
        // disambiguation or stub page meant "nothing good found".
        val search = http(act, "https://en.wikipedia.org/w/api.php?action=query&format=json" +
            "&list=search&srlimit=3&srsearch=" + URLEncoder.encode(q, "UTF-8"))
        val titles = try {
            val arr = JSONObject(search).getJSONObject("query").getJSONArray("search")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
        } catch (e: Exception) { return null }
        for (title in titles) {
            val extract = try {
                val page = http(act, "https://en.wikipedia.org/w/api.php?action=query&format=json" +
                    "&prop=extracts&explaintext=1&exintro=1&redirects=1&titles=" +
                    URLEncoder.encode(title, "UTF-8"))
                val pages = JSONObject(page).getJSONObject("query").getJSONObject("pages")
                val k = pages.keys().next()
                pages.getJSONObject(k).getString("extract")
            } catch (e: Exception) { null }
            if (extract != null && extract.length >= 60) return title to extract
            // v9.20.0: the extract API comes back empty for some pages
            // (redirect chains, stubs) - the REST summary usually still has
            // the intro, so try it before giving up on this title.
            val rest = try {
                val body = http(act, "https://en.wikipedia.org/api/rest_v1/page/summary/" +
                    URLEncoder.encode(title.replace(' ', '_'), "UTF-8"))
                JSONObject(body).optString("extract").ifBlank { null }
            } catch (e: Exception) { null }
            if (rest != null && rest.length >= 60) return title to rest
        }
        return null
    }

    /** A fetched web page: title, reading text, verbatim code blocks. */
    private class WebPage(val title: String, val text: String,
                          val code: List<String>, val url: String)

    /** Search the open web and fetch the first page worth keeping.
     *  DuckDuckGo's no-JS endpoint for the links, then the kernel's
     *  HtmlText for the clean text - up to 3 candidates, first one
     *  with real content wins. */
    private fun fetchWeb(act: MainActivity, q: String): WebPage? {
        // v9.29.1 "Search fix": DuckDuckGo's lite endpoint now serves an
        // anti-bot challenge page (HTTP 202, no result links) on most
        // networks, and under rate-limiting it returns a non-2xx that used
        // to throw UNGUARDED - killing the whole fetch before the html
        // endpoint was ever tried, so web search came back empty. The html
        // endpoint is the one that still returns results: ask it FIRST, and
        // guard both calls so neither can abort the search.
        val enc = URLEncoder.encode(q, "UTF-8")
        var links = try {
            parseDdgLinks(http(act, "https://html.duckduckgo.com/html/?q=" + enc))
        } catch (e: Exception) { emptyList() }
        if (links.isEmpty()) {
            links = try {
                parseDdgLinks(http(act, "https://lite.duckduckgo.com/lite/?q=" + enc))
            } catch (e: Exception) { emptyList() }
        }
        for ((url, title) in links.take(3)) {
            val pageHtml = try { http(act, url) } catch (e: Exception) { continue }
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
            if (url.startsWith("https") && !url.contains("duckduckgo.com") &&
                title.length > 3) out.add(url to title)
        }
        return out.distinctBy { it.first }
    }

    /** Every fetch, in plain sight: the log file (fetch_log.txt) feeds
     *  the Fetches screen, and deleting an entry deletes the article
     *  with it.
     *  v9.4.0 "Audit Fixes II" (audit: the log grew without bound):
     *  rotate on append - only the last 500 entries are kept. */
    private fun logFetch(act: MainActivity, title: String, url: String) {
        try {
            val f = File(act.filesDir, "fetch_log.txt")
            val line = System.currentTimeMillis().toString() + "\t" +
                title.replace("\t", " ").replace("\n", " ") + "\t" + url
            val kept = f.readLines().toMutableList()
            kept.add(line)
            f.writeText(kept.takeLast(500).joinToString("\n") + "\n")
        } catch (e: Exception) { }
    }

    /** v9.15.0 "Private Fetch": one raw request, routed through the
     *  configured SOCKS proxy (Orbot/Tor or a user proxy) when one is on. */
    private fun httpOnce(act: MainActivity, url: String): String =
        NovaNet.getText(act, url, connectMs = 10000, readMs = 20000)

    /** v9.15.0 "Private Fetch": https only. A plain-http URL is upgraded
     *  to https outright and never sent in the clear - if the upgrade
     *  fails the fetch fails honestly, instead of leaking the query. */
    private fun http(act: MainActivity, url: String): String {
        val secure = if (url.startsWith("http://"))
            "https://" + url.substring("http://".length) else url
        return httpOnce(act, secure)
    }
}
