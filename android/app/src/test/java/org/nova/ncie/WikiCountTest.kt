package org.nova.ncie

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v9.33.126: the wiki article count is a pure parse, so it is pinned here.
 * These are the exact inputs [org.nova.WikiCore.articleCount] can hand it:
 * null (the file is there but could not be read), "" (no file yet, or an
 * empty one), the number the downloader wrote, and garbage.
 */
class WikiCountTest {

    @Test
    fun unreadableCountIsZero() {
        assertEquals(0, WikiCount.parse(null))
    }

    @Test
    fun missingOrEmptyCountIsZero() {
        assertEquals(0, WikiCount.parse(""))
        assertEquals(0, WikiCount.parse("   "))
        assertEquals(0, WikiCount.parse("\n"))
    }

    @Test
    fun aWrittenCountReadsBack() {
        assertEquals(1234, WikiCount.parse("1234"))
        assertEquals(1234, WikiCount.parse("1234\n"))
        assertEquals(1234, WikiCount.parse("  1234  "))
    }

    @Test
    fun zeroIsZero() {
        assertEquals(0, WikiCount.parse("0"))
    }

    @Test
    fun garbageIsZero() {
        assertEquals(0, WikiCount.parse("articles"))
        assertEquals(0, WikiCount.parse("12 articles"))
        assertEquals(0, WikiCount.parse("1,234"))
        assertEquals(0, WikiCount.parse("12.5"))
    }

    @Test
    fun aNegativeCountIsNotACount() {
        assertEquals(0, WikiCount.parse("-1"))
        assertEquals(0, WikiCount.parse("-1234"))
    }

    @Test
    fun aCountTooLargeForAnIntIsZero() {
        // the downloader writes an Int, so anything past it is corruption
        assertEquals(0, WikiCount.parse("99999999999999"))
    }
}
