package org.nova.ncie.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v9.33.69: the HTML-to-text converter gets its first unit tests.
 *
 * [HtmlText] turns a fetched web page into the clean reading text NOVA stores
 * and quotes. It is pure, deterministic string work (no parser, no script
 * execution), and it runs on EVERY online lookup - [toText] for the body,
 * [codeBlocks] for the <pre> sections kept verbatim, [decodeEntities] for the
 * entities. A silent regression here either dumps page chrome into the
 * knowledge base or mangles the text the model then answers from, with nothing
 * failing anywhere. The behaviour is pinned directly.
 *
 * Pure JVM, no Android calls - run with `gradle testDebugUnitTest`
 * (CI runs it on every pull request).
 */
class HtmlTextTest {

    // ---- toText: chrome dropped, tags stripped, text kept ----

    @Test
    fun tagsAreStrippedAndTextKept() {
        assertEquals("Hello world", HtmlText.toText("<p>Hello <b>world</b></p>"))
    }

    @Test
    fun chromeBlocksAreDroppedWhole() {
        assertEquals(
            "Real text here",
            HtmlText.toText(
                "<html><head><title>X</title></head><body>" +
                    "<script>var x=1;</script><p>Real text here</p></body></html>"))
    }

    @Test
    fun aBreakBecomesANewline() {
        assertEquals("one\ntwo", HtmlText.toText("one<br>two"))
    }

    @Test
    fun listItemsBecomeDashes() {
        assertEquals("- alpha\n- beta", HtmlText.toText("<ul><li>alpha</li><li>beta</li></ul>"))
    }

    @Test
    fun commentsAreDropped() {
        assertEquals("a b", HtmlText.toText("a<!-- c -->b"))
    }

    @Test
    fun aRunOfBlankLinesCollapsesToOne() {
        assertEquals("a\n\nb", HtmlText.toText("a\n\n\n\nb"))
    }

    @Test
    fun entitiesAreDecodedInTheBody() {
        assertEquals(
            "a & b <tag> 'q' A",
            HtmlText.toText("<p>a &amp; b &lt;tag&gt; &#39;q&#39; &#x41;</p>"))
    }

    @Test
    fun outputIsCappedAtMaxChars() {
        assertEquals("xxxxxxxxxx\u2026", HtmlText.toText("x".repeat(20), 10))
    }

    // ---- codeBlocks: <pre> kept, inner tags gone, entities decoded ----

    @Test
    fun aPreBlockIsExtractedAndTrimmed() {
        assertEquals(listOf("code  here"), HtmlText.codeBlocks("<pre>  code  here  </pre>"))
    }

    @Test
    fun innerTagsAreStrippedAndEntitiesDecodedInCode() {
        assertEquals(listOf("a < b"), HtmlText.codeBlocks("<pre><code>a &lt; b</code></pre>"))
    }

    // ---- decodeEntities: named, numeric and hex forms ----

    @Test
    fun theNamedEntitiesDecode() {
        assertEquals("&<>\"' ", HtmlText.decodeEntities("&amp;&lt;&gt;&quot;&#39;&nbsp;"))
    }

    @Test
    fun numericAndHexEntitiesDecode() {
        assertEquals("AB", HtmlText.decodeEntities("&#65;&#x42;"))
    }
}
