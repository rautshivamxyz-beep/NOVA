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

    /** v8.5.0: the BUNDLED skills are parsed fresh from the assets on
     *  every boot - a new skill ships with an app update and just works.
     *  The user's own skills.txt (if they created one) takes precedence:
     *  its skills match first, so an edit overrides the defaults. */
    fun ensure(ctx: Context) {
        if (skills != null) return
        val bundled = try {
            ctx.assets.open("skills.txt").bufferedReader().readText()
        } catch (e: Exception) { "" }
        val user = try { file(ctx).readText() } catch (e: Exception) { "" }
        skills = SkillStore.parse(user) + SkillStore.parse(bundled)
    }

    /** The first skill whose trigger matches, or null. */
    fun match(text: String): Skill? {
        val s = skills ?: return null
        return SkillStore.best(s, text)
    }
}
