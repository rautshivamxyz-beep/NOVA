package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.64: the reply-deduplication helpers get their first unit tests.
 *
 * These pure string functions run on EVERY auto-continued reply and on every
 * long document/notes summary (MainActivity 1472-1481, 1925-2159). They
 * decide whether a continuation repeats the previous text, whether a cut
 * sentence is re-emitted, and whether a summary restates itself. A silent
 * regression here is felt as duplicated paragraphs in the chat with nothing
 * failing anywhere, so the behaviour is pinned directly.
 *
 * Pure JVM, no Android calls - run with `gradle testDebugUnitTest`
 * (CI runs it on every pull request).
 */
class ChatTextTest {

    // ---- v9.33.77 docSearchIn: the answer must survive the budget ----
    //
    // docSearchIn builds the "from my notes" window. Both failures below were
    // real: a 450-character note whose answering sentence is its LAST
    // paragraph returned 194 characters of filler at a 300-character budget
    // and 0 characters at a smaller one - the notes were dropped and the
    // model answered from imagination with nothing reported anywhere.

    private val overview =
        "The chapter opens with a general overview of the topic and its history. "

    @Test
    fun theAnsweringParagraphIsNotDroppedForEarlierFiller() {
        val p0 = "Photosynthesis appears again in the summary below. " + overview.trim()
        val p1 = "Photosynthesis is also mentioned in the review questions. " + overview.trim()
        val answer = "Photosynthesis converts light into chemical energy."
        val doc = listOf(p0, p1, answer).joinToString("\n\n")
        val win = docSearchIn(doc, "photosynthesis", 300)
        assertTrue("the window dropped the answering paragraph: " + win,
            win.contains("chemical energy"))
    }

    @Test
    fun aWindowIsNeverSilentlyEmpty() {
        // one long scoring paragraph, then the answer: the old code broke out
        // of the loop on the first paragraph and returned ""
        val long = ("Photosynthesis is covered later in this chapter. " +
            "Background context that the chapter mentions once. ".repeat(8)).trim()
        val doc = long + "\n\n" + "Photosynthesis converts light into chemical energy."
        assertTrue(docSearchIn(doc, "photosynthesis", 200).isNotEmpty())
    }

    @Test
    fun chosenParagraphsStayInDocumentOrder() {
        val first = "Photosynthesis converts light into chemical energy."
        val middle = "This paragraph is about something else entirely."
        val last = "Photosynthesis is also reviewed in the questions at the end."
        val doc = listOf(first, middle, last).joinToString("\n\n")
        // a budget that holds the two matching paragraphs but not the middle
        val win = docSearchIn(doc, "photosynthesis", 120)
        assertTrue(win.contains("chemical energy"))
        assertTrue(win.contains("questions at the end"))
        // document order, with the gap marked
        assertTrue(win.indexOf("chemical energy") < win.indexOf("questions at the end"))
        assertTrue(win.contains("[...]"))
    }

    @Test
    fun theWindowNeverExceedsItsBudget() {
        val doc = "Photosynthesis. " + "padding words that go on and on. ".repeat(200)
        val win = docSearchIn(doc, "photosynthesis", 400)
        assertTrue("window is " + win.length + " chars", win.length <= 400)
        assertTrue(win.isNotEmpty())
    }

    // ---- dropRepeatedBlocks: drop runs the continuation re-emits ----

    @Test
    fun aRepeatedRunOfThreeLinesIsDropped() {
        // the model restarted a whole 3-line section on auto-continue
        assertEquals("New", dropRepeatedBlocks("A\nB\nC", "A\nB\nC\nNew"))
    }

    @Test
    fun aSingleRepeatedLongLineIsDropped() {
        val long = "x".repeat(70)
        assertEquals("new", dropRepeatedBlocks(long, "$long\nnew"))
    }

    @Test
    fun aSingleRepeatedShortLineIsKept() {
        // one short repeated line is not enough evidence of a restart
        assertEquals(
            "Hello there friend.\nBrand new line.",
            dropRepeatedBlocks("Hello there friend.", "Hello there friend.\nBrand new line."))
    }

    // ---- stripRepeatJoin: drop the overlap at a continuation seam ----

    @Test
    fun theOverlapBetweenOldAndNewIsJoined() {
        assertEquals(
            "In conclusion, the water cycle repeats forever.",
            stripRepeatJoin("In conclusion, the water cycle", "the water cycle repeats forever."))
    }

    @Test
    fun textWithNoOverlapIsConcatenatedUnchanged() {
        assertEquals("abcdef", stripRepeatJoin("abc", "def"))
    }

    // ---- stripRepeatStart: drop a first line that echoes a stopped reply ----

    @Test
    fun aFirstLineEchoingTheStoppedReplyIsDropped() {
        assertEquals(
            "Plants make food.",
            stripRepeatStart(
                "Let me explain photosynthesis in simple terms. Plants make food.",
                "Let me explain photosynthesis in simple terms."))
    }

    @Test
    fun aFirstLineThatDoesNotEchoIsKept() {
        assertEquals(
            "Something else entirely.",
            stripRepeatStart("Something else entirely.", "Let me explain photosynthesis in simple terms."))
    }

    // ---- cutPartialLine: drop the half sentence the continuation completes ----

    @Test
    fun thePartialLastLineIsDroppedWhenTheContinuationCompletesIt() {
        assertEquals(
            "Intro line here",
            cutPartialLine(
                "Intro line here\nThis sentence is incomplete and long enough",
                "This sentence is incomplete and long enough. Now complete."))
    }

    @Test
    fun aShortLastLineIsNeverTouched() {
        assertEquals("Intro line here\nshort", cutPartialLine("Intro line here\nshort", "x"))
    }

    // ---- tail300: a word-boundary tail for progress text ----

    @Test
    fun aShortTextIsReturnedWhole() {
        assertEquals("short", tail300("short"))
    }

    @Test
    fun aLongTextIsCutAtTheNearestWordBoundary() {
        assertEquals("tail", tail300("b".repeat(350) + " tail"))
    }

    // ---- dedupeLines: exact and near repeats in summaries ----

    @Test
    fun anExactRepeatedShortLineIsDropped() {
        assertEquals("hello\nworld", dedupeLines("hello\nhello\nworld"))
    }

    @Test
    fun blankLinesSurvive() {
        assertEquals("a\n\nb", dedupeLines("a\n\nb"))
    }

    @Test
    fun aNearRepeatedLongLineIsDropped() {
        assertEquals(
            "The quick brown fox jumps over the lazy dog today",
            dedupeLines(
                "The quick brown fox jumps over the lazy dog today\n" +
                    "The quick brown fox jumps over the lazy dog now"))
    }

    // ---- v9.33.74: repetition repair ----
    // A model that loses the plot repeats one sentence until the budget runs
    // out. These pin the collapse rule, including what it must NOT touch.

    @Test
    fun aSentenceRepeatedFiveTimesCollapsesToOne() {
        val loop = ("I do not have that information. ".repeat(5)).trim()
        assertEquals("I do not have that information.", collapseRepetition(loop))
    }

    @Test
    fun twoRepeatsAreLeftAlone() {
        // emphasis, not a loop - the run must reach three before anything moves
        val t = "Wait. Wait. Then it stopped and the room went quiet for a long, long moment of silence."
        assertEquals(t, collapseRepetition(t))
    }

    @Test
    fun anAlternatingLoopCollapsesToItsFirstCycle() {
        // the classic small-model loop is two sentences trading places
        val t = ("It is on. It is off. ".repeat(5)).trim()
        assertEquals("It is on. It is off.", collapseRepetition(t))
    }

    @Test
    fun aRepeatedWordRunCollapsesToOne() {
        val t = "The answer is very very very very simple, and that is all there is to it at all."
        assertEquals(
            "The answer is very simple, and that is all there is to it at all.",
            collapseRepetition(t))
    }

    @Test
    fun aRepeatedLineRunCollapsesToOne() {
        val t = "Section one is done and dusted.\n" +
            "Section one is done and dusted.\n" +
            "Section one is done and dusted."
        assertEquals("Section one is done and dusted.", collapseRepetition(t))
    }

    @Test
    fun aFencedCodeBlockIsNeverTouched() {
        // three identical lines are normal in a snippet - collapsing them
        // would corrupt what the user asked for
        val t = "Here is the snippet you asked about, with the value set three " +
            "times in a row as you wrote it:\n```\nx = 1\nx = 1\nx = 1\n```"
        assertEquals(t, collapseRepetition(t))
    }

    @Test
    fun ordinaryTextIsUnchanged() {
        val t = "Your exam is on Monday at 9am. Bring the hall ticket and a pen. " +
            "Do not carry the phone."
        assertEquals(t, collapseRepetition(t))
    }

    @Test
    fun aShortReplyIsNeverTouched() {
        // the whole pass is skipped below 80 chars, so a terse answer cannot
        // be mangled by a false positive
        val t = "No. No. No."
        assertEquals(t, collapseRepetition(t))
    }
}
