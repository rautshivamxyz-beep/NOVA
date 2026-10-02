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
        if (kws.isEmpty()) {
            // v9.3.0: keywords empty AND the raw text is Devanagari -
            // nothing local or online can be searched for this; be
            // honest about it instead of falling through silently
            if (DEVANAGARI.containsMatchIn(text)) {
                honestNotSearchable(act, text)
                return
            }
            act.launchNcieSend(text, offered = true); return
        }
        val q = kws.joinToString(" ")
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
            // 1. Wikipedia first - curated, clean, no page parsing needed
            val wiki = try { fetchWiki(act, q) } catch (e: Exception) { null }
            var saved: String? = null
            if (wiki != null && Coverage.ratio(text, wiki.second) >= 0.3) {
                if (WikiCore.appendArticle(act, wiki.first, wiki.second)) {
                    logFetch(act, wiki.first, "wikipedia")
                    saved = wiki.first
                }
            }
            // 2. the open web - only when Wikipedia had nothing usable
            if (saved == null) {
                val page = try { fetchWeb(act, q) } catch (e: Exception) { null }
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
                act.launchNcieSend(text, offered = true)
            }
        }
    }

    /** (title, intro extract) from Wikipedia, or null. Two requests:
     *  the search API for the best title, then the extract API for its
     *  intro paragraphs. */
    private fun fetchWiki(act: MainActivity, q: String): Pair<String, String>? {
        val search = http(act, "https://en.wikipedia.org/w/api.php?action=query&format=json" +
            "&list=search&srlimit=1&srsearch=" + URLEncoder.encode(q, "UTF-8"))
        val title = try {
            JSONObject(search).getJSONObject("query").getJSONArray("search")
                .getJSONObject(0).getString("title")
        } catch (e: Exception) { return null }
        val page = http(act, "https://en.wikipedia.org/w/api.php?action=query&format=json" +
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
    private fun fetchWeb(act: MainActivity, q: String): WebPage? {
        val html = http(act, "https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(q, "UTF-8"))
        val links = parseDdgLinks(html)
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
