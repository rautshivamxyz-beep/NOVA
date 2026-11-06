package org.nova.ncie.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v9.33.91: the answer cache's ceiling.
 *
 * The cached answers in `filesDir/summary_cache` were never removed, so the
 * directory grew for the life of the install. [NcieCache.prune] is the rule
 * that bounds it, and it is pure file work, so it is pinned here: the newest
 * entries survive, the oldest go, a directory already under the ceiling is
 * left alone, and nothing that is not a cache entry is ever touched.
 */
class NcieCacheTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("novacache").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun entry(name: String, ageSeconds: Int): File {
        val f = File(dir, name)
        f.writeText(name)
        f.setLastModified(1_600_000_000_000L + ageSeconds * 1000L)
        return f
    }

    @Test
    fun theNewestSurviveAndTheOldestGo() {
        entry("qa_a", 10)
        entry("qa_b", 20)
        entry("qa_c", 30)
        entry("qa_d", 40)
        entry("qa_e", 50)
        assertEquals(3, NcieCache.prune(dir, "qa_", 2))
        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("qa_d", "qa_e"), left)
    }

    @Test
    fun aDirectoryUnderTheCeilingIsLeftAlone() {
        entry("qa_a", 10)
        entry("qa_b", 20)
        assertEquals(0, NcieCache.prune(dir, "qa_", 200))
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun nothingThatIsNotACacheEntryIsTouched() {
        entry("qa_a", 10)
        entry("qa_b", 20)
        entry("sum_notes_1", 5)
        entry("chat_summary.txt", 1)
        NcieCache.prune(dir, "qa_", 1)
        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertTrue("got " + left, left.contains("sum_notes_1"))
        assertTrue("got " + left, left.contains("chat_summary.txt"))
        assertEquals(3, left.size)
    }

    @Test
    fun aCeilingOfZeroClearsThem() {
        entry("qa_a", 10)
        entry("qa_b", 20)
        assertEquals(2, NcieCache.prune(dir, "qa_", 0))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun aPutKeepsTheCeilingAndLeavesNoTmpFile() {
        val filesDir = Files.createTempDirectory("novafiles").toFile()
        try {
            for (i in 1..4) NcieCache.put(filesDir, "qa_$i", "answer $i", keep = 2)
            val cached = NcieCache.dir(filesDir).listFiles()!!.filter { it.name.startsWith("qa_") }
            assertEquals(2, cached.size)
            assertTrue(NcieCache.dir(filesDir).listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun theFamilyIsThePrefixBeforeTheFirstUnderscore() {
        assertEquals("qa_", NcieCache.familyOf("qa_1a2b_c3d4"))
        assertEquals("doc5_", NcieCache.familyOf("doc5_ff21_4096"))
        assertEquals("plain", NcieCache.familyOf("plain"))
    }

    @Test
    fun oneFamilyIsNotPrunedByAnother() {
        val filesDir = Files.createTempDirectory("novafiles").toFile()
        try {
            for (i in 1..3) NcieCache.put(filesDir, "doc5_$i", "summary $i", keep = 1)
            for (i in 1..3) NcieCache.put(filesDir, "qa_$i", "answer $i", keep = 3)
            val d = NcieCache.dir(filesDir)
            assertEquals(1, d.listFiles()!!.count { it.name.startsWith("doc5_") })
            assertEquals(3, d.listFiles()!!.count { it.name.startsWith("qa_") })
        } finally {
            filesDir.deleteRecursively()
        }
    }

    // ---- v9.33.97: one key rule ----

    @Test
    fun theKeyStaysInTheQaFamily() {
        assertEquals("qa_", NcieCache.familyOf(NcieCache.qaKey("what is a cell", "Qwen", 512)))
    }

    @Test
    fun theSameQuestionAndSettingsGiveTheSameKey() {
        assertEquals(
            NcieCache.qaKey("What is a cell", "Qwen3 0.6B", 512),
            NcieCache.qaKey("what is a cell", "Qwen3 0.6B", 512))
    }

    @Test
    fun aLongerAnswerLengthIsADifferentAnswer() {
        // the setting shaped the reply, so it is part of the identity of the
        // cached reply - otherwise a longer setting replayed the short one
        assertNotEquals(
            NcieCache.qaKey("what is a cell", "Qwen3 0.6B", 256),
            NcieCache.qaKey("what is a cell", "Qwen3 0.6B", 2048))
    }

    @Test
    fun anotherModelIsAnotherAnswer() {
        assertNotEquals(
            NcieCache.qaKey("what is a cell", "Qwen3 0.6B", 512),
            NcieCache.qaKey("what is a cell", "Llama 3.2 1B", 512))
    }

    @Test
    fun aDifferentQuestionIsADifferentKey() {
        assertNotEquals(
            NcieCache.qaKey("what is a cell", "Qwen3 0.6B", 512),
            NcieCache.qaKey("what is a gene", "Qwen3 0.6B", 512))
    }

    @Test
    fun theKeyIsLocaleIndependent() {
        // the delete used to lowercase with the device locale while the read
        // used Locale.ROOT; the key must not depend on the phone's locale
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ROOT)
            val root = NcieCache.qaKey("WHAT IS A CELL", "Qwen3 0.6B", 512)
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val tr = NcieCache.qaKey("WHAT IS A CELL", "Qwen3 0.6B", 512)
            assertEquals(root, tr)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun aPutLandsTheAnswer() {
        val filesDir = Files.createTempDirectory("novafiles").toFile()
        try {
            assertTrue(NcieCache.put(filesDir, "qa_x", "the answer"))
            assertEquals("the answer", File(NcieCache.dir(filesDir), "qa_x").readText())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    // v9.33.117 "Aa and BB are not the same question": the cached-answer key
    // was a 32-bit String.hashCode, and "Aa" / "BB" is Java's own collision
    // pair. Two questions that collide share one entry, so one is answered
    // with the other's answer, marked "(cached)".

    @Test
    fun theCanonicalHashCollisionIsNotOneCacheEntry() {
        assertEquals("the premise: these two strings really do collide",
            "Aa".hashCode(), "BB".hashCode())
        assertNotEquals("two questions, two entries",
            NcieCache.qaKey("Aa", "Qwen3 0.6B", 512),
            NcieCache.qaKey("BB", "Qwen3 0.6B", 512))
    }

    @Test
    fun twoModelLabelsThatCollideAreNotOneEntry() {
        assertEquals("the premise: these two labels really do collide",
            "Aa".hashCode(), "BB".hashCode())
        assertNotEquals("two models, two entries",
            NcieCache.qaKey("what is a cell", "Aa", 512),
            NcieCache.qaKey("what is a cell", "BB", 512))
    }

    @Test
    fun theSameQuestionIsStillOneEntry() {
        assertEquals(NcieCache.qaKey("What is a cell?", "Qwen3 0.6B", 512),
            NcieCache.qaKey("what is a cell?", "Qwen3 0.6B", 512))
        assertNotEquals("the answer length still shapes the key",
            NcieCache.qaKey("what is a cell?", "Qwen3 0.6B", 512),
            NcieCache.qaKey("what is a cell?", "Qwen3 0.6B", 900))
    }
}
