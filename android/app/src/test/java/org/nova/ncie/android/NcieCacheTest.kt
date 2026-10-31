package org.nova.ncie.android

import org.junit.After
import org.junit.Assert.assertEquals
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
}
