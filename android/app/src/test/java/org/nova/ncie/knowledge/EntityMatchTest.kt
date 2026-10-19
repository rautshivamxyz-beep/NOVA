package org.nova.ncie.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v9.33.14: the article title must NAME the entity that was asked about. */
class EntityMatchTest {

    @Test
    fun rejectsAnArticleThatMerelyMentionsTheWords() {
        // "who is physics wallah" was answered with the Radha Bhatt intro,
        // whose page only mentions a web series called Physics Wallah.
        assertFalse(EntityMatch.titleMatchesEntity("Radha Bhatt", "who is physics wallah"))
        assertFalse(EntityMatch.titleMatchesEntity("Mia Tomlinson", "who is mia ham"))
    }

    @Test
    fun acceptsTheArticleThatNamesTheEntity() {
        assertTrue(EntityMatch.titleMatchesEntity("Mia Hamm", "who is mia hamm"))
        // a title that runs the entity together still names it
        assertTrue(EntityMatch.titleMatchesEntity("PhysicsWallah", "who is physics wallah"))
        assertTrue(EntityMatch.titleMatchesEntity("Grigori Rasputin", "who is rasputin"))
        assertTrue(EntityMatch.titleMatchesEntity("Narendra Modi", "who is narendra modi"))
        assertTrue(EntityMatch.titleMatchesEntity("Lencho Letta", "who is lencho"))
        assertTrue(EntityMatch.titleMatchesEntity("J. K. Rowling", "who is jk rowling"))
        assertTrue(EntityMatch.titleMatchesEntity("Photosynthesis", "what is photosynthesis"))
    }

    @Test
    fun stripsTheQuestionLeadIn() {
        assertEquals("physics wallah", EntityMatch.entityPhrase("who is physics wallah"))
        assertEquals("photosynthesis", EntityMatch.entityPhrase("what is photosynthesis?"))
    }

    @Test
    fun aQuestionWithNoEntityNeverMatches() {
        assertFalse(EntityMatch.titleMatchesEntity("Anything At All", "who is the"))
    }
}
