package org.nova.ncie.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.130 "A memory that could not be read is not an empty memory".
 *
 * [PersistentLearner.persist] writes the WHOLE snapshot out of the in-memory
 * store after every record, and restore used to treat a snapshot it could not
 * read exactly like no snapshot at all. One failed read therefore meant the
 * next recorded fact rewrote the file from an empty store and deleted every
 * fact, every cached answer and every weak topic the user had accumulated -
 * the same bug the v9.33.119 sweep killed in reminders, exams, planner, the
 * scheduled texts and the conversation store, and v9.33.129 killed in the
 * tuning overrides.
 *
 * [LearningStore.read] now answers two different things: "" is a store with
 * nothing stored yet, null is a store that has something stored and could not
 * be read. These tests pin the learner's half of that contract against a fake
 * store, so no filesystem and no Android are involved.
 */
class PersistentLearnerRestoreTest {

    /** A store that always answers [text] and counts what was written to it. */
    private class FakeStore(private val text: String?) : LearningStore {
        var writes = 0
        var last: String? = null
        override fun write(snapshot: String) {
            writes++
            last = snapshot
        }
        override fun read(): String? = text
    }

    @Test
    fun aStoreThatCouldNotBeReadIsNeverOverwritten() {
        val store = FakeStore(null)
        val learner = PersistentLearner(store)
        learner.noteFailure("photosynthesis")
        assertEquals(0, store.writes)
    }

    @Test
    fun aFailedReadDoesNotStopTheLearnerWorkingInMemory() {
        // the session must still function - it is a cache as well as a memory
        val store = FakeStore(null)
        val learner = PersistentLearner(store)
        learner.record(LearnedFact.fromChat("who wrote hamlet", "Shakespeare, around 1600.", 0.9))
        assertEquals(1, learner.learnedFacts().size)
        assertEquals(0, store.writes)
    }

    @Test
    fun anAbsentStoreIsNotAFailedOne() {
        // "" is a fresh install: nothing to lose, so the write goes ahead
        val store = FakeStore("")
        val learner = PersistentLearner(store)
        assertEquals(0, learner.learnedFacts().size)
        learner.noteFailure("photosynthesis")
        assertEquals(1, store.writes)
        assertTrue(store.last!!.contains("photosynthesis"))
    }

    @Test
    fun aSnapshotThatReadsIsRestoredAndThenAddedTo() {
        val first = FakeStore("")
        PersistentLearner(first).noteFailure("photosynthesis")
        val snapshot = first.last!!

        val second = FakeStore(snapshot)
        val learner = PersistentLearner(second)
        assertEquals(1, learner.weakTopics().size)
        learner.noteFailure("mitosis")
        assertEquals(1, second.writes)
        // the old topic survives the rewrite that added the new one
        assertTrue(second.last!!.contains("photosynthesis"))
        assertTrue(second.last!!.contains("mitosis"))
    }

    @Test
    fun aWipeIsStillWrittenAfterAFailedRead() {
        // clear() is an instruction, not a read that went wrong
        val store = FakeStore(null)
        val learner = PersistentLearner(store)
        learner.clear()
        assertEquals(1, store.writes)
        assertEquals("", store.last)
    }

    @Test
    fun forgettingAfterAFailedReadStillWritesNothing() {
        val store = FakeStore(null)
        val learner = PersistentLearner(store)
        learner.record(LearnedFact.fromChat("who wrote hamlet", "Shakespeare, around 1600.", 0.9))
        assertTrue(learner.forget("who wrote hamlet"))
        assertEquals(0, store.writes)
    }
}
