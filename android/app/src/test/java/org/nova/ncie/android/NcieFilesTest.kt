package org.nova.ncie.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v9.33.90: the one atomic save.
 *
 * Every state file NOVA rewrites in place - the exam date, the tuning
 * overrides, the learned experience, the focus session, the chunk index that
 * backs "from my notes" - goes through [NcieFiles.write]. A plain writeText
 * truncates first and writes second, so a kill in between leaves a half file
 * that every reader parses defensively and therefore silently resets.
 *
 * These pin the three things that matter: the new text is what lands, the old
 * file is replaced rather than appended to, and no .tmp is left behind for the
 * next reader to trip over. The last test pins the failure direction - a save
 * that cannot happen reports false instead of throwing into a UI thread.
 */
class NcieFilesTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("novafiles").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theTextLandsInTheFile() {
        val f = File(dir, "state.txt")
        assertTrue(NcieFiles.write(f, "hello"))
        assertTrue(f.exists())
        assertEquals("hello", f.readText())
    }

    @Test
    fun theOldFileIsReplacedNotAppended() {
        val f = File(dir, "state.txt")
        f.writeText("a much longer old value that must not survive")
        assertTrue(NcieFiles.write(f, "new"))
        assertEquals("new", f.readText())
    }

    @Test
    fun noTmpFileIsLeftBehind() {
        val f = File(dir, "state.txt")
        NcieFiles.write(f, "one")
        NcieFiles.write(f, "two")
        val leftovers = dir.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("leftover: " + leftovers.map { it.name }, leftovers.isEmpty())
    }

    @Test
    fun theParentDirectoryIsCreated() {
        val f = File(File(dir, "deep/nested"), "state.txt")
        assertTrue(NcieFiles.write(f, "x"))
        assertEquals("x", f.readText())
    }

    @Test
    fun anEmptyValueIsARealSave() {
        val f = File(dir, "state.txt")
        f.writeText("something")
        assertTrue(NcieFiles.write(f, ""))
        assertEquals("", f.readText())
    }

    @Test
    fun aSaveThatCannotHappenReportsFalseAndDoesNotThrow() {
        // the parent path is a FILE, so the directory can never be created
        val blocker = File(dir, "blocker")
        blocker.writeText("i am not a directory")
        val f = File(File(blocker, "sub"), "state.txt")
        assertFalse(NcieFiles.write(f, "x"))
    }
}
