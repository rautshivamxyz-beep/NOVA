package org.nova.ncie.android

import org.nova.ncie.Tokens

import org.nova.NovaLog

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
import org.nova.ncie.knowledge.EntityMatch
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
            try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { NovaLog.quiet("OnlineFetch", e) }
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
        // v9.32.2 "Search the question": searching the bare keywords sent "modi" to
        // Wikipedia, where it is ambiguous, and the answer came back about his
        // estranged wife. A natural question resolves the entity correctly, so a
        // short question is searched as written; only a long one falls back to
        // its key terms.
        val q = if (devanagari || text.trim().length <= 120) text.trim().take(160)
                else kws.joinToString(" ")
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
        // v9.32.0 "Always online": when the user has asked NOVA to look things up
        // on its own, the dialog is skipped and the fetch runs immediately - a
        // grounded answer is the only thing that stops the invented facts.
        if (org.nova.Settings(act).autoOnline) {
            fetch(act, text, q)
            return
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
        val q = if (text.trim().length <= 120) text.trim().take(160) else kws.joinToString(" ")
        if (org.nova.Settings(act).autoOnline) { fetch(act, text, q); return }
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
                try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { NovaLog.quiet("OnlineFetch", e) }
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
            // v9.33.14 "The right entity or nothing": a "who is X" question
            // whose fetched article does not NAME X in its title is the wrong
            // answer - "who is physics wallah" was answered with the Radha
            // Bhatt intro because her page merely mentions those words. An
            // article that is not about the thing asked is never served, and
            // never stored.
            val wrongEntity = wiki != null && entityQuestion(text) &&
                !EntityMatch.titleMatchesEntity(wiki.first, text)
            if (wiki != null && !wrongEntity && (Coverage.ratio(q, wiki.second) >= 0.15 ||
                    hits(q, wiki.second) >= 2)) {
                if (WikiCore.appendArticle(act, wiki.first, wiki.second)) {
                    logFetch(act, wiki.first, "wikipedia")
                    saved = wiki.first
                } else reason = "wikipedia: could not store"
            } else if (wrongEntity) {
                reason = "wikipedia: '" + wiki!!.first + "' does not name the thing asked about"
            } else if (wiki == null) {
                if (reason.isEmpty()) reason = "wikipedia: no usable article"
            } else reason = "wikipedia: weak match"
            // 2. the open web - only when Wikipedia had nothing usable
            var savedWeb: WebPage? = null
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
                    // Entity lookups require both title identity and content coverage.
                    // A page that merely mentions a name must never become a biography.
                    val wrongWebEntity = entityQuestion(text) &&
                        !EntityMatch.titleMatchesEntity(page.title, text)
                    if (!wrongWebEntity &&
                        (Coverage.ratio(q, body) >= 0.15 || hits(q, body) >= 2) &&
                        WikiCore.appendArticle(act, page.title, body)) {
                        logFetch(act, page.title, page.url)
                        saved = page.title
                        savedWeb = page
                    } else reason = joinReason(reason,
                        if (wrongWebEntity) "web: title does not match requested person" else "web: weak match")
                } else reason = joinReason(reason, "web: no page")
            }
            // v9.17.1: diagnose the failure on the IO thread (a proxy
            // reachability probe must never block the UI)
            val failMsg = if (saved == null) diagnose(act, reason) else null
            withContext(Dispatchers.Main) {
                if (saved != null) {
                    // v9.32.6: a direct entity question is answered FROM the
                    // article, not rewritten by the model. The article is also
                    // stored, so the offline wiki still gets it.
                    val src = wiki?.second
                    if (src != null && wiki.first == saved && entityQuestion(text)) {
                        act.toast("Found \"$saved\" - answering from it")
                        directAnswer(act, cleanSourceText(src), saved)
                    } else if (savedWeb != null && savedWeb.title == saved && entityQuestion(text)) {
                        val webSource = savedWeb!!
                        act.toast("Found \"$saved\" - answering from the source")
                        directAnswer(act, cleanSourceText(webSource.text),
                            webSource.title + " — " + webSource.url, "Web")
                    } else {
                        act.toast("Saved \"$saved\" - now offline forever")
                        act.launchNcieSend(text, offered = true)
                    }
                } else {
                    // v9.29.1: the lookup found NOTHING. Re-sending with
                    // offered=true used to disable every honesty gate and let
                    // the model invent an answer from its own memory - that is
                    // exactly how "Modi's wife" came back as fabricated names.
                    // Answer honestly instead of guessing.
                    act.toast(failMsg ?: "Couldn't find anything good")
                    answerNotFound(act)
                }
            }
        }
    }

    /** v9.29.1: a lookup that found nothing gets an honest reply, never a
     *  guess. Deterministic - no model call - so a failed search can never
     *  turn into invented names, dates or numbers. */
    private fun answerNotFound(act: MainActivity) {
        act.offeredQuestionShown = null
        val reply = Msg(Role.ASSISTANT,
            "I looked that up and couldn't find a reliable source for it, so " +
                "I won't guess. Try rephrasing the question, or ask about " +
                "something in your notes.")
        act.currentChat.messages.add(reply)
        act.adapter.add(reply)
        act.scrollToEnd()
        act.scope.launch(Dispatchers.IO) {
            try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { NovaLog.quiet("OnlineFetch", e) }
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
        // v9.33.76: every-script content words. The ASCII-length filter
        // dropped a Hindi query's two-character words, so the keyword side
        // of the acceptance gate counted zero hits for a Hindi question.
        val terms = Tokens.contentWords(q).distinct()
        if (terms.isEmpty()) return 0
        // v9.33.7: whole-word match - substring matching counted "art"
        // inside "start", inflating the 2+ keyword acceptance gate
        val low = Tokens.norm(text)
        return terms.count { low.contains(" " + it + " ") }
    }

    /** (title, intro extract) from Wikipedia, or null. Two requests:
     *  the search API for the best title, then the extract API for its
     *  intro paragraphs. */
    /**
     * v9.32.6 "Answer from the source": for a direct entity question ("who is
     * X", "what is X") the fetched Wikipedia intro IS the answer. Letting the
     * small model rewrite it is what produced "Jashodaben Modi is the estranged
     * wife of Narendra Modi, the former Prime Minister" and "Thomas Shelby is
     * portrayed by Paul Anderson" - the model filled the gaps from its own
     * memory. Returning the source text verbatim cannot be wrong and needs no
     * generation, so the answer is also instant. Every other question still
     * goes through the model.
     */
    /** v9.33.38: the rule now lives in EntityMatch, so the OFFLINE Wikipedia
     *  path can apply exactly the same test instead of serving the wrong
     *  article. See [EntityMatch.isEntityQuestion] for why the shape and the
     *  length are both required. */
    private fun entityQuestion(q: String): Boolean = EntityMatch.isEntityQuestion(q)

    /** Posts the source text as the answer. Deterministic - no model call. */
    private fun directAnswer(act: MainActivity, body: String, source: String,
                             sourceKind: String = "Wikipedia") {
        act.offeredQuestionShown = null
        val reply = Msg(Role.ASSISTANT, body + "\n\n(Source: " + sourceKind + " - " + source + ")")
        act.currentChat.messages.add(reply)
        act.adapter.add(reply)
        act.scrollToEnd()
        act.scope.launch(Dispatchers.IO) {
            try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { NovaLog.quiet("OnlineFetch", e) }
        }
    }

    private fun fetchWiki(act: MainActivity, q: String): Pair<String, String>? {
        // v9.33.15 "Name it exactly": a "who is X" question is first looked up
        // as a page TITLE. Full-text search ranked "Radha Bhatt" first for
        // "physics wallah" because her page mentions it; the page actually
        // CALLED that ("PhysicsWallah") is the right answer, and a title
        // lookup finds it directly.
        if (entityQuestion(q)) {
            val phrase = EntityMatch.entityPhrase(q)
            if (phrase.isNotBlank()) {
                val exact = try { pageByTitle(act, phrase) } catch (e: Exception) { null }
                if (exact != null && EntityMatch.titleMatchesEntity(exact.first, q)) return exact
            }
        }
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
            // v9.33.61: entity lookups must not accept a page merely because
            // it mentions the requested name. Check the title before returning
            // so a wrong first hit doesn't block a correct later result.
            if (entityQuestion(q) && !EntityMatch.titleMatchesEntity(title, q)) continue
            // v9.29.5: skip disambiguation pages ("X may refer to:") - the top
            // hit for many names is one, and grounding on a list of unrelated
            // people is worse than trying the next hit.
            if (extract != null && extract.length >= 60 &&
                !extract.contains("may refer to:", ignoreCase = true)) return title to extract
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

    /** The page whose TITLE is (or redirects to) [title], or null when there
     *  is no such page or it has no usable intro. */
    private fun pageByTitle(act: MainActivity, title: String): Pair<String, String>? {
        val page = http(act, "https://en.wikipedia.org/w/api.php?action=query&format=json" +
            "&redirects=1&prop=extracts&explaintext=1&exintro=1&titles=" +
            URLEncoder.encode(title, "UTF-8"))
        val pages = JSONObject(page).getJSONObject("query").getJSONObject("pages")
        val o = pages.getJSONObject(pages.keys().next())
        if (o.has("missing")) return null
        val resolved = o.optString("title").ifBlank { title }
        val extract = o.optString("extract")
        if (extract.length >= 60 && !extract.contains("may refer to:", ignoreCase = true))
            return resolved to extract
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
        // The primary search endpoint is user-configurable in Settings.
        // Require HTTPS and a {query} placeholder; fall back to known endpoints
        // when a custom provider is blocked or returns no usable links.
        val template = Settings(act).webSearchUrlTemplate
        var links = if (template.startsWith("https://") && template.contains("{query}")) {
            try {
                parseDdgLinks(http(act, template.replace("{query}", enc)), q)
            } catch (e: Exception) { emptyList() }
        } else emptyList()
        if (links.isEmpty()) {
            links = try {
                parseDdgLinks(http(act, "https://html.duckduckgo.com/html/?q=" + enc), q)
            } catch (e: Exception) { emptyList() }
        }
        if (links.isEmpty()) {
            links = try {
                parseDdgLinks(http(act, "https://lite.duckduckgo.com/lite/?q=" + enc), q)
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
    private fun parseDdgLinks(html: String, q: String): List<Pair<String, String>> {
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
                title.length > 3 &&
                (!entityQuestion(q) || EntityMatch.titleMatchesEntity(title, q)))
                out.add(url to title)
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
            NcieFiles.write(f, kept.takeLast(500).joinToString("\n") + "\n")
        } catch (e: Exception) { NovaLog.quiet("OnlineFetch", e) }
    }

    /** v9.15.0 "Private Fetch": one raw request, routed through the
     *  configured SOCKS proxy (Orbot/Tor or a user proxy) when one is on. */
    private fun httpOnce(act: MainActivity, url: String): String =
        NovaNet.getText(act, url, connectMs = 4000, readMs = 8000)

    /** v9.15.0 "Private Fetch": https only. A plain-http URL is upgraded
     *  to https outright and never sent in the clear - if the upgrade
     *  fails the fetch fails honestly, instead of leaking the query. */
    private fun http(act: MainActivity, url: String): String {
        val secure = if (url.startsWith("http://"))
            "https://" + url.substring("http://".length) else url
        return httpOnce(act, secure)
    }
}
