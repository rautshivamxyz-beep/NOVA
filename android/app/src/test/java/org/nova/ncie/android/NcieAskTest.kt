package org.nova.ncie.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.106 "A typed command is the whole message".
 *
 * Both directions matter and both are pinned here: a command that stops
 * firing is a feature the user loses, and a command that fires on a real
 * question is a feature that answers the wrong thing. The old patterns
 * failed the second test - they matched a PREFIX of the message.
 */
class NcieAskTest {

    @Test
    fun theDiagnosticsCommandStillFires() {
        for (t in listOf(
            "health", "nova health", "health check", "nova health check",
            "nova health status", "what's not working", "what is not working",
            "what is broken", "fix nova", "fix nova please", "nova setup",
            "setup nova", "diagnose", "diagnose nova", "Health?", "HEALTH"
        )) {
            assertTrue("should fire: $t", NcieAsk.health(t))
        }
    }

    @Test
    fun aDiagnosticsQuestionReachesTheModel() {
        for (t in listOf(
            "health benefits of walking to college",
            "what's not working in this function",
            "what is broken in my code",
            "fix nova's grammar",
            "diagnose this rash for me",
            "health is wealth, explain",
            "how do I improve my health",
            "nova health is bad"
        )) {
            assertFalse("must not fire: $t", NcieAsk.health(t))
        }
    }

    @Test
    fun theGrowthCommandStillFires() {
        for (t in listOf(
            "skills", "your skills", "my skills", "nova's skills", "growth",
            "what skills do you have", "what skills have you learned",
            "what skills have you got", "what skills can you do",
            "how have you grown", "what have you taught yourself", "Skills?"
        )) {
            assertTrue("should fire: $t", NcieAsk.skills(t))
        }
    }

    @Test
    fun aSkillsQuestionReachesTheModel() {
        for (t in listOf(
            "what skills do I need to become a data analyst",
            "what skills should I learn for placement",
            "what skills do I need to crack NEET",
            "how have you grown your business",
            "growth of bacteria in milk"
        )) {
            assertFalse("must not fire: $t", NcieAsk.skills(t))
        }
    }

    /** The length ceiling is still the outer gate - a long message is a
     *  study question even when it opens like a command. */
    @Test
    fun aLongMessageIsNeverACommand() {
        val longHealth = "health " + "x".repeat(NcieAsk.HEALTH_MAX)
        val longSkills = "skills " + "y".repeat(NcieAsk.SKILLS_MAX)
        assertFalse(NcieAsk.health(longHealth))
        assertFalse(NcieAsk.skills(longSkills))
    }

    @Test
    fun theNotificationSummaryStillFires() {
        for (t in listOf(
            "what did i miss", "What did I miss?", "what did i miss today",
            "what did i miss so far", "any notifications", "any notifications today",
            "any notifications from whatsapp", "read my notifications",
            "check my notifications", "notification summary"
        )) {
            assertTrue("should fire: $t", NcieAsk.missed(t))
        }
    }

    @Test
    fun aLectureQuestionReachesTheModel() {
        for (t in listOf(
            "what did i miss in class yesterday",
            "what did i miss in today's lecture",
            "i missed my bus, what do i do",
            "why did i miss the deadline"
        )) {
            assertFalse("must not fire: $t", NcieAsk.missed(t))
        }
    }

    @Test
    fun theConversationSummaryStillFires() {
        for (t in listOf(
            "summarize our conversation", "summarise our conversation",
            "what were we talking about", "what were we talking about so far",
            "conversation summary", "What were we talking about?"
        )) {
            assertTrue("should fire: $t", NcieAsk.summary(t))
        }
    }

    @Test
    fun aClassRecapQuestionReachesTheModel() {
        for (t in listOf(
            "what were we talking about in class yesterday",
            "what were we talking about last week in physics",
            "summarize our conversation in hindi please"
        )) {
            assertFalse("must not fire: $t", NcieAsk.summary(t))
        }
    }
}
