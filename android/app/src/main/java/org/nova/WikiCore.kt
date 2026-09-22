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
            val bw = BufferedWriter(FileWriter(articlesFile(ctx), false))
            val dw = BufferedWriter(FileWriter(doneFile(ctx), false))
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
            File(d, "count").writeText(n.toString())
            index = null
            _state.value = Pair(0f, "done - $n articles saved")
        } catch (e: Exception) {
            lastError = "download failed - check internet, then try again"
            _state.value = Pair(0f, lastError ?: "")
        } finally {
            downloading = false
            if (lastError == null) {
                Thread.sleep(3000)
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

    /** Builds the search index ahead of time (call from a background thread). */
    fun warmUp(ctx: Context) {
        if (index == null) index = buildIndex(ctx)
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
