package org.nova.ncie.android

import android.content.Context
import org.nova.WikiCore
import java.io.File

/**
 * v9.5.0 "Document Grounding": strict answers from the user's OWN
 * material, with sources. "from my notes: <question>" triggers a
 * deep-read retrieval over every stored source - Knowledge documents
 * plus offline wiki articles - and grounds the model's answer in the
 * best-matching chunks, so nothing is invented:
 *
 *  - [retrieve] splits each source into ~1500-char chunks (paragraph
 *    boundaries preferred), scores every chunk by case-insensitive
 *    keyword overlap with the question (the kernel analyzer's keywords,
 *    the same normalization the notes search uses), keeps the top 2
 *    chunks per source and the top 4 across sources. Only chunks that
 *    actually match a keyword come back - an empty list means the
 *    user's material says nothing about the question.
 *  - [groundPrompt] builds the deterministic prompt: answer ONLY from
 *    the retrieved text, or reply exactly "Not in your notes", and
 *    mention the source.
 *  - [docsList] / [wikiNames] list what the user has, names only.
 *
 * Fully local and deterministic, no new dependency: retrieval is pure
 * string work over files the app already owns, and the LLM (already
 * loaded in-app) is only used for the final grounded answer - the
 * NcieTutor pattern (NovaEngineAdapter.generate on Dispatchers.IO).
 */
object NcieGround {

    /** The question's own stopwords - the same words the wiki search
     *  drops, so scoring counts content terms only ("what is the
     *  ozone layer" scores on ozone and layer, not on what and the). */
    private val STOP = setOf("what", "who", "when", "where", "why", "how", "the", "and",
        "for", "are", "was", "were", "is", "does", "did", "do", "with", "about",
        "tell", "explain", "describe", "which", "that", "this", "from", "many",
        "much", "some", "give", "list", "name", "then", "than", "into", "also",
        "my", "notes", "note", "documents", "document")

    /** The question's significant terms: the kernel analyzer's keywords
     *  (NcieKnowledge.keyTerms - the retrieval layer's existing helper)
     *  minus the question stopwords above. */
    internal fun questionTerms(question: String): List<String> =
        NcieKnowledge.keyTerms(question).filter { it !in STOP }

    /** All knowledge documents + wiki articles the user has, names only,
     *  in store order (Knowledge first, then wiki). */
    fun docsList(ctx: Context): List<String> {
        val names = ArrayList<String>()
        for (d in NcieKnowledge.docs(ctx)) names.add(d.first)
        names.addAll(wikiNames(ctx))
        return names
    }

    /** The stored wiki article titles (done.txt, the same list
     *  NcieTutor.findSource walks), or empty when wiki is not ready. */
    fun wikiNames(ctx: Context): List<String> = try {
        val done = File(ctx.filesDir, "wiki").resolve("done.txt")
        if (done.exists()) done.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        else emptyList()
    } catch (e: Exception) { emptyList() }

    /** Deep-read retrieval: the top (sourceName, chunkText) pairs for a
     *  question - top 2 keyword-matching chunks per source, top 4 across
     *  sources, best score first. Empty when nothing matches at all. */
    fun retrieve(ctx: Context, question: String): List<Pair<String, String>> {
        val terms = questionTerms(question)
        if (terms.isEmpty()) return emptyList()
        val best = ArrayList<Triple<Int, String, String>>()   // score, source, chunk
        for (src in sources(ctx)) {
            val perSource = ArrayList<Pair<Int, String>>()
            for (chunk in chunksOf(src.text)) {
                // the notes search's normalization, verbatim: lowercase,
                // non-alphanumerics collapsed to spaces, whole-word match
                val norm = " " + chunk.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
                var score = 0
                for (t in terms) if (norm.contains(" " + t + " ")) score++
                if (score > 0) perSource.add(score to chunk)
            }
            perSource.sortByDescending { it.first }
            for (i in 0 until minOf(2, perSource.size))
                best.add(Triple(perSource[i].first, src.name, perSource[i].second))
        }
        best.sortByDescending { it.first }
        return best.take(4).map { it.second to it.third }
    }

    /** The deterministic grounded prompt: the model sees the question and
     *  ONLY the retrieved chunks, and must own up when they do not
     *  contain the answer. */
    fun groundPrompt(question: String, chunks: List<Pair<String, String>>): String =
        "Answer using ONLY the TEXT below. If the answer is not in the " +
            "text, reply exactly: Not in your notes. Otherwise answer " +
            "briefly and mention which source it came from. TEXT: " +
            chunks.joinToString("\n") { it.second + " (Source: " + it.first + ")" } +
            "\n\nQuestion: " + question

    /** ~1500-char chunks, cut at paragraph boundaries where possible -
     *  a single paragraph longer than two chunks is still split so no
     *  chunk grows unbounded. */
    internal fun chunksOf(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (para in text.split(Regex("\n+"))) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (sb.isNotEmpty() && sb.length + p.length + 1 > 1500) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(p)
            while (sb.length > 3000) {
                out.add(sb.substring(0, 1500))
                sb.delete(0, 1500)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Every stored source with its full text: Knowledge documents
     *  first, then the wiki articles (title + text via WikiCore, the
     *  same path NcieTutor.findSource uses). File work only - call
     *  from a background thread. */
    private fun sources(ctx: Context): List<Source> {
        val out = ArrayList<Source>()
        for (d in NcieKnowledge.docs(ctx)) {
            val t = NcieKnowledge.docText(ctx, d.first)
            if (t.isNotBlank()) out.add(Source(d.first, t))
        }
        try {
            if (WikiCore.isReady(ctx)) {
                WikiCore.warmUp(ctx)
                for (title in wikiNames(ctx)) {
                    val txt = WikiCore.articleText(ctx, title)
                    if (!txt.isNullOrBlank()) out.add(Source(title, txt))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    private class Source(val name: String, val text: String)
}
