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
            "Thomas Shelby's brothers are Arthur Shelby and John Shelby."
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
    fun relationshipEvidenceMustBeAboutTheRequestedPersonAndRelationship() {
        val question = "who is the husband of lady gaga"
        assertTrue(FollowUpIntent.hasRelationshipEvidence(
            question,
            "Lady Gaga is married to Michael Polansky."
        ))
        assertFalse(FollowUpIntent.hasRelationshipEvidence(
            question,
            "Lady Gaga is a singer. Bradley Cooper was married to Jennifer Esposito."
        ))
        assertFalse(FollowUpIntent.hasRelationshipEvidence(
            question,
            "Lady Gaga is engaged to Michael Polansky."
        ))
        assertFalse(FollowUpIntent.hasRelationshipEvidence(
            question,
            "Lady Gaga and Bradley Cooper appeared together in a film; Bradley Cooper is married."
        ))
    }

    @Test
    fun relationshipFollowUpDoesNotInventAReferent() {
        assertNull(FollowUpIntent.personFactLookup("who are his brothers", listOf("tell me a joke")))
    }

    @Test
    fun directFactualChallengesAreRecognisedOnlyWithAPreviousAnswer() {
        val previous = "Lady Gaga's husband is Bradley Cooper."
        for (challenge in listOf(
            "that's wrong",
            "why are you inventing facts about her husband",
            "I told you not to invent facts",
            "you made that up",
            "that is not true"
        )) {
            assertTrue("$challenge should trigger re-evaluation",
                FollowUpIntent.isCorrectionFeedback(challenge, previous))
        }
        assertFalse(FollowUpIntent.isCorrectionFeedback("that's wrong", null))
        assertFalse(FollowUpIntent.isCorrectionFeedback("explain her music", previous))
        assertFalse(FollowUpIntent.isCorrectionFeedback(
            "you are wrong " + "x".repeat(220), previous))
    }

    @Test
    fun correctionChallengesIncludeRequestsForConfidenceAndEvidence() {
        val previous = "The current champion is Driver X."
        for (challenge in listOf(
            "are you sure?",
            "is that actually correct?",
            "that doesn't sound right",
            "you misunderstood the question",
            "that's not what I asked",
            "where did you get that?",
            "show me a source",
            "cite your answer",
            "prove it"
        )) {
            assertTrue("$challenge should trigger re-evaluation",
                FollowUpIntent.isCorrectionFeedback(challenge, previous))
        }
        assertFalse(FollowUpIntent.isCorrectionFeedback("are you sure?", null))
    }

    @Test
    fun depthFeedbackKeepsTheSameTopic() {
        assertTrue(FollowUpIntent.isDepthFeedback("these are basic facts"))
        assertTrue(FollowUpIntent.isDepthFeedback("too basic, go deeper"))
        assertFalse(FollowUpIntent.isDepthFeedback("tell me a joke"))
    }
}
