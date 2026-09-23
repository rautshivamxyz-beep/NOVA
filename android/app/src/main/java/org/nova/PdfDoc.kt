package org.nova

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads text out of PDF files for NOVA's document chat.
 *
 * Speed: cleaned text is cached per file, so opening the same PDF a second
 * time is instant (no re-parse, no re-OCR).
 *
 * Logic: extraction is page-aware (each page ends with a "- page N -"
 * marker, so "what's on page 12" works) and position-sorted, so
 * two-column textbook pages and slides come out in real reading order
 * instead of scrambled halves. Each page is cleaned - hyphenated line
 * breaks are joined, page-number/URL/email/leader lines are dropped -
 * and lines that repeat on most pages (running headers and footers like
 * "sst notes | Page 4" or a website banner) are removed, because they
 * pollute every chunk the model later sees.
 *
 * v5.0: scanned, image-only PDFs are now READ instead of rejected. Pages
 * are rendered and run through on-device OCR (ML Kit text recognition,
 * bundled with the app - still fully offline, no internet). Two-column
 * scans come out in proper reading order: when the lines clearly split
 * into a left half and a right half that do not overlap, the whole left
 * column is emitted first, then the right. Small models answer much
 * better from clean text.
 */
object PdfDoc {

    /** How much full text to keep in the cache (about a 600-page book). */
    private const val CACHE_CAP = 400_000

    /** OCR is slow (~1-2 s per page) - cap how many pages we scan. */
    private const val OCR_PAGE_CAP = 80

    /**
     * Extracts (and caches) up to [maxChars] characters of clean text.
     * [onProgress] fires once per OCR'd page (1-based) so the UI can show
     * "reading with OCR - page 5/40...". It never fires for normal text
     * PDFs (they parse in seconds).
     */
    suspend fun extractText(
        context: Context,
        uri: Uri,
        maxChars: Int = 250_000,
        onProgress: ((page: Int, total: Int) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val key = cacheKey(context, uri)
            ?: return@withContext extract(context, uri, maxChars, onProgress)
        val cache = File(File(context.filesDir, "pdf_cache").apply { mkdirs() }, key)
        if (cache.exists()) {
            val t = try { cache.readText() } catch (e: Exception) { "" }
            if (t.isNotBlank()) {
                return@withContext if (t.length > maxChars)
                    t.substring(0, maxChars) + "\n[...document truncated]" else t
            }
        }
        val text = extract(context, uri, maxChars, onProgress)
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
        // "v4" prefix: v5.2 rebuilds table/two-column reading order on
        // text PDFs - force a re-read of PDFs cached by older versions
        "v4_" + Integer.toHexString(name.hashCode() * 31 + size.toInt()) +
            "_" + Integer.toHexString(uri.hashCode())
    } catch (e: Exception) { null }

    private suspend fun extract(
        context: Context,
        uri: Uri,
        maxChars: Int,
        onProgress: ((page: Int, total: Int) -> Unit)?
    ): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                PDDocument.load(ins).use { doc ->
                    val stripper = GapStripper()
                    // real reading order for two-column pages, sidebars, slides
                    stripper.setSortByPosition(true)
                    val pages = doc.numberOfPages
                    // pass 1: read and clean every page (up to the cap)
                    var pageTexts = ArrayList<Pair<Int, String>>(pages)
                    var total = 0
                    for (p in 1..pages) {
                        stripper.setStartPage(p)
                        stripper.setEndPage(p)
                        stripper.words.clear()
                        stripper.getText(doc)
                        val cleaned = clean(buildPageText(stripper.words))
                        if (cleaned.isNotBlank()) {
                            pageTexts.add(p to cleaned)
                            total += cleaned.length
                        }
                        if (total >= maxChars) break
                    }
                    // scanned / image-only PDF: pages exist, text doesn't.
                    // Read it with on-device OCR instead of giving up.
                    if (pageTexts.isEmpty() ||
                        (doc.numberOfPages >= 2 && total < doc.numberOfPages * 40)
                    ) {
                        pageTexts = ocrPages(doc, maxChars, onProgress)
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
                    t
                }
            } ?: ""
        } catch (e: Exception) { "" }
    }

    /**
     * Renders each page and runs offline OCR over it. Progress is reported
     * per page because a scanned textbook takes minutes on first read
     * (later reads come from the cache instantly).
     */
    private suspend fun ocrPages(
        doc: PDDocument,
        maxChars: Int,
        onProgress: ((page: Int, total: Int) -> Unit)?
    ): ArrayList<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val n = minOf(doc.numberOfPages, OCR_PAGE_CAP)
        if (n <= 0) return out
        val renderer = PDFRenderer(doc)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            var total = 0
            for (p in 0 until n) {
                onProgress?.invoke(p + 1, n)
                try {
                    val bmp = renderer.renderImageWithDPI(p, 150f)
                    val page = ocrBitmap(recognizer, bmp)
                    val w = bmp.width
                    bmp.recycle()
                    val cleaned = readingOrder(page, w)
                    if (cleaned.isNotBlank()) {
                        out.add(p + 1 to cleaned)
                        total += cleaned.length
                    }
                    if (total >= maxChars) break
                } catch (e: Exception) {
                    // one unreadable page should not kill the whole read
                }
            }
        } finally {
            recognizer.close()
        }
        return out
    }

    /** Runs the recognizer on one page bitmap. */
    private suspend fun ocrBitmap(recognizer: TextRecognizer, bmp: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    /**
     * Rebuilds reading order for two-column scans. When the recognized
     * lines clearly split into a left half and a right half that do not
     * overlap horizontally, emit the whole left column first, then the
     * right - instead of interleaving the two.
     */
    private fun readingOrder(t: Text, pageWidth: Int): String {
        val lines = ArrayList<Pair<String, Rect?>>()
        for (b in t.textBlocks) for (l in b.lines) lines.add(l.text to l.boundingBox)
        if (lines.size < 12 || pageWidth <= 0) return lines.joinToString("\n")
        val mid = pageWidth / 2
        val left = lines.filter { (it.second?.centerX() ?: mid) < mid }
        val right = lines.filter { (it.second?.centerX() ?: mid) >= mid }
        if (left.size < 6 || right.size < 6) return lines.joinToString("\n")
        val maxLeftRight = left.mapNotNull { it.second?.right }.maxOrNull() ?: 0
        val minRightLeft = right.mapNotNull { it.second?.left }.minOrNull() ?: pageWidth
        return if (maxLeftRight <= minRightLeft + pageWidth / 20)
            (left.map { it.first } + right.map { it.first }).joinToString("\n")
        else lines.joinToString("\n")
    }

    /** A newline string, spelled without escape sequences. */
    private val NL = 10.toChar().toString()

    /**
     * One word from the PDF text layer with its position: x range, baseline
     * and the word's text.
     */
    private class WordW(val x0: Float, val x1: Float, val y: Float, val t: String)

    /**
     * A PDFTextStripper that keeps every word's position while the text is
     * being read, so pages whose lines carry a wide internal gap (two-column
     * text or side-by-side table cells) can be rebuilt in true reading order
     * afterwards (buildPageText) instead of left and right interleaved on
     * every line ("Flip Flip / Horizontally Vertically").
     */
    private class GapStripper : PDFTextStripper() {
        val words = ArrayList<WordW>()
        override fun writeString(text: String, positions: List<TextPosition>) {
            var x0 = 0f; var x1 = 0f; var y = 0f
            val sb = StringBuilder()
            for (p in positions) {
                val u = p.unicode ?: continue
                val px0 = p.xDirAdj
                val px1 = px0 + p.widthDirAdj
                if (sb.isNotEmpty()) {
                    // a space, a horizontal jump or a baseline change
                    // all end the current word
                    if (u == " " || px0 - x1 > 1.5f || Math.abs(p.yDirAdj - y) > 2.5f) {
                        words.add(WordW(x0, x1, y, sb.toString()))
                        sb.setLength(0)
                        if (u == " ") continue
                    }
                }
                if (sb.isEmpty()) { x0 = px0; x1 = px1; y = p.yDirAdj }
                else if (px1 > x1) x1 = px1
                sb.append(u)
            }
            if (sb.isNotEmpty()) words.add(WordW(x0, x1, y, sb.toString()))
        }
    }

    /**
     * Rebuilds one page's text from the placed words. Words are grouped
     * into lines by baseline; when most lines contain a wide internal gap
     * (a two-column layout or a side-by-side table), all left-hand segments
     * are emitted first, then the right-hand ones - instead of interleaving
     * left and right text on every single line.
     */
    private fun buildPageText(words: List<WordW>): String {
        if (words.isEmpty()) return ""
        val sorted = words.sortedWith(compareBy({ it.y }, { it.x0 }))
        // group words into lines by baseline (3pt tolerance)
        val lines = ArrayList<List<WordW>>()
        var cur = ArrayList<WordW>()
        var curY = 0f
        for (w in sorted) {
            if (cur.isEmpty()) { curY = w.y; cur.add(w); continue }
            if (Math.abs(w.y - curY) <= 3.0f) cur.add(w)
            else { lines.add(cur); cur = ArrayList(); curY = w.y; cur.add(w) }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        // split every line into segments at gaps wider than 6% of the page
        val width = words.maxOf { it.x1 }
        val gapMin = width * 0.06f
        val segLines = ArrayList<List<List<WordW>>>()
        var wideLines = 0
        for (ln in lines) {
            val segs = ArrayList<List<WordW>>()
            var seg = ArrayList<WordW>()
            for (i in ln.indices) {
                if (i > 0 && ln[i].x0 - ln[i - 1].x1 > gapMin) {
                    segs.add(seg); seg = ArrayList()
                }
                seg.add(ln[i])
            }
            if (seg.isNotEmpty()) segs.add(seg)
            if (segs.size >= 2) wideLines++
            segLines.add(segs)
        }
        // mostly gapless page: plain top-to-bottom text
        if (wideLines * 10 < lines.size * 6) {
            return lines.joinToString(NL) { ln -> ln.joinToString(" ") { it.t } }
        }
        // find the split line: the median of all wide-gap midpoints
        val mids = ArrayList<Float>()
        for (segs in segLines) for (i in 1 until segs.size)
            mids.add((segs[i - 1].last().x1 + segs[i].first().x0) / 2f)
        mids.sort()
        val splitX = mids[mids.size / 2]
        val left = StringBuilder()
        val right = StringBuilder()
        for (segs in segLines) {
            for (s in segs) {
                val mid = (s.first().x0 + s.last().x1) / 2f
                val dst = if (mid < splitX) left else right
                if (dst.isNotEmpty()) dst.append(NL)
                dst.append(s.joinToString(" ") { it.t })
            }
        }
        return if (right.isEmpty()) left.toString()
        else left.toString() + NL + NL + right.toString()
    }

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
