package org.nova.ncie.android

import android.content.Context
import org.nova.ncie.skill.Skill
import org.nova.ncie.skill.SkillStore
import java.io.File

/**
 * v8.4.0 (stage 3): the app's skill library - skills as data. skills.txt
 * ships in the assets and is copied to filesDir on first boot; the user
 * (or a future download) can edit that file to add skills with NO code
 * change. Parsing, matching and rendering are the kernel's SkillStore.
 */
object NcieSkills {

    @Volatile private var skills: List<Skill>? = null

    private fun file(ctx: Context): File = File(ctx.filesDir, "skills.txt")

    /** Copy the bundled defaults once, then parse. Safe to call on any
     *  thread; the chat path calls it from the io warm-up. */
    fun ensure(ctx: Context) {
        if (skills != null) return
        val f = file(ctx)
        if (!f.exists()) {
            try {
                ctx.assets.open("skills.txt").bufferedReader().use { r ->
                    f.writeText(r.readText())
                }
            } catch (e: Exception) { }
        }
        skills = try { SkillStore.parse(f.readText()) } catch (e: Exception) { emptyList() }
    }

    /** The first skill whose trigger matches, or null. */
    fun match(text: String): Skill? {
        val s = skills ?: return null
        return SkillStore.best(s, text)
    }
}
