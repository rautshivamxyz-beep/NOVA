package org.nova

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import org.nova.ncie.android.NcieGround
import org.nova.ncie.knowledge.WikiStore

/**
 * Offline Wikipedia: downloads a pre-built "vital articles" dataset
 * (one gzip file from this repo, built by the wiki-dataset workflow),
 * then finds matching articles for a question so NOVA can answer from
 * real knowledge instead of guessing.
 *
 * Storage is one line per article in wiki/articles.txt:
 *   TITLE <unit-sep> paragraph <unit-sep> paragraph ...
 * done.txt records the titles, and the title index is built in memory
 * on first search (or app start via [warmUp]).
 */
object WikiCore {

    data class Hit(val title: String, val text: String)

    /** (progress 0..1, label) while downloading; (0, "") when idle. */
    private val _state = MutableStateFlow(Pair(0f, ""))
    val state: StateFlow<Pair<Float, String>> = _state

    @Volatile var downloading = false
        private set

    /** Last download failure reason - shown in Knowledge until the next try. */
    @Volatile var lastError: String? = null
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
        prepared = null
        dir(ctx).deleteRecursively()
    }

    // ----------------------------------------------------------- download

    /**
     * One reliable download instead of ~550 rate-limited API calls: the
     * whole vital-articles set is pre-built into a single text file in the
     * repo (by the "Build Wikipedia dataset" workflow) and fetched with
     * one gzip connection. Line format is the storage format, so lines
     * are copied straight into articles.txt.
     */
    suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        if (downloading) return@withContext
        downloading = true
        lastError = null
        val d = dir(ctx)
        try {
            _state.value = Pair(0.04f, "downloading Wikipedia data")
            val url = "https://raw.githubusercontent.com/rautshivamxyz-beep/NOVA/main/wiki/articles-v1.txt"
            val text = http(url)
            val lines = text.split("\n").filter { it.contains("\u241F") }
            if (lines.size < 100) {
                lastError = "dataset file not ready yet - try again in a few minutes"
                _state.value = Pair(0f, lastError ?: "")
                return@withContext
            }
            // v7.6: drop the old "ready" flag first and create done.txt
            // ONLY after the articles are complete - an interrupted
            // download used to leave a half dataset permanently "ready"
            doneFile(ctx).delete()
            val doneTmp = File(d, "done.tmp")
            val bw = BufferedWriter(FileWriter(articlesFile(ctx), false))
            val dw = BufferedWriter(FileWriter(doneTmp, false))
            var n = 0
            for (line in lines) {
                val title = line.substringBefore('\u241F').trim()
                if (title.isEmpty()) continue
                bw.write(line)
                bw.write("\n")
                dw.write(title.replace('\n', ' '))
                dw.write("\n")
                n++
                if (n % 400 == 0) {
                    _state.value = Pair(0.04f + 0.9f * n / lines.size, "saving $n articles")
                    bw.flush(); dw.flush()
                    File(d, "count").writeText(n.toString())
                }
            }
            bw.close(); dw.close()
            doneTmp.renameTo(doneFile(ctx))
            File(d, "count").writeText(n.toString())
            index = null
            // v9.6.0 "Engine Pack": the whole store was replaced - drop
            // the grounding index; the next retrieve rebuilds it.
            NcieGround.indexReset(ctx)
            prepared = null
            _state.value = Pair(0f, "done - $n articles saved")
        } catch (e: Exception) {
            lastError = "download failed - check internet, then try again"
            _state.value = Pair(0f, lastError ?: "")
        } finally {
            downloading = false
            if (lastError == null) {
                kotlinx.coroutines.delay(3000)
                _state.value = Pair(0f, "")
            }
        }
    }

    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "NOVA-local-assistant/1.0 (offline study)")
        // gzip cuts the transfer to ~1/4 - the dataset is several MB
        conn.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val stream = if (conn.contentEncoding?.equals("gzip", true) == true)
                java.util.zip.GZIPInputStream(conn.inputStream) else conn.inputStream
            return stream.bufferedReader().readText()
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

    /** v8.1.0 (opt #3): the index's PRE-TOKENIZED form - titles are
     *  tokenized once per build here, not once per query inside
     *  WikiStore.search. Built together with [index], invalidated
     *  together with it. */
    @Volatile private var prepared: WikiStore.PreparedIndex? = null
    private val wikiStore = WikiStore()

    /** Builds the search index ahead of time (call from a background thread). */
    fun warmUp(ctx: Context) {
        if (index == null) {
            val ix = buildIndex(ctx)
            index = ix
            prepared = wikiStore.prepare(ix)
        }
    }

    /** v8.4.0 (stage 1): an online-fetched article joins the store. One
     *  articles.txt line (title + paragraphs) is appended and the in-memory
     *  index + prepared form are refreshed; a same-title article replaces
     *  its old copy. The article is offline forever after this call. */
    fun appendArticle(ctx: Context, title: String, text: String): Boolean {
        if (!isReady(ctx)) return false
        // one line per article: stray newlines would corrupt the format
        val paras = text.split("\n\n").map { it.replace("\n", " ").trim() }
            .filter { it.isNotEmpty() }
        if (paras.isEmpty()) return false
        val line = title + paras.joinToString("") { "\u241F$it" } + "\n"
        val f = articlesFile(ctx)
        val off = f.length()
        try { f.appendText(line) } catch (e: Exception) { return false }
        // v9.6.0 "Engine Pack": the stored article joins the grounding index
        NcieGround.indexUpdate(ctx, title, text)
        val cur = index ?: return true   // warmUp() will pick it up
        val ix = cur.toMutableList()
        ix.removeAll { it.first.equals(title, ignoreCase = true) }
        ix.add(title to off)
        index = ix
        prepared = wikiStore.prepare(ix)
        return true
    }

    /** v8.5.0: the full text of one stored article (the Fetches screen's
     *  preview), or null when the title is not in the store. */
    fun articleText(ctx: Context, title: String): String? {
        val ix = index ?: return null
        val i = ix.indexOfFirst { it.first.equals(title, ignoreCase = true) }
        if (i < 0) return null
        val line = readLineAt(ctx, ix[i].second) ?: return null
        return line.substringAfter('\u241F').replace('\u241F', '\n')
    }

    /** v8.5.0: remove one article - the Fetches screen's delete. The
     *  whole file is rewritten without its line, then the index and its
     *  prepared form are rebuilt. Call from a background thread. Returns
     *  true when the title was there. */
    fun removeArticle(ctx: Context, title: String): Boolean {
        val f = articlesFile(ctx)
        if (!f.exists()) return false
        val lines = try { f.readLines() } catch (e: Exception) { return false }
        val kept = lines.filter { !it.substringBefore('\u241F').equals(title, ignoreCase = true) }
        if (kept.size == lines.size) return false
        try {
            f.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
        } catch (e: Exception) { return false }
        val ix = buildIndex(ctx)
        index = ix
        NcieGround.indexUpdate(ctx, title, null)
        prepared = wikiStore.prepare(ix)
        return true
    }

    /** Finds the most relevant stored articles for a question. */
    fun search(ctx: Context, query: String, maxResults: Int = 2): List<Hit> {
        if (!isReady(ctx)) return emptyList()
        val qw = words(query)
        if (qw.isEmpty()) return emptyList()
        // NEVER build the index here: it reads the whole multi-MB articles
        // file, and doing that on the UI thread froze the first message of
        // every chat. warmUp() builds it in the background after app start;
        // until it's ready, this one question just runs without Wikipedia.
        val ix = index ?: return emptyList()
        if (ix.isEmpty()) return emptyList()
        // v0.7.0 (#1): the scoring/selection half now lives kernel-side —
        // org.nova.ncie.knowledge.WikiStore, ported verbatim from the code
        // that was here and verified query-identical (20-query battery,
        // exact output match) before this swap. This wrapper keeps the
        // file I/O: the byte-offset index and the reads at offsets.
        // v8.1.0 (opt #3): the PREPARED index - one WikiStore instance,
        // its titles tokenized once at warm-up, not once per query
        val p = prepared ?: return emptyList()
        return wikiStore.search(p, query, maxResults) { off -> readLineAt(ctx, off) }
            .map { Hit(it.title, it.text) }
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
