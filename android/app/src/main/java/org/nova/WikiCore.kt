package org.nova

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Offline Wikipedia: downloads the "vital articles" collection (levels 2-4,
 * ~11,000 core topics) once, then finds matching articles for a question so
 * NOVA can answer from real knowledge instead of guessing.
 *
 * Storage is one line per article in wiki/articles.txt:
 *   TITLE <unit-sep> paragraph <unit-sep> paragraph ...
 * done.txt records which titles were fetched (resumable), and the title
 * index is built in memory on first search (or app start via [warmUp]).
 */
object WikiCore {

    data class Hit(val title: String, val text: String)

    /** (progress 0..1, label) while downloading; (0, "") when idle. */
    private val _state = MutableStateFlow(Pair(0f, ""))
    val state: StateFlow<Pair<Float, String>> = _state

    @Volatile var downloading = false
        private set

    private fun dir(ctx: Context): File = File(ctx.filesDir, "wiki").apply { mkdirs() }
    private fun articlesFile(ctx: Context): File = File(dir(ctx), "articles.txt")
    private fun doneFile(ctx: Context): File = File(dir(ctx), "done.txt")

    /** True once at least one article is stored. */
    fun isReady(ctx: Context): Boolean = doneFile(ctx).exists()

    fun articleCount(ctx: Context): Int {
        val f = File(dir(ctx), "count")
        return if (f.exists()) f.readText().trim().toIntOrNull() ?: 0 else 0
    }

    /** Deletes the downloaded articles. */
    fun remove(ctx: Context) {
        index = null
        dir(ctx).deleteRecursively()
    }

    // ----------------------------------------------------------- download

    /**
     * Downloads/updates the article set. Batches 20 titles per request,
     * paces itself to be gentle on mobile data and Wikipedia, and resumes
     * from where a previous (interrupted) run stopped.
     */
    suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        if (downloading) return@withContext
        downloading = true
        val d = dir(ctx)
        try {
            // 1) collect the topic list from the vital-articles pages
            val titles = linkedSetOf<String>()
            val lists = mutableListOf("Vital articles/Level/2", "Vital articles/Level/3")
            try { lists += level4Subpages() } catch (e: Exception) { }
            for ((i, page) in lists.withIndex()) {
                _state.value = Pair((i + 1f) / lists.size * 0.05f,
                    "reading topic list ${i + 1}/${lists.size}")
                try { titles += links(page) } catch (e: Exception) { }
            }
            if (titles.isEmpty()) {
                _state.value = Pair(0f, "could not reach Wikipedia")
                return@withContext
            }

            // 2) fetch intro texts, 20 titles per request, resumable
            val done = doneFile(ctx).readLines().toHashSet()
            val queue = titles.filter { it !in done }
            var total = articleCount(ctx)
            val nTotal = queue.size
            var fetched = 0
            val bw = BufferedWriter(FileWriter(articlesFile(ctx), true))
            val dw = BufferedWriter(FileWriter(doneFile(ctx), true))
            fun store(batch: List<String>) {
                val pages = fetchBatch(batch)
                for ((t, text) in pages) {
                    if (text.length > 200) {
                        bw.write(t.replace('\n', ' '))
                        bw.write("\u241F")
                        bw.write(text.trim().replace('\n', '\u241F'))
                        bw.write("\n")
                        dw.write(t.replace('\n', ' ')); dw.write("\n")
                        total++
                    }
                }
                // mark the whole requested batch as handled either way,
                // so redirects and misses are not retried forever
                for (t in batch) { dw.write(t.replace('\n', ' ')); dw.write("\n") }
                File(d, "count").writeText(total.toString())
                bw.flush(); dw.flush()
            }
            var i = 0
            while (i < queue.size) {
                val batch = queue.subList(i, minOf(i + 20, queue.size))
                i += 20
                try {
                    store(batch)
                } catch (e: Exception) {
                    // one retry after a pause - Wikipedia rate-limits bursts
                    try { Thread.sleep(5000) } catch (x: Exception) { }
                    try { store(batch) } catch (e2: Exception) { }
                }
                fetched += batch.size
                _state.value = Pair(0.05f + 0.95f * fetched / nTotal,
                    "$fetched/$nTotal articles")
                Thread.sleep(1200)
            }
            bw.close(); dw.close()
            index = null
            _state.value = Pair(0f, "done - $total articles saved")
        } finally {
            downloading = false
            Thread.sleep(3000)
            _state.value = Pair(0f, "")
        }
    }

    /** Fetches plain-text intro extracts for up to 20 titles. */
    private fun fetchBatch(titles: List<String>): List<Pair<String, String>> {
        val q = titles.joinToString("|") { URLEncoder.encode(it, "UTF-8") }
        val url = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts" +
            "&explaintext&exintro&exlimit=20&redirects=1&format=json&titles=$q"
        val pages = JSONObject(http(url)).getJSONObject("query").getJSONObject("pages")
        val out = mutableListOf<Pair<String, String>>()
        for (k in pages.keys()) {
            val p = pages.getJSONObject(k)
            val t = p.optString("title")
            val e = p.optString("extract", "")
            if (t.isNotEmpty() && e.isNotEmpty()) out.add(t to e)
        }
        return out
    }

    /** Article titles linked from a vital-articles list page. */
    private fun links(page: String): List<String> {
        val u = "https://en.wikipedia.org/w/api.php?action=parse&prop=links&format=json&page=" +
            URLEncoder.encode(page, "UTF-8")
        val arr = JSONObject(http(u)).getJSONObject("parse").getJSONArray("links")
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optInt("ns") == 0) out.add(o.optString("*"))
        }
        return out
    }

    /** The ~55 subpages of "Vital articles/Level/4" (People, History, ...). */
    private fun level4Subpages(): List<String> {
        val u = "https://en.wikipedia.org/w/api.php?action=query&list=allpages&apprefix=" +
            URLEncoder.encode("Vital articles/Level/4/", "UTF-8") +
            "&apnamespace=4&aplimit=500&format=json"
        val arr = JSONObject(http(u)).getJSONObject("query").getJSONArray("allpages")
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString("title"))
        return out
    }

    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "NOVA-local-assistant/1.0 (offline study)")
        try {
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------- search

    private val STOP = setOf("what", "who", "when", "where", "why", "how", "the", "and",
        "for", "are", "was", "were", "is", "does", "did", "do", "with", "about",
        "tell", "explain", "describe", "which", "that", "this", "from", "many",
        "much", "some", "give", "list", "name", "then", "than", "into", "also")

    private fun words(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOP }.toSet()

    /** In-memory title index: (title, byte offset of its line). */
    @Volatile private var index: List<Pair<String, Long>>? = null

    /** Builds the search index ahead of time (call from a background thread). */
    fun warmUp(ctx: Context) {
        if (index == null) index = buildIndex(ctx)
    }

    /** Finds the most relevant stored articles for a question. */
    fun search(ctx: Context, query: String, maxResults: Int = 2): List<Hit> {
        if (!isReady(ctx)) return emptyList()
        val qw = words(query)
        if (qw.isEmpty()) return emptyList()
        val ix = index ?: buildIndex(ctx).also { index = it }
        if (ix.isEmpty()) return emptyList()
        val ql = query.lowercase()
        val scored = ix.mapNotNull { (title, off) ->
            val score = qw.count { it in words(title) } +
                (if (title.lowercase() in ql) 2 else 0)
            if (score > 0) Triple(score, title, off) else null
        }.sortedWith(compareByDescending<Triple<Int, String, Long>> { it.first }
            .thenBy { it.second })
        if (scored.isEmpty()) return emptyList()
        val out = mutableListOf<Hit>()
        for ((_, title, off) in scored.take(maxResults)) {
            val line = readLineAt(ctx, off) ?: continue
            val parts = line.split('\u241F')
            val paras = parts.drop(1).filter { it.isNotBlank() }
            if (paras.isEmpty()) continue
            val best = paras.sortedByDescending { p -> qw.count { p.lowercase().contains(it) } }
                .take(3).joinToString(" ")
            val text = if (best.length > 1100) best.substring(0, 1100) + "…" else best
            out.add(Hit(title, text))
        }
        return out
    }

    private fun buildIndex(ctx: Context): List<Pair<String, Long>> {
        val f = articlesFile(ctx)
        if (!f.exists()) return emptyList()
        val out = mutableListOf<Pair<String, Long>>()
        BufferedReader(java.io.FileReader(f)).use { r ->
            var off = 0L
            while (true) {
                val line = r.readLine() ?: break
                val title = line.substringBefore('\u241F')
                if (title.isNotEmpty()) out.add(title to off)
                off += line.toByteArray(Charsets.UTF_8).size + 1L
            }
        }
        return out
    }

    private fun readLineAt(ctx: Context, off: Long): String? = try {
        RandomAccessFile(articlesFile(ctx), "r").use { raf ->
            raf.seek(off)
            raf.readLine()?.let { String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }
        }
    } catch (e: Exception) { null }
}
