package org.nova

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads text out of PDF files for NOVA's document chat.
 *
 * Speed: cleaned text is cached per file, so opening the same PDF a second
 * time is instant (no re-parse).
 *
 * Logic: extraction is page-aware (each page ends with a "- page N -"
 * marker, so "what's on page 12" works) and position-sorted, so
 * two-column textbook pages and slides come out in real reading order
 * instead of scrambled halves. Each page is cleaned - hyphenated line
 * breaks are joined, page-number/URL/email/leader lines are dropped -
 * and lines that repeat on most pages (running headers and footers like
 * "sst notes | Page 4" or a website banner) are removed, because they
 * pollute every chunk the model later sees. Scanned, image-only PDFs are
 * detected (pages exist, text doesn't) and reported as empty. Small
 * models answer much better from clean text.
 */
object PdfDoc {

    /** How much full text to keep in the cache (about a 600-page book). */
    private const val CACHE_CAP = 400_000

    /** Extracts (and caches) up to [maxChars] characters of clean text. */
    suspend fun extractText(context: Context, uri: Uri, maxChars: Int = 250_000): String =
        withContext(Dispatchers.IO) {
            val key = cacheKey(context, uri)
                ?: return@withContext extract(context, uri, maxChars)
            val cache = File(File(context.filesDir, "pdf_cache").apply { mkdirs() }, key)
            if (cache.exists()) {
                val t = try { cache.readText() } catch (e: Exception) { "" }
                if (t.isNotBlank()) {
                    return@withContext if (t.length > maxChars)
                        t.substring(0, maxChars) + "\n[...document truncated]" else t
                }
            }
            val text = extract(context, uri, CACHE_CAP)
            if (text.isNotBlank()) try { cache.writeText(text) } catch (e: Exception) { }
            if (text.length > maxChars)
                text.substring(0, maxChars) + "\n[...document truncated]" else text
        }

    /** Stable key for the cache: file name + size + uri. */
    private fun cacheKey(context: Context, uri: Uri): String? = try {
        var name = ""; var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = c.getString(i) ?: ""
                val j = c.getColumnIndex(OpenableColumns.SIZE)
                if (j >= 0 && !c.isNull(j)) size = c.getLong(j)
            }
        }
        if (size <= 0) size = try {
            context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: -1L
        } catch (e: Exception) { -1L }
        if (size <= 0 && name.isEmpty()) return null
        // "v2" prefix: v3.6 extraction is much cleaner - force a re-read of
        // PDFs that were cached by an older version
        "v2_" + Integer.toHexString(name.hashCode() * 31 + size.toInt()) +
            "_" + Integer.toHexString(uri.hashCode())
    } catch (e: Exception) { null }

    private fun extract(context: Context, uri: Uri, maxChars: Int): String = try {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            PDDocument.load(ins).use { doc ->
                val stripper = PDFTextStripper()
                // real reading order for two-column pages, sidebars, slides
                stripper.setSortByPosition(true)
                val pages = doc.numberOfPages
                // pass 1: read and clean every page (up to the cap)
                val pageTexts = ArrayList<Pair<Int, String>>(pages)
                var total = 0
                for (p in 1..pages) {
                    stripper.setStartPage(p)
                    stripper.setEndPage(p)
                    val cleaned = clean(stripper.getText(doc))
                    if (cleaned.isNotBlank()) {
                        pageTexts.add(p to cleaned)
                        total += cleaned.length
                    }
                    if (total >= maxChars) break
                }
                if (pageTexts.isEmpty()) return@use ""
                // pass 2: lines that repeat on most pages are running
                // headers/footers - drop them so they don't poison chunks
                val linePages = HashMap<String, Int>()
                for ((_, t) in pageTexts) {
                    val seen = HashSet<String>()
                    for (l in t.lines()) {
                        val s = l.trim()
                        if (s.length in 2..60) seen.add(s)
                    }
                    for (s in seen) linePages[s] = (linePages[s] ?: 0) + 1
                }
                val n = pageTexts.size
                val junk = if (n >= 3)
                    linePages.filter { it.value * 10 >= n * 6 }.keys else emptySet()
                val sb = StringBuilder()
                for ((p, t) in pageTexts) {
                    var c = t.lines().filterNot { it.trim() in junk }.joinToString("\n")
                    c = c.replace(Regex("\\n{3,}"), "\n\n").trim()
                    if (c.isNotBlank()) {
                        sb.append(c).append("\n\n— page ").append(p).append(" —\n\n")
                    }
                    if (sb.length >= maxChars) break
                }
                var t = sb.toString()
                if (t.length > maxChars) t = t.substring(0, maxChars) + "\n[...document truncated]"
                // scanned / image-only PDF: pages exist, text doesn't
                if (doc.numberOfPages >= 2 && t.length < doc.numberOfPages * 40) ""
                else t
            }
        } ?: ""
    } catch (e: Exception) { "" }

    /**
     * Cleans one raw PDF page: joins hyphenated line breaks ("edu-\ncation"
     * -> "education"), drops page-number-only lines, bare URLs, emails,
     * "page X of Y" lines and dot/dash leaders, collapses whitespace.
     */
    private fun clean(page: String): String {
        var t = page.replace(Regex("([a-z])-\n([a-z])"), "$1$2")
        t = t.lines().filterNot { l ->
            val s = l.trim()
            (s.length in 1..5 && Regex("^\\D?\\d{1,4}\\D?$").matches(s)) ||
                Regex("(?i)^(https?://|www\\.)\\S{4,}$").matches(s) ||
                Regex("^\\S+@\\S+\\.\\S{2,}$").matches(s) ||
                Regex("(?i)^pages?\\s*\\d+(\\s*(of|/)\\s*\\d+)?$").matches(s) ||
                Regex("^[\\s.\\-\\u2013\\u2014_=*]{3,}$").matches(s)
        }.joinToString("\n")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        t = t.replace(Regex("[ \\t]{3,}"), " ")
        return t.trim()
    }
}
