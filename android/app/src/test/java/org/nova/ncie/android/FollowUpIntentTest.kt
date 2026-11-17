package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpIntentTest {
    @Test
    fun oneMoreAfterAJokeIsRoutedAsAnotherJoke() {
        assertTrue(FollowUpIntent.isAnotherJoke("one more", "tell me a joke", Jokes.at(0)))
    }

    @Test
    fun repeatedJokeFollowUpsRemainInTheJokeThread() {
        assertTrue(FollowUpIntent.isAnotherJoke("another one", "one more", Jokes.at(1)))
    }

    @Test
    fun oneMoreAfterAnUnrelatedQuestionIsNotHijacked() {
        assertFalse(FollowUpIntent.isAnotherJoke("one more", "what is photosynthesis?", Jokes.at(0)))
    }

    @Test
    fun ordinaryAssistantTextDoesNotQualifyAsAJokeContext() {
        assertFalse(FollowUpIntent.isAnotherJoke("one more", "tell me a joke", "Here is a Python example."))
    }

    @Test
    fun arbitraryLongMessagesAreNotTreatedAsFollowUps() {
        assertFalse(FollowUpIntent.isAnotherJoke(
            "one more, and explain why it is funny", "tell me a joke", Jokes.at(0)
        ))
    }

    @Test
    fun nextJokeAdvancesAndNeverRepeatsThePreviousOne() {
        assertNotEquals(Jokes.at(0), Jokes.next(Jokes.at(0)))
        assertEquals(Jokes.at(2), Jokes.next(Jokes.at(1)))
        assertEquals(Jokes.at(0), Jokes.next(Jokes.at(Jokes.count() - 1)))
    }
}
