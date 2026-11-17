package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LearningInvalidationTest {
    @Test
    fun findsThePersistentFileWhenLazyBootHasNotStarted() {
        val filesDir = File("/tmp/nova-test-files")
        assertEquals(File(filesDir, "ncie_learn.txt"),
            learningFileForInvalidation(null, filesDir))
    }

    @Test
    fun keepsTheAlreadyCapturedFileAsTheSourceOfTruth() {
        val loaded = File("/data/user/0/org.nova/files/ncie_learn.txt")
        val later = File("/tmp/other-files")
        assertEquals(loaded, learningFileForInvalidation(loaded, later))
    }

    @Test
    fun refusesToGuessAPathWithoutEitherSource() {
        assertNull(learningFileForInvalidation(null, null))
    }
}
