package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

/**
 * v9.33.127 "The one save in the app, pinned".
 *
 * [NcieFiles.write] is the single save behind roughly twenty of NOVA's state
 * stores - the exam date, the reminders, the conversations, the learned facts,
 * the focus session, the wiki count. Three separate releases were spent fixing
 * its call sites (v9.33.96, v9.33.100, v9.33.123) and one on its lock
 * (v9.33.99), and it had no test of its own: every one of those was pinned by
 * reading the source in verify_patches, which cannot tell whether the thing
 * actually works.
 *
 * It is ordinary file I/O, so a plain JVM test can run it for real against a
 * temporary directory. No Android class is touched on any path here -
 * [NovaLog] catches its own logging failure.
 */
class NcieFilesTest {

    private fun tempDir(): File =
        Files.createTempDirectory("nova-files-test").toFile().also { it.deleteOnExit() }

    @Test
    fun writePutsTheTextOnDisk() {
        val f = File(tempDir(), "state.txt")
        assertTrue(NcieFiles.write(f, "hello"))
        assertEquals("hello", f.readText())
    }

    @Test
    fun writeOverwritesTheOldTextCompletely() {
        val f = File(tempDir(), "state.txt")
        assertTrue(NcieFiles.write(f, "a much longer first value"))
        assertTrue(NcieFiles.write(f, "short"))
        assertEquals("short", f.readText())
    }

    @Test
    fun writeLeavesNoTemporaryFileBehind() {
        val dir = tempDir()
        val f = File(dir, "state.txt")
        assertTrue(NcieFiles.write(f, "x"))
        assertEquals(listOf("state.txt"), dir.list()!!.sorted())
    }

    @Test
    fun writeCreatesTheParentDirectory() {
        val f = File(File(tempDir(), "a/b/c"), "state.txt")
        assertTrue(NcieFiles.write(f, "nested"))
        assertEquals("nested", f.readText())
    }

    @Test
    fun writeHandlesEmptyText() {
        val f = File(tempDir(), "state.txt")
        assertTrue(NcieFiles.write(f, ""))
        assertEquals("", f.readText())
    }

    /**
     * v9.33.99 "One buffer per file": the save writes a FIXED sibling `.tmp`,
     * so two threads saving the same path without the per-path lock shared one
     * buffer and the file that got renamed into place was neither writer's
     * text. The final file must be exactly one of the two values, never a mix.
     */
    @Test
    fun twoThreadsOnOneFileNeverInterleave() {
        val f = File(tempDir(), "state.txt")
        val a = "A".repeat(4000)
        val b = "B".repeat(4000)
        repeat(40) {
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val ta = Thread { start.await(); repeat(5) { NcieFiles.write(f, a) }; done.countDown() }
            val tb = Thread { start.await(); repeat(5) { NcieFiles.write(f, b) }; done.countDown() }
            ta.start(); tb.start()
            start.countDown()
            done.await()
            val got = f.readText()
            assertTrue("interleaved write: " + got.take(20),
                got == a || got == b)
        }
    }

    /** Different files still save independently - the lock is per path. */
    @Test
    fun twoDifferentFilesBothLand() {
        val dir = tempDir()
        val one = File(dir, "one.txt")
        val two = File(dir, "two.txt")
        assertTrue(NcieFiles.write(one, "1"))
        assertTrue(NcieFiles.write(two, "2"))
        assertEquals("1", one.readText())
        assertEquals("2", two.readText())
    }

    /**
     * v9.33.119 "A store that could not be read is not an empty store": the
     * other half of the save. "" is "there is nothing here yet"; null is
     * "there is something here and I could not read it". A directory sitting
     * where the state file belongs is exactly the second case, and it is the
     * one a caller must not treat as an empty store.
     */
    @Test
    fun readOrNullOnAMissingFileIsEmpty() {
        assertEquals("", NcieFiles.readOrNull(File(tempDir(), "nothing.txt")))
    }

    @Test
    fun readOrNullOnAGoodFileIsItsText() {
        val f = File(tempDir(), "state.txt")
        NcieFiles.write(f, "value")
        assertEquals("value", NcieFiles.readOrNull(f))
    }

    @Test
    fun readOrNullOnAnUnreadableFileIsNull() {
        val f = File(tempDir(), "state.txt")
        assertTrue(f.mkdirs())          // a directory where a file belongs
        assertTrue(f.exists())
        assertNull(NcieFiles.readOrNull(f))
    }

    /** A save that cannot work answers false - it never throws at the caller. */
    @Test
    fun writeToAnImpossiblePathAnswersFalse() {
        val dir = tempDir()
        val blocker = File(dir, "blocker")
        // File.writeText answers Unit, not a boolean - assert the result of the
        // write, not the write itself (v9.33.128: this line did not compile)
        blocker.writeText("not a directory")
        assertTrue(blocker.isFile)
        val f = File(blocker, "state.txt")   // parent is a file, not a directory
        assertFalse(NcieFiles.write(f, "x"))
    }
}
