package org.nova.ncie.knowledge

/**
 * v0.9.5 (v8.5.0 stage 1): HTML to plain text, for fetching web pages.
 * Deterministic, no parser dependency, no script execution - the fetcher
 * hands over raw HTML and gets back clean reading text.
 *
 * What it does:
 *  - script/style/head/noscript/nav/aside/footer blocks and HTML comments
 *    are dropped whole (semantic chrome never reaches the reader)
 *  - block-level tags become paragraph breaks; the rest of the tags vanish
 *  - [codeBlocks] pulls every <pre> section out VERBATIM - entity-decoded,
 *    inner tags stripped, indentation preserved - so fetched code examples
 *    land in the knowledge base exactly as written
 *  - entities are decoded (the named five plus numeric and hex forms)
 *  - output is capped ([maxChars]) and whitespace-collapsed - a web page
 *    is megabytes of chrome around a few kilobytes of content
 */
object HtmlText {

    /** The significant text of a page, whitespace-cleaned and capped. */
    fun toText(html: String, maxChars: Int = 6000): String {
        var s = html
        // drop whole non-content blocks, case-insensitively
        for (tag in listOf("script", "style", "head", "noscript", "nav", "aside", "footer")) {
            s = Regex("(?is)<" + tag + "\\b.*?</" + tag + ">").replace(s, " ")
        }
        s = Regex("(?is)<!--.*?-->").replace(s, " ")
        // block boundaries become paragraph breaks; list items get a dash
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?i)</(p|div|section|article|li|tr|h[1-6]|blockquote|pre|table|ul|ol)>").replace(s, "\n")
        s = Regex("(?i)<li\\b[^>]*>").replace(s, "- ")
        // everything else inside angle brackets goes
        s = Regex("(?s)<[^>]*>").replace(s, " ")
        s = decodeEntities(s)
        // whitespace hygiene: trim line ends, collapse spaces, cap blank runs
        val lines = s.split('\n').map { it.trim().replace(Regex("[ \t]{2,}"), " ") }
        val out = StringBuilder()
        var blank = 0
        for (l in lines) {
            if (l.isEmpty()) { blank++; if (blank <= 1) out.append('\n') }
            else { blank = 0; out.append(l).append('\n') }
        }
        var text = out.toString().trim()
        if (text.length > maxChars) text = text.substring(0, maxChars) + "…"
        return text
    }

    /** Every <pre> block, verbatim: entities decoded, tags stripped,
     *  indentation kept. At most 6 blocks, 2000 chars each. */
    fun codeBlocks(html: String): List<String> {
        val out = ArrayList<String>()
        for (m in Regex("(?is)<pre\\b[^>]*>(.*?)</pre>").findAll(html)) {
            var code = Regex("(?s)<[^>]*>").replace(m.groupValues[1], " ")
            code = decodeEntities(code)
            code = code.trim()
            if (code.length > 2000) code = code.substring(0, 2000)
            if (code.isNotEmpty()) out.add(code)
            if (out.size >= 6) break
        }
        return out
    }

    /** The named five (the only ones real text needs), the numeric and
     *  hex forms, and a trailing-ampersand safety pass. */
    internal fun decodeEntities(s: String): String {
        var r = s
        r = Regex("&#x([0-9a-fA-F]{1,4});").replace(r) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        r = Regex("&#(\\d{1,5});").replace(r) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        val amp = "&"
        val named = listOf(
            amp + "amp;" to amp,
            amp + "lt;" to "<",
            amp + "gt;" to ">",
            amp + "quot;" to "\"",
            amp + "#39;" to "'",
            amp + "nbsp;" to " ",
        )
        for ((e, c) in named) r = r.replace(e, c)
        return r
    }
}
