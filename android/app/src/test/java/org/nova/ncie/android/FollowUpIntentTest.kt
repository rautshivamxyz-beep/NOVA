package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun commonTypoOneMoeeIsRecognisedOnlyInJokeContext() {
        assertTrue(FollowUpIntent.isAnotherJoke("one moee", "tell me a joke", Jokes.at(0)))
        assertFalse(FollowUpIntent.isAnotherJoke("one moee", "what is photosynthesis?", Jokes.at(0)))
    }
    @Test
    fun unrelatedPreviousTurnIsNotHijacked() {
        assertFalse(FollowUpIntent.isAnotherJoke("one more", "what is photosynthesis?", Jokes.at(0)))
    }
    @Test
    fun nonJokeAssistantTextDoesNotQualify() {
        assertFalse(FollowUpIntent.isAnotherJoke("one more", "tell me a joke", "Here is a Python example."))
    }
    @Test
    fun longMessagesAreNotTreatedAsFollowUps() {
        assertFalse(FollowUpIntent.isAnotherJoke(
            "one more, and explain why it is funny", "tell me a joke", Jokes.at(0)
        ))
    }
    @Test
    fun nextJokeAdvancesWithoutRepeating() {
        assertNotEquals(Jokes.at(0), Jokes.next(Jokes.at(0)))
        assertEquals(Jokes.at(2), Jokes.next(Jokes.at(1)))
        assertEquals(Jokes.at(0), Jokes.next(Jokes.at(Jokes.count() - 1)))
    }

    @Test
    fun checkOnlineAboutHimUsesTheLastExplicitPersonLookup() {
        assertEquals(
            "who is thomas shelby",
            FollowUpIntent.onlineLookupQuery(
                "check online about him",
                listOf("who is thomas Shelby", "his name is Tommy Shelby")
            )
        )
    }

    @Test
    fun correctionAndBareCheckCommandsResolveThePreviousEntity() {
        val history = listOf("who is thomas Shelby", "you gave the wrong actor")
        assertEquals("who is thomas shelby",
            FollowUpIntent.onlineLookupQuery("again wrong go and check online", history))
        assertEquals("who is thomas shelby",
            FollowUpIntent.onlineLookupQuery("check again", history))
    }

    @Test
    fun personRelationshipFollowUpUsesTheLastPersonQuestion() {
        assertEquals(
            "who are the brothers of thomas shelby",
            FollowUpIntent.personFactLookup(
                "who was his brothers",
                listOf("who is thomas Shelby", "his name is Tommy Shelby")
            )
        )
    }

    @Test
    fun onlineCheckAboutHisBrothersExpandsToTheNamedPerson() {
        assertEquals(
            "who are the brothers of thomas shelby",
            FollowUpIntent.onlineLookupQuery(
                "check online about his brothers",
                listOf("who is thomas Shelby")
            )
        )
    }

    @Test
    fun resolvedFamilyQuestionIsRecognisedAfterLookup() {
        assertTrue(FollowUpIntent.isPersonRelationQuestion(
            "who are the brothers of Thomas Shelby"
        ))
        assertFalse(FollowUpIntent.isPersonRelationQuestion("who are the brothers"))
    }

    @Test
    fun familyEvidenceMustNameThePersonAndTheRequestedRelationship() {
        val query = "who are the brothers of thomas shelby"
        assertTrue(FollowUpIntent.hasRelationshipEvidence(
            query,
            "Tommy Shelby's brothers are Arthur Shelby and John Shelby."
        ))
        assertFalse(FollowUpIntent.hasRelationshipEvidence(
            query,
            "Thomas Shelby is a fictional character in Peaky Blinders."
        ))
        assertFalse(FollowUpIntent.hasRelationshipEvidence(
            query,
            "The brothers of another character are mentioned here."
        ))
    }

    @Test
    fun relationshipFollowUpDoesNotInventAReferent() {
        assertNull(FollowUpIntent.personFactLookup("who are his brothers", listOf("tell me a joke")))
    }

    @Test
    fun depthFeedbackKeepsTheSameTopic() {
        assertTrue(FollowUpIntent.isDepthFeedback("these are basic facts"))
        assertTrue(FollowUpIntent.isDepthFeedback("too basic, go deeper"))
        assertFalse(FollowUpIntent.isDepthFeedback("tell me a joke"))
    }
}
