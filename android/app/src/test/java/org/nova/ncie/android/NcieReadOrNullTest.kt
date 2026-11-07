package org.nova.ncie.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v9.33.119 "A store that could not be read is not an empty store".
 *
 * Every store in NOVA parses its file defensively, so a file it cannot read
 * comes back as "nothing there". For a screen that only displays that is the
 * right answer. For a store that WRITES BACK what it just read it is data
 * loss: the reminder store, the exam list and planner.json all load, change
 * one item and save the whole list, so an empty list from a failed read means
 * the next save deletes everything else the user had.
 *
 * [NcieFiles.readOrNull] is the one place that keeps the two cases apart.
 * These pin the distinction the mutators depend on: "" is "not there yet"
 * (nothing to lose, safe to create), null is "there and unreadable" (something
 * to lose, write nothing).
 */
class NcieReadOrNullTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("novaread").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aMissingFileIsNotAReadFailure() {
        // a fresh install: nothing on disk, and the first save may create it
        assertEquals("", NcieFiles.readOrNull(File(dir, "never-written.json")))
    }

    @Test
    fun aFileWithTextComesBackWhole() {
        val f = File(dir, "reminders.json")
        f.writeText("[{\"at\":1,\"t\":\"drink water\"}]")
        assertEquals("[{\"at\":1,\"t\":\"drink water\"}]", NcieFiles.readOrNull(f))
    }

    @Test
    fun anEmptyFileIsEmptyTextNotAFailure() {
        // a file that is really empty is still a file NOVA wrote - it is not
        // the same answer as one that could not be opened at all
        val f = File(dir, "empty.json")
        f.writeText("")
        assertEquals("", NcieFiles.readOrNull(f))
    }

    @Test
    fun aPathThatCannotBeReadIsNull() {
        // the path exists but is a directory: exists() is true and readText()
        // throws, which is exactly the corrupt-state-file shape the stores
        // must refuse to overwrite. (A chmod-000 file cannot be used here -
        // the test runner may be root, and root ignores the mode.)
        val f = File(dir, "unreadable.json")
        assertTrue("could not make the unreadable path", f.mkdirs())
        assertNull(NcieFiles.readOrNull(f))
    }
}
