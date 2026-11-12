package org.nova

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * v9.33.140 "Nothing is deleted before it is replaced": pin where a restore
 * entry lands, and that a crafted entry cannot land outside the staging folder.
 *
 * This is the one place in NOVA that can destroy every chat, note, reminder and
 * setting the user owns, so its two rules are asserted rather than eyeballed:
 *  - a normal entry is staged where the swap will find it (`files/...` for
 *    filesDir, `shared_prefs/...` for the folder beside it), and
 *  - an entry that climbs out of the staging folder is refused (null), not
 *    written somewhere else on the device.
 *
 * Pure file arithmetic: no Activity, no zip, no device.
 */
class BackupRestoreTest {

    private lateinit var staging: File

    @Before
    fun setUp() {
        staging = File(System.getProperty("java.io.tmpdir") ?: "/tmp",
            "nova-stage-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        staging.deleteRecursively()
    }

    @Test
    fun `a plain entry is staged under files`() {
        val t = stageTarget(staging, "chats.json")
        assertEquals(File(File(staging, "files"), "chats.json").canonicalPath, t?.canonicalPath)
    }

    @Test
    fun `a settings entry is staged beside files, not inside it`() {
        val t = stageTarget(staging, "shared_prefs/nova_prefs.xml")
        assertEquals(File(File(staging, "shared_prefs"), "nova_prefs.xml").canonicalPath,
            t?.canonicalPath)
    }

    @Test
    fun `a nested files entry keeps its shape`() {
        val t = stageTarget(staging, "embeddings/notes.emb")
        assertEquals(File(File(staging, "files"), "embeddings/notes.emb").canonicalPath, t?.canonicalPath)
    }

    @Test
    fun `an entry that climbs out of the staging folder is refused`() {
        assertNull(stageTarget(staging, "../../evil.txt"))
        assertNull(stageTarget(staging, "shared_prefs/../../evil.xml"))
        assertNull(stageTarget(staging, "files/../../../evil"))
    }

    @Test
    fun `an absolute-looking entry is read as relative and stays inside`() {
        val t = stageTarget(staging, "/etc/passwd")
        assertTrue(t != null)
        assertTrue(t!!.canonicalPath.startsWith(staging.canonicalPath + File.separator))
    }

    @Test
    fun `an empty name is refused`() {
        assertNull(stageTarget(staging, ""))
    }
}
