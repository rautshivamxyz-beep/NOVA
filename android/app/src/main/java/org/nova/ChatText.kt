package org.nova

import org.nova.ncie.Tokens
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.net.Uri
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.nova.ncie.android.NcieArithmetic
import org.nova.ncie.android.NcieChat
import org.nova.ncie.android.NcieEngines
import org.nova.ncie.android.NcieGround
import org.nova.ncie.android.NcieEmbed
import org.nova.ncie.android.NcieKnowledge
import org.nova.ncie.android.NcieLearn
import org.nova.ncie.android.NcieSkills
import org.nova.ncie.android.NcieSummary
import org.nova.ncie.android.NcieTune
import org.nova.ncie.android.NcieValue
import org.nova.ncie.android.NovaEngineAdapter
import org.nova.ncie.verify.Coverage
import org.nova.ncie.android.ncieSend
import io.noties.markwon.Markwon
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale


// v9.33.58 (improvement pass): extracted from MainActivity.kt - document search,
// text cleanup and small UI helpers. Top-level `private` became `internal` so the
// class and MessageAdapter (both in this package) can still call them.

// ------------------------------------------------------ document search

internal val docStop = setOf("what", "who", "when", "where", "why", "how", "the", "and",
    "for", "are", "was", "were", "is", "does", "did", "do", "with", "about",
    "tell", "explain", "describe", "which", "that", "this", "from", "many",
    "much", "some", "give", "list", "name", "then", "than", "into", "also",
    "page", "please", "according", "document", "pdf", "notes", "show", "gimme",
    "send", "paste", "display", "want", "full", "whole", "actual", "instead")

/**
 * Finds the best parts of a document for a question. Scores EVERY
 * paragraph over the whole document (so matches near the end are found
 * too), weights rare words higher than common ones, matches word
 * beginnings ("photosynth" finds "photosynthesis") and boosts paragraphs
 * with dates / names / numbers for when / who / how-many questions. A
 * question naming a page returns exactly that page.
 */
fun docSearchIn(doc: String, query: String, maxChars: Int): String {
    if (doc.length <= maxChars) return doc
    // "page 12" question - answer from exactly that page
    Regex("(?i)\\bpage\\s+(\\d{1,4})\\b").find(query)?.let { m ->
        val markers = Regex("\u2014 page (\\d+) \u2014").findAll(doc).toList()
        val mi = markers.indexOfFirst { it.groupValues[1] == m.groupValues[1] }
        if (mi >= 0) {
            // a page marker sits AFTER that page's text
            val start = if (mi == 0) 0 else markers[mi - 1].range.last + 1
            var pageText = doc.substring(start, markers[mi].range.first).trim()
            if (pageText.length > maxChars) pageText = pageText.substring(0, maxChars)
            return "(page ${m.groupValues[1]} of the document)\n$pageText"
        }
    }
    val ql = query.lowercase()
    val qw = Tokens.split(ql)
        .filter { it.length > 2 && it !in docStop }.toSet()
    if (qw.isEmpty()) return doc.take(maxChars)
    val paras = doc.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
    val lower = paras.map { it.lowercase() }
    // rarity weights: a word that appears in few paragraphs counts more
    val weights = HashMap<String, Double>()
    val prefixes = HashMap<String, Regex>()
    for (w in qw) {
        var c = 0
        for (pl in lower) if (w in pl) c++
        if (c > 0) { weights[w] = 1.0 / c + 0.05; continue }
        if (w.length >= 6) {   // no full hit - match the word beginning instead
            val pre = Regex(Regex.escape(w.substring(0, 5)))
            var pc = 0
            for (pl in lower) if (pre.containsMatchIn(pl)) pc++
            if (pc > 0) { weights[w] = 0.6 / pc + 0.05; prefixes[w] = pre }
        }
    }
    if (weights.isEmpty()) return doc.take(maxChars)
    // question-type routing
    val wantDates = Regex("\\bwhen\\b|\\byear\\b|\\bdate\\b").containsMatchIn(ql)
    val wantNames = Regex("\\bwho\\b|\\bwhom\\b").containsMatchIn(ql)
    val wantNums = Regex("\\bhow many\\b|\\bhow much\\b").containsMatchIn(ql)
    val dateRe = Regex("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b|\\b(18|19|20)\\d{2}\\b")
    val nameRe = Regex("\\b[A-Z][a-z]{2,}\\b")
    val scored = mutableListOf<Pair<Double, Int>>()
    for ((i, p) in paras.withIndex()) {
        val pl = lower[i]
        var sc = 0.0
        for ((w, wt) in weights) {
            val pre = prefixes[w]
            if (pre != null) { if (pre.containsMatchIn(pl)) sc += wt }
            else if (w in pl) sc += wt
        }
        if (sc <= 0.0) continue
        if (p.length < 80) sc *= 1.5            // headings weigh more
        if (wantDates && dateRe.containsMatchIn(p)) sc += 0.5
        if (wantNames && nameRe.findAll(p).take(3).count() >= 2) sc += 0.4
        if (wantNums && p.any { it.isDigit() }) sc += 0.4
        scored.add(sc to i)
    }
    if (scored.isEmpty()) return doc.take(maxChars)
    val best = scored.sortedByDescending { it.first }.take(12).map { it.second }.sorted()
    val out = StringBuilder()
    var last = -2
    for (i in best) {
        if (out.isNotEmpty() && i != last + 1) out.append("[...]\n")
        val p = paras[i]
        if (out.length + p.length > maxChars) break
        out.append(p).append("\n\n")
        last = i
    }
    return out.toString().trim()
}

/**
 * True for table-of-contents / references / index chunks - mostly page
 * references instead of real content. Skipping them makes summaries
 * faster and keeps them on-topic.
 */
fun isJunkChunkText(c: String): Boolean {
    val lines = c.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return true
    if (Regex("(?im)^(table of )?contents$|^references$|^bibliography$|^index$")
            .containsMatchIn(c)) return true
    var refs = 0
    for (l in lines) {
        val t = l.trim()
        if (Regex("\\.{2,}\\s*\\d{1,4}$").containsMatchIn(t) ||       // "Topic .... 12"
            Regex("^\\d{1,4}$").matches(t) ||                             // bare page number
            Regex("^[ivxlcdm]{1,7}$", RegexOption.IGNORE_CASE).matches(t)) refs++
    }
    return refs * 2 > lines.size
}

// ---------------------------------------------------------------- adapter

/** v5.4.5: follow-up questions with no keywords of their own - they mean
 *  "the same notes again", so the last grounded notes are carried forward. */
internal val FOLLOW_UP_Q = Regex("(?i)\\b(explain (it|that|this)|in more detail|more detail|tell me more|explain more|elaborate|go on)\\b")

// v7.3: bare greetings / smalltalk - matched on the WHOLE message
internal val SMALLTALK_REGEX = Regex(
    "(?i)^[\\s']*(hi+|hey+|hello+|yo|sup|namaste|hola|good (morning|afternoon|evening|night)" +
        "|how are (you|u)|how r (you|u)|what'?s up|how'?s it going)[\\s.!~?]*$"
)

// v7.4: the four follow-up chip prompts - recognized so they never hit
// the QA cache (stale-answer replay) or the notes summarizer
internal val CHIP_PROMPTS = setOf(
    "Explain that more simply, like I am 12 years old.",
    "Give me one clear real-life example of that.",
    "Quiz me on this topic.",
    "Summarize that in exactly 3 short bullet points."
)
/**
 * Removes hidden model "thinking" blocks so only the actual answer is shown,
 * spoken and saved. While a block is still open (streaming), everything from
 * the opening tag on is hidden.
 *
 * v9.13.4 "Thinking Tags Hardened": every tag variant a GGUF may use for
 * hidden reasoning is stripped - Qwen3 / MiniCPM use  thinking, some models
 * use <thinking> - so no chain-of-thought can ever leak into the reply.
 */
internal val THINK_TAGS = listOf("think", "thinking")

fun stripThinking(s: String): String {
    var out = s
    for (tag in THINK_TAGS) {
        val open = "<" + tag + ">"
        val close = "</" + tag + ">"
        out = out.replace(
            Regex("(?s)" + java.util.regex.Pattern.quote(open) +
                ".*?" + java.util.regex.Pattern.quote(close)), "")
        val i = out.indexOf(open)
        if (i >= 0) out = out.substring(0, i)
    }
    return out
}
internal val CODE_BLOCK = Regex("(?s)```[a-zA-Z0-9+#.-]*\\n?(.*?)```")

/** Markdown stripped to plain text - clean for pasting as a prompt.
 *  Code blocks and inline code are stashed first so their underscores and
 *  asterisks (like __init__ or x * y) survive the markdown stripping. */
fun plainText(s: String): String {
    val stash = mutableListOf<String>()
    var t = CODE_BLOCK.replace(s) {
        stash.add(it.groupValues[1]); "\u0000${stash.size - 1}\u0000"
    }
    t = Regex("`[^`\\n]+`").replace(t) {
        stash.add(it.value.substring(1, it.value.length - 1)); "\u0000${stash.size - 1}\u0000"
    }
    t = t
        .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_~]+"), "")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^>\\s?"), "")
        .replace(Regex("(?m)^[-*+] "), "- ")
        .trim()
    for (i in stash.indices) t = t.replace("\u0000$i\u0000", stash[i])
    return t
}

/**
 * Drops blocks from a continuation that merely repeat content already in
 * the existing reply - the model often restarts a whole section when a
 * cut-off reply is auto-continued. Runs of 3+ lines (or any 60+ char
 * line) that already appear in the old text are removed; genuinely new
 * lines are kept.
 */
internal fun dropRepeatedBlocks(old: String, added: String): String {
    val oldSet = HashSet<String>()
    for (l in old.lines()) oldSet.add(l.trim().replace(Regex("\\s+"), " "))
    val out = ArrayList<String>()
    var run = ArrayList<String>()
    fun close(keep: Boolean) {
        if (keep) out.addAll(run)
        run = ArrayList()
    }
    for (raw in added.lines()) {
        val n = raw.trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty() || oldSet.contains(n)) run.add(raw)
        else {
            val big = run.any { it.trim().length >= 60 }
            close(!(run.size >= 3 || big))
            out.add(raw)
        }
    }
    val big = run.any { it.trim().length >= 60 }
    close(!(run.size >= 3 || big))
    return out.joinToString("\n")
}

/**
 * When a reply was cut mid-sentence and the continuation repeats that
 * sentence in full, keep only the complete version: the half line at the
 * end of the old text is dropped.
 */
internal fun cutPartialLine(old: String, added: String): String {
    val lines = old.trimEnd().split("\n").toMutableList()
    if (lines.size < 2) return old
    val last = lines.last().trim()
    val firstNew = added.trim().lines().firstOrNull()?.trim() ?: return old
    if (last.length >= 40 && (last.lastOrNull() ?: ' ') !in ".!?\"'*" &&
        (firstNew.startsWith(last) || last.startsWith(firstNew.take(40))))
        lines.removeAt(lines.size - 1)
    return lines.joinToString("\n")
}

/** Last ~300 chars of a progress text, starting at a word boundary
 *  so the first word is not cut in half ("chieving independence"). */
internal fun tail300(t: String): String {
    val tail = t.takeLast(300)
    if (t.length <= 300) return tail
    val i = tail.indexOfFirst { it == ' ' || it == '\n' }
    return if (i >= 0) tail.substring(i + 1) else tail
}

/** True when a final summary derailed: too short, scratchpad "Step 1:"
 * style, or almost no keyword overlap with the source summaries (the model
 * wandered off-topic - typically a context-window overflow). */
internal fun looksDerailed(t: String, source: String): Boolean {
    if (t.length < 30) return true
    if (Regex("(?i)step\\s*[0-9]+\\s*[:.]").containsMatchIn(t)) return true
    val src = Tokens.split(source).filter { it.length > 4 }.toHashSet()
    val out = Tokens.split(t).filter { it.length > 4 }
    if (src.isEmpty() || out.size < 10) return false
    val hit = out.count { it in src }
    return hit * 10 < out.size * 3
}

/**
 * v9.13.1 "Honest Summaries": deterministic fact verification for study
 * summaries. The LLM only generates - the checks are pure code, fully
 * local, no new dependencies. Every line carrying a factual marker (a
 * 4-digit year, a "June 12"/"12 June" style date, or digits with a unit)
 * is checked against the SOURCE text the summary was built from: the
 * exact marker string must appear there. Unmatched lines are KEPT but
 * flagged as unverified - nothing is silently deleted and the summary
 * is never rewritten by another model pass. If more than half of the
 * marker lines are unverifiable, one warning line is prepended.
 */
internal fun verifySummaryFacts(summary: String, source: String): String {
    val ordRe = Regex("(?i)(?<=\\d)(st|nd|rd|th)\\b")
    fun norm(s: String) =
        ordRe.replace(s.lowercase(), "").replace(Regex("\\s+"), " ")
    val src = norm(source)
    val dateRe = Regex("(?i)\\b(?:january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2}\\b|\\b\\d{1,2}\\s+(?:january|february|march|april|may|june|july|august|september|october|november|december)\\b")
    val yearRe = Regex("\\b(?:1[0-9]{3}|20[0-9]{2})\\b")
    val unitRe = Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:%|percent|km|cm|mm|kg|mg|ml|million|billion|thousand|crore|lakh|years?|days?|hours?|minutes?|seconds?|people|students|feet|foot|metres?|meters?|inches?|months?|weeks?)\\b")
    // exact-token containment: the marker must appear in the source
    // with no digit glued to either end, so "august 1" does NOT match
    // inside "august 1944" and "1944" does not match inside "21944"
    fun inSrc(mark: String): Boolean {
        var i = src.indexOf(mark)
        while (i >= 0) {
            val beforeOk = i == 0 || !src[i - 1].isDigit()
            val after = i + mark.length
            val afterOk = after >= src.length || !src[after].isDigit()
            if (beforeOk && afterOk) return true
            i = src.indexOf(mark, i + 1)
        }
        return false
    }
    val out = ArrayList<String>()
    var markerLines = 0
    var unverified = 0
    for (line in summary.lines()) {
        val marks = ArrayList<String>()
        for (m in dateRe.findAll(line)) marks.add(m.value)
        for (m in yearRe.findAll(line)) marks.add(m.value)
        for (m in unitRe.findAll(line)) marks.add(m.value)
        if (marks.isEmpty()) { out.add(line); continue }
        markerLines++
        if (marks.all { inSrc(norm(it)) }) out.add(line)
        else { unverified++; out.add(line.trimEnd() + " (unverified)") }
    }
    var res = out.joinToString("\n")
    if (markerLines > 0 && unverified * 2 > markerLines)
        res = "Warning: many details in this summary could not be matched to your notes \u2014 treat with care.\n\n" + res
    return res
}

/**
 * v9.13.1 "Honest Summaries": deterministic meta-commentary stripper.
 * Tiny models close their study summaries with lines ABOUT the summary
 * ("This summary covers all the key information...", "Note: ...").
 * TRAILING blank/meta lines are dropped, and a leading "(from ...)"
 * label the model echoed is dropped too (the caller adds the real
 * source label). Pure string matching - no model involved, and the
 * "(from <file>)" label itself is kept.
 */
internal fun stripSummaryMeta(text: String): String {
    val metaRe = Regex("(?i)this summary covers|providing a comprehensive overview|in summary, this|covers all the key|the summary above")
    val leadRe = Regex("(?i)^\\s*(note|disclaimer)\\s*:")
    val lines = text.lines().toMutableList()
    while (lines.isNotEmpty()) {
        val last = lines[lines.size - 1].trim()
        if (last.isNotEmpty() && !metaRe.containsMatchIn(last) &&
            !leadRe.containsMatchIn(last)) break
        lines.removeAt(lines.size - 1)
    }
    var out = lines.joinToString("\n").trim()
    // a leading "(from ...)" the model echoed - the caller is about to
    // add the real source label, so drop the duplicate
    out = Regex("(?i)^\\(from[^)]*\\)\\s*(?:\\r?\\n)+").replace(out, "")
    return out
}


/**
 * Removes repeated lines from a summary - tiny 1B models often restate
 * the same sentence in several section summaries. Exact repeats are
 * always dropped; longer lines that share >= 65% of their words with an
 * earlier line are dropped too.
 */
/**
 * v5.4: detects a derailed CHAT answer - scratchpad steps or text with
 * almost nothing in common with the question and its notes.
 */
internal fun chatDerailed(t: String, source: String): Boolean {
    if (t.length < 25) return false
    if (Regex("(?i)step [0-9]+[.:]").containsMatchIn(t)) return true
    // overlap only makes sense against a notes-rich (grounded) prompt;
    // a plain short question shares too few words with any good answer
    if (source.length < 400) return false
    val src = Tokens.split(source).filter { it.length > 4 }.toHashSet()
    val out = Tokens.split(t).filter { it.length > 4 }
    if (src.isEmpty() || out.size < 12) return false
    val hit = out.count { it in src }
    return hit * 10 < out.size * 2
}

internal fun dedupeLines(t: String): String {
    val seen = ArrayList<Set<String>>()
    val out = ArrayList<String>()
    for (raw in t.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) { out.add(""); continue }
        val words = Tokens.split(line)
            .filter { it.length > 3 }.toSet()
        var dup = false
        if (line.length >= 40 && words.size >= 4) {
            for (prev in seen) {
                var inter = 0
                for (w in words) if (w in prev) inter++
                val j = inter.toDouble() / (words.size + prev.size - inter)
                // near-repeat: most of this line's words already appeared
                val contained = inter.toDouble() / words.size
                if (j >= 0.65 || (words.size >= 6 && contained >= 0.6)) { dup = true; break }
            }
        } else if (out.any { it.trim() == line }) {
            dup = true
        }
        if (!dup) {
            out.add(raw)
            if (line.length >= 40 && words.size >= 4) seen.add(words)
        }
    }
    return out.joinToString("\n")
}

/** If the added text starts by repeating the end of the old text, drop the overlap. */
internal fun stripRepeatJoin(old: String, added: String): String {
    val a = old.trimEnd()
    val b = added.trimStart()
    val max = minOf(400, b.length)
    for (k in max downTo 10) {
        val head = b.take(k).trim()
        if (head.length >= 10 && a.endsWith(head)) {
            var rest = b.substring(k).trimStart()
            if (rest.startsWith(".")) rest = rest.substring(1).trimStart()
            return a + (if (rest.isNotEmpty()) " " + rest else "")
        }
    }
    return old + added
}

/** Drops the first line of a new reply when it just repeats the last
 *  line of a previous, stopped reply. */
internal fun stripRepeatStart(newText: String, prevText: String): String {
    var last = prevText.lines().map { it.trim() }.lastOrNull { it.isNotBlank() }
        ?: return newText
    last = last.removeSuffix("⏹").trim()
    if (last.length < 12) return newText
    val t = newText.trimStart()
    if (!t.startsWith(last)) return newText
    var rest = t.substring(last.length).trimStart()
    if (rest.startsWith(".")) rest = rest.substring(1).trimStart()
    return rest.ifEmpty { newText }
}

internal fun copyToClipboard(ctx: Context, text: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("NOVA", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        // some devices (e.g. MIUI) block clipboard access - never crash,
        // let the user copy manually from a dialog instead
        AlertDialog.Builder(ctx)
            .setTitle("Copy manually")
            .setMessage(if (text.length > 4000) text.take(4000) + "\n…" else text)
            .setPositiveButton("Close", null)
            .show()
    }
}


/** v7.6.5: pressed-state feedback for custom-drawn controls. Wraps any
 *  background (or none) in a bounded ripple tinted with the theme accent;
 *  radiusPx adds a round mask so icon-only buttons ripple in their own
 *  shape instead of a rectangle. */
internal fun rippleOverlay(
    bg: android.graphics.drawable.Drawable?,
    radiusPx: Float? = null,
): android.graphics.drawable.RippleDrawable {
    val tint = android.content.res.ColorStateList.valueOf(Color.argb(
        46, Color.red(NovaTheme.accent), Color.green(NovaTheme.accent), Color.blue(NovaTheme.accent)))
    return android.graphics.drawable.RippleDrawable(
        tint, bg,
        if (radiusPx == null) null
        else GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radiusPx })
}

