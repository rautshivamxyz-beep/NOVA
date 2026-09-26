#!/usr/bin/env python3
"""NOVA v7.6.0 support patch: reliability fixes for every non-MainActivity
file (run AFTER patch_v740.py - it anchors on v7.4-patched files).

- atomic tmp+rename saves: Knowledge, Exams, Study, ReminderStore
- unique monotonic reminder IDs (hash collisions overwrote alarms)
- Backup now contains exams and reminders, and rejects path-traversal ids
- WikiCore: done.txt only after the articles are complete
- ModelDownloader: real .part deleted on cancelled imports; free-space
  check before multi-GB downloads
- NovaEngine: resetConversation refuses to run during a model load
- ChatsActivity: search filters an in-memory list (no per-keystroke
  flash reads); KnowledgeActivity: OCR recognizer closed
- ExamsActivity: strict date parsing; ModelsActivity: real GB sizes with
  Locale.US; NotifBrain: component-based isEnabled
- SettingsActivity: backup/restore off the main thread, empty export
  reported as failure
- Exams: calendar-day daysLeft (yesterday no longer shows TODAY)

All edits are buffered in memory - nothing is written unless every
anchor matched. Idempotent - safe to run on every CI build.
Usage: patch_v760_support.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = os.path.join(ROOT, "android/app/src/main/java/org/nova/")

# idempotency: skip entirely if already applied (Knowledge.kt carries the tag)
if "v7.6" in open(os.path.join(BASE, "Knowledge.kt"), encoding="utf-8").read():
    print("support files: v7.6.0 patch already applied")
    sys.exit(0)
total = 0
files = {}

def edit(fname, old, new, what):
    global total
    p = BASE + fname
    if p not in files:
        files[p] = open(p, encoding="utf-8").read()
    src = files[p]
    n = src.count(old)
    assert n == 1, "%s: %s anchor found %dx" % (fname, what, n)
    files[p] = src.replace(old, new)
    total += 1
    print("ok  %-22s %s" % (fname, what))

ATOMIC = '''            // v7.6: atomic write - a crash mid-write no longer wipes the file
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }'''

# ================= Knowledge.kt =================
edit("Knowledge.kt", '''            for (c in chunks) arr.put(JSONObject().put("d", c.doc).put("t", c.text))
            file(ctx).writeText(arr.toString())
            cache = chunks''',
'''            for (c in chunks) arr.put(JSONObject().put("d", c.doc).put("t", c.text))
''' + ATOMIC + '''
            cache = chunks''', "atomic save")

edit("Knowledge.kt", '''    private fun tokenize(s: String): List<String> {''',
'''    /** v7.6: public for the send()-side relevance gate. */
    fun tokenize(s: String): List<String> {''', "tokenize public")

edit("Knowledge.kt", '''    fun hasDocs(ctx: Context): Boolean = load(ctx).isNotEmpty()''',
'''    fun hasDocs(ctx: Context): Boolean = load(ctx).isNotEmpty()

    /** v7.6: warm the cache from a background thread at app start so the
     *  first message of a session never parses knowledge.json on the UI
     *  thread (Wikipedia got warmUp long ago - this is the notes twin). */
    fun warmUp(ctx: Context) { load(ctx) }''', "warmUp")

edit("Knowledge.kt", '''    fun addDoc(ctx: Context, name: String, text: String) {''',
'''    @Synchronized
    fun addDoc(ctx: Context, name: String, text: String) {''', "synchronized addDoc")

# ================= Exams.kt =================
edit("Exams.kt", '''            for (e in exams) arr.put(JSONObject().put("name", e.name).put("date", e.dateMs))
            file(ctx).writeText(arr.toString())''',
'''            for (e in exams) arr.put(JSONObject().put("name", e.name).put("date", e.dateMs))
''' + ATOMIC, "atomic save")

edit("Exams.kt", '''    fun daysLeft(dateMs: Long): Int =
        ((dateMs - System.currentTimeMillis()) / 86_400_000L).toInt() + 1''',
'''    fun daysLeft(dateMs: Long): Int {
        // v7.6: calendar-day difference in the local zone - the old
        // millisecond math showed "TODAY" for an exam held yesterday
        val zone = java.util.TimeZone.getDefault()
        fun day(ms: Long) = (ms + zone.getOffset(ms)) / 86_400_000L
        return (day(dateMs) - day(System.currentTimeMillis())).toInt()
    }''', "daysLeft calendar days")

edit("Exams.kt", '''        return upcoming.joinToString("; ") { e ->
            "${e.name} in ${daysLeft(e.dateMs)} days " +
                "(${SimpleDateFormat("d MMM", Locale.US).format(Date(e.dateMs))})"
        }''',
'''        return upcoming.joinToString("; ") { e ->
            val d = daysLeft(e.dateMs)
            (if (d <= 0) "${e.name} TODAY " else "${e.name} in $d days ") +
                "(${SimpleDateFormat("d MMM", Locale.US).format(Date(e.dateMs))})"
        }''', "promptLine today")

# ================= Study.kt =================
edit("Study.kt", '''                    .put("iv", c.interval).put("due", c.due))
            }
            file(ctx).writeText(arr.toString())''',
'''                    .put("iv", c.interval).put("due", c.due))
            }
''' + ATOMIC, "atomic save")

# ================= ReminderStore.kt =================
edit("ReminderStore.kt", '''            file(ctx).writeText(arr.toString())
        } catch (e: Exception) { }
    }
}''',
'''            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { }
    }

    /** v7.6: stable unique reminder IDs - the old hashCode() ids could
     *  collide, silently overwriting an alarm with another. */
    fun nextId(ctx: Context): Int {
        val f = File(ctx.filesDir, "reminder_id")
        var n = 1
        try { if (f.exists()) n = f.readText().trim().toInt() + 1 } catch (e: Exception) { }
        try { f.writeText(n.toString()) } catch (e: Exception) { }
        return n
    }
}''', "atomic save + nextId")

# ================= ReminderReceiver.kt =================
edit("ReminderReceiver.kt", '''        val id = (atMillis.toString() + text).hashCode()''',
'''        // v7.6: unique ID from the store - hash collisions silently
        // overwrote alarms
        val id = ReminderStore.nextId(context)''', "monotonic id")

# ================= Backup.kt =================
edit("Backup.kt", '''        val kFile = File(ctx.filesDir, "knowledge.json")''',
'''        // v7.6: exams and reminders are part of the backup - a reinstall
        // used to silently lose both
        val exams = JSONArray()
        for (e in Exams.load(ctx)) exams.put(JSONObject().put("name", e.name).put("date", e.dateMs))
        val reminders = JSONArray()
        for ((at, t, rep) in ReminderStore.load(ctx))
            reminders.put(JSONObject().put("at", at).put("t", t).put("rep", rep))
        val kFile = File(ctx.filesDir, "knowledge.json")''', "export exams+reminders")

edit("Backup.kt", '''            .put("study", study)
            .put("chats", chats)''',
'''            .put("study", study)
            .put("exams", exams)
            .put("reminders", reminders)
            .put("chats", chats)''', "export puts")

edit("Backup.kt", '''        var n = 0
        val chats = o.optJSONArray("chats") ?: JSONArray()''',
'''        // v7.6: restore exams and reminders too
        val exams = o.optJSONArray("exams")
        if (exams != null) {
            val list = mutableListOf<Exams.Exam>()
            for (i in 0 until exams.length()) {
                val eo = exams.getJSONObject(i)
                list.add(Exams.Exam(eo.getString("name"), eo.optLong("date", 0L)))
            }
            if (list.isNotEmpty()) Exams.save(ctx, list)
        }
        val rem = o.optJSONArray("reminders")
        if (rem != null) {
            val now = System.currentTimeMillis()
            for (i in 0 until rem.length()) {
                val ro = rem.getJSONObject(i)
                val at = ro.optLong("at", 0L)
                val rep = ro.optLong("rep", 0L)
                val t = ro.optString("t", "")
                if (t.isNotEmpty() && (at > now || rep > 0L)) {
                    ReminderStore.add(ctx, at, t, rep)
                    Reminder.schedule(ctx, at, t, rep)
                }
            }
        }
        var n = 0
        val chats = o.optJSONArray("chats") ?: JSONArray()''', "restore exams+reminders")

edit("Backup.kt", '''            val id = c.optString("id")
            if (id.isNotBlank()) {''',
'''            val id = c.optString("id")
            // v7.6: a crafted backup could write outside the chats folder
            // via "../" in the id - plain names onlBˆYˆ
Yš\Ó›İ›[šÊ
H	‰ˆ™YÙ^
–ĞKV˜K^ŒNWËWJÈŠK›X]Ú\ÊY
JHÉÉÉËšYØ[š]^˜][ÛˆŠB‚ˆÈOOOOOOOOOOOOOOOOHÚZÚPÛÜ™KšİOOOOOOOOOOOOOOOOB™Y]
•ÚZÚPÛÜ™Kšİ‹	ÉÉÈ˜[ÈHY™™\™YÜš]\Šš[UÜš]\Š\XÛ\Ñš[Jİ
K˜[ÙJJBˆ˜[ÈHY™™\™YÜš]\Šš[UÜš]\ŠÛ™Qš[Jİ
K˜[ÙJJIÉÉË‰ÉÉÈËÈËˆ›ÜHÛœ™XYHˆ›YÈš\œİ[™Ü™X]HÛ™KˆËÈÓ“HY\ˆH\XÛ\È\™HÛÛ\]HH[ˆ[\œ\YˆËÈİÛ›ØY\ÙYÈX]™HH[ˆ]\Ù]\›X[™[Hœ™XYH‚ˆÛ™Qš[Jİ
K™[]J
Bˆ˜[Û™U\Hš[J™Û™K\ŠBˆ˜[ÈHY™™\™YÜš]\Šš[UÜš]\Š\XÛ\Ñš[Jİ
K˜[ÙJJBˆ˜[ÈHY™™\™YÜš]\Šš[UÜš]\ŠÛ™U\˜[ÙJJIÉÉË™Û™K\İŠB‚™Y]
•ÚZÚPÛÜ™Kšİ‹	ÉÉÈË˜ÛÜÙJ
NÈË˜ÛÜÙJ
Bˆš[J˜Ûİ[ŠKÜš]U^
‹Ôİš[™Ê
JIÉÉË‰ÉÉÈË˜ÛÜÙJ
NÈË˜ÛÜÙJ
BˆÛ™U\œ™[˜[YUÊÛ™Qš[Jİ
JBˆš[J˜Ûİ[ŠKÜš]U^
‹Ôİš[™Ê
JIÉÉË™Û™K\™[˜[YHŠB‚™Y]
•ÚZÚPÛÜ™Kšİ‹	ÉÉÈYˆ
\İ\œ›ÜˆOH[
HÂˆ™XYœÛY\
Ì
IÉÉË‰ÉÉÈYˆ
\İ\œ›ÜˆOH[
HÂˆÛİ[˜ÛÜ›İ][™\Ë™[^JÌ
IÉÉË™[^H›İÛY\ŠB‚ˆÈOOOOOOOOOOOOOOOOH[Ù[İÛ›ØY\‹šİOOOOOOOOOOOOOOOOB™Y]
“[Ù[İÛ›ØY\‹šİ‹	ÉÉÈ›ØˆHØÛÜK›][˜ÚÂˆHÂˆ˜[\İH[š\]YQš[Jš[J\‹Ø[š]^™J˜[YJJJBˆ˜[\Hš[J\İ˜XœÛÛ]T]
È‹œ\ŠBˆ˜\ˆÛÜYYH	ÉÉË‰ÉÉÈ›ØˆHØÛÜK›][˜ÚÂˆ˜\ˆ\š[Nˆš[OÈH[ˆHÂˆ˜[\İH[š\]YQš[Jš[J\‹Ø[š]^™J˜[YJJJBˆ˜[\Hš[J\İ˜XœÛÛ]T]
È‹œ\ŠBˆ\š[HH\ˆ˜\ˆÛÜYYH	ÉÉËš[\Ü\™YˆŠB‚™Y]
“[Ù[İÛ›ØY\‹šİ‹	ÉÉÈHØ]Ú
NˆÛİ[˜ÛÜ›İ][™\ËØ[˜Ù[][Û‘^Ù\[ÛŠHÂˆš[J\‹Ø[š]^™J˜[YJH
È‹œ\ŠK™[]J
BˆÜİ]K˜[YHHİ]K’YIÉÉË‰ÉÉÈHØ]Ú
NˆÛİ[˜ÛÜ›İ][™\ËØ[˜Ù[][Û‘^Ù\[ÛŠHÂˆËÈËˆ[]HH‘PS\X[š[HHÚ]H[š\]ZYšYYˆËÈ˜[YH
[Ù[LK™ÙİYŠHHÛÛX[\[]YH›Û‹Y^\İ[ˆËÈ][™İ˜[™YH][KQĞˆœ\ˆ\š[OË™[]J
BˆÜİ]K˜[YHHİ]K’YIÉÉËš[\ÜØ[˜Ù[ÛX[\ŠB‚™Y]
“[Ù[İÛ›ØY\‹šİ‹	ÉÉÈ˜\ˆÛ™HHYˆ
™\İ[YJH^\İ[™È[ÙH	ÉÉË‰ÉÉÈËÈËˆ™Y\ÙHÈİ\[ÈH[\ÚÈHHÛ˜Z[\™HØ\ÂˆËÈS“ÔÔÈY\[ÈH][KQĞˆİÛ›ØYˆYˆ
İ[ˆ	‰ˆ[™›ÚY›ÜË”İ]œÊ\‹˜XœÛÛ]T]
K˜]˜Z[X›P]\Èİ[
Bˆ›İÈSÑ^Ù\[ÛŠ››İ[›İYÚœ™YHÜXÙH›Üˆ\È[Ù[ŠBˆ˜\ˆÛ™HHYˆ
™\İ[YJH^\İ[™È[ÙH	ÉÉË™œ™YHÜXÙHÚXÚÈŠB‚ˆÈOOOOOOOOOOOOOOOOH›İ˜Q[™Ú[™KšİOOOOOOOOOOOOOOOOB™Y]
“›İ˜Q[™Ú[™Kšİ‹	ÉÉÈİ\Ü[™[ˆ™\Ù]ÛÛ™\œØ][ÛŠÛÛ^ˆÛÛ^Ş\İ[T›Û\ˆİš[™ÊNˆ›ÛÛX[ˆÂˆ˜[[™Ú[™HH[™Ú[™T™YˆÎˆ™]\›ˆ˜[ÙBˆYˆ
Z\Ó[Ù[ØYY
H™]\›ˆ˜[ÙIÉÉË‰ÉÉÈİ\Ü[™[ˆ™\Ù]ÛÛ™\œØ][ÛŠÛÛ^ˆÛÛ^Ş\İ[T›Û\ˆİš[™ÊNˆ›ÛÛX[ˆÂˆ˜[[™Ú[™HH[™Ú[™T™YˆÎˆ™]\›ˆ˜[ÙBˆËÈËˆ™]™\ˆšYÚ[ˆ[‹Y›YÚ[Ù[ØYH›™]ÈÚ]ˆ\š[™ÈBˆËÈ[Ù[İÚ]ÚÛİ[İX›KYš]™HH[™Ú[™BˆYˆ
ØY[™ÊH™]\›ˆ˜[ÙBˆYˆ
Z\Ó[Ù[ØYY
H™]\›ˆ˜[ÙIÉÉË›ØY[™ÈİX\™ŠB‚ˆÈOOOOOOOOOOOOOOOOHÚ]ĞXİ]š]KšİOOOOOOOOOOOOOOOOB™Y]
Ú]ĞXİ]š]Kšİ‹	ÉÉÈš]˜]H˜\ˆ^ÜÚ]ˆÚ]ÈH[ˆš]˜]H˜\ˆ]Y\Nˆİš[™ÈHˆ‰ÉÉË‰ÉÉÈš]˜]H˜\ˆ^ÜÚ]ˆÚ]ÈH[ˆš]˜]H˜\ˆ]Y\Nˆİš[™ÈHˆ‚‚ˆËÈËˆ[‹[Y[[ÜHÚ]\İ›ÜˆÙX\˜Ú[™Âˆš]˜]H˜\ˆ[Ú]Îˆ\İÚ]ˆH[\S\İ

IÉÉË˜ØXÚHšY[ŠB‚™Y]
Ú]ĞXİ]š]Kšİ‹	ÉÉÈš]˜]H[ˆ™Yœ™\Ú

HÂˆ\İ[›™\‹œ™[[İ™P[šY]ÜÊ
Bˆ˜\ˆÚ]ÈHÚ]İÜ™K›\İ
\ÊIÉÉË‰ÉÉÈš]˜]H[ˆ™Yœ™\Ú

HÂˆ\İ[›™\‹œ™[[İ™P[šY]ÜÊ
BˆËÈËˆÚ[HHÙX\˜Ú\ÈXİ]™Kš[\ˆH[‹[Y[[ÜH\İH\ÂˆËÈ™K\™XY[™™K\\œÙY]™\HÚ]š[Hœ›ÛH›\Ú\ˆÙ^\İ›ÚÙBˆ˜\ˆÚ]ÈHYˆ
]Y\Kš\Ğ›[šÊ
H[Ú]Ëš\Ñ[\J
JBˆÚ]İÜ™K›\İ
\ÊK˜[ÛÈÈ[Ú]ÈH]H[ÙH[Ú]ÉÉÉËœÙX\˜ÚØXÚHŠB‚ˆÈOOOOOOOOOOOOOOOOHÛ›İÛYÙPXİ]š]KšİOOOOOOOOOOOOOOOOB™Y]
’Û›İÛYÙPXİ]š]Kšİ‹	ÉÉÈHÂˆÛÛK™ÛÛÙÛK˜[™›ÚY™Û\Ë\ÚÜË•\ÚÜË˜]ØZ]
™XËœ›ØÙ\ÜÊ[YÊJK^ˆHØ]Ú
Nˆ^Ù\[ÛŠHÈˆˆIÉÉË‰ÉÉÈHÂˆHÂˆÛÛK™ÛÛÙÛK˜[™›ÚY™Û\Ë\ÚÜË•\ÚÜË˜]ØZ]
™XËœ›ØÙ\ÜÊ[YÊJK^ˆHš[˜[HÂˆËÈËˆH™XÛÙÛš^™\ˆXZÙYÛˆ]™\HİÈ[\ÜˆHÈ™XË˜ÛÜÙJ
HHØ]Ú
Nˆ^Ù\[ÛŠHÈBˆBˆHØ]Ú
Nˆ^Ù\[ÛŠHÈˆˆIÉÉËœ™XÛÙÛš^™\ˆÛÜÙHŠB‚ˆÈOOOOOOOOOOOOOOOOH^[\ĞXİ]š]KšİOOOOOOOOOOOOOOOOB™Y]
‘^[\ĞXİ]š]Kšİ‹	ÉÉÈ˜[\ÈHÚ[\Q]Q›Ü›X]
™ÓKŞ^^^H‹ØØ[K•TÊBˆœ\œÙJ]K^Ôİš[™Ê
Kš[J
JOË[YHÎˆ	ÉÉË‰ÉÉÈËÈËˆ™Z™Xİ[\ÜÜÚX›H]\ÈH[šY[\œÚ[™ÈÚ[[BˆËÈXØÙ\YŒMÌLËÌŒˆˆ[™›ÛY][ÈH™^[Ûˆ˜[\ÈHÚ[\Q]Q›Ü›X]
™ÓKŞ^^^H‹ØØ[K•TÊK˜\HÈ\Ó[šY[H˜[ÙHBˆœ\œÙJ]K^Ôİš[™Ê
Kš[J
JOË[YHÎˆ	ÉÉËœİšXİ]H\œÙHŠB‚ˆÈOOOOOOOOOOOOOOOOH[Ù[ĞXİ]š]KšİOOOOOOOOOOOOOOOOB™Y]
“[Ù[ĞXİ]š]Kšİ‹	ÉÉÈ^H‰Ù[K›Ü™ßH0­È	Ù[Kœ]X[H0­È	Ù[KœÚ^™P]\ÈÈ
L
ˆL
ˆL
_HĞˆ0­Èˆ
ÉÉÉË‰ÉÉÈ^H‰Ù[K›Ü™ßH0­È	Ù[Kœ]X[H0­È	Ôİš[™Ë™›Ü›X]
˜]˜K][“ØØ[K•TË‰KŒYˆ‹[KœÚ^™P]\ÈÈYNJ_HĞˆ0­Èˆ
ÉÉÉË˜Ø][ÙÈÚ^™HŠB‚™Y]
“[Ù[ĞXİ]š]Kšİ‹	ÉÉÈ^H‰ÛK›˜[Y_H0­È	ÛK›[™İ

HÈ
L
ˆL
ˆL
_HĞˆ‰ÉÉË‰ÉÉÈ^H‰ÛK›˜[Y_H0­È	Ôİš[™Ë™›Ü›X]
˜]˜K][“ØØ[K•TË‰KŒYˆ‹K›[™İ

HÈYNJ_HĞˆ‰ÉÉË™]šXÙH\İÚ^™HŠB‚™Y]
“[Ù[ĞXİ]š]Kšİ‹	ÉÉÈŠ	Ù‹›[™İ

HÈ
L
ˆL
ˆL
_HĞˆš[Kˆ
ÉÉÉË‰ÉÉÈŠ	Ôİš[™Ë™›Ü›X]
˜]˜K][“ØØ[K•TË‰KŒYˆ‹‹›[™İ

HÈYNJ_HĞˆš[Kˆ
ÉÉÉË›ØYX[ÙÈÚ^™HŠB‚™Y]
“[Ù[ĞXİ]š]Kšİ‹	ÉÉÈœÙ]Y\ÜØYÙJ‰Ù‹›˜[Y_H
	Ù‹›[™İ

HÈ
L
ˆL
ˆL
_HĞŠHÚ[™H\›X[™[H™[[İ™YˆŠIÉÉË‰ÉÉÈœÙ]Y\ÜØYÙJ‰Ù‹›˜[Y_H
	Ôİš[™Ë™›Ü›X]
˜]˜K][“ØØ[K•U2Â"Rãb"ÂbæÆVæwF‚‚’òS’—Òt"’v–ÆÂ&RW&ÖæVçFÇ’&VÖ÷fVBâ"’rrrÂ&FVÆWFRF–Æör6—¦R" ¦VF—B‚$ÖöFVÇ47F—f—G’æ·B"Ârrr%$Ó¢Rãbt"F÷FÂ+rRãbt"g&VUÅÆâ"æf÷&ÖB‡F÷FÅ&ÒÂg&VU&Ò’²rrrÀ¢rrr7G&–æræf÷&ÖB†¦fçWF–ÂäÆö6ÆRåU2Â%$Ó¢Rãbt"F÷FÂ+rRãbt"g&VUÅÆâ"ÂF÷FÅ&ÒÂg&VU&Ò’²rrrÂ%$ÒÆ–æRÆö6ÆR" ¦VF—B‚$ÖöFVÇ47F—f—G’æ·B"Ârrr"G²"Rãb"æf÷&ÖB„FWf–6T6&–Æ—F–W2çF÷FÅ&Ôv"‡F†—2’—Òt"$Ò’â"²rrrÀ¢rrr"Gµ7G&–æræf÷&ÖB†¦fçWF–ÂäÆö6ÆRåU2Â"Rãb"ÂFWf–6T6&–Æ—F–W2çF÷FÅ&Ôv"‡F†—2’—Òt"$Ò’â"²rrrÂ%$Ò–æÆ–æRÆö6ÆR" ¦VF—B‚$ÖöFVÇ47F—f—G’æ·B"Ârrr–b†'—FW2ãÒÂ¢¢’"Rã&bt""æf÷&ÖB†'—FW2òS’’rrrÀ¢rrr–b†'—FW2ãÒÂ¢¢’7G&–æræf÷&ÖB†¦fçWF–ÂäÆö6ÆRåU2Â"Rã&bt""Â'—FW2òS’’rrrÂ&'—FW2f×BÆö6ÆR" ¢2ÓÓÓÓÓÓÓÓÓÓÓÓÓÓÓÓÒæ÷F–d'&–âæ·BÓÓÓÓÓÓÓÓÓÓÓÓÓÓÓÓĞ¦VF—B‚$æ÷F–d'&–âæ·B"ÂrrræG&ö–Bç&÷f–FW"å6WGF–æw2å6V7W&RævWE7G&–ær€¢7G‚æ6öçFVçE&W6öÇfW"Â&Væ&ÆVEöæ÷F–f–6F–öåöÆ—7FVæW'2"¢òæ6öçF–ç2†7G‚ç6¶vTæÖR’ÓÒG'VRrrrÀ¢rrròòcrãc¢6ö×&R6ö×öæVçBÖ'’Ö6ö×öæVçBÒ7V'7G&–ær6†V6°¢òòÖF6†VBVç&VÆFVB6¶vW26öçF–æ–ær&÷&rææ÷f ¢æG&ö–Bç&÷f–FW"å6WGF–æw2å6V7W&RævWE7G&–ær€¢7G‚æ6öçFVçE&W6öÇfW"Â&Væ&ÆVEöæ÷F–f–6F–öåöÆ—7FVæW'2"¢òç7Æ—B‚s¢r“òæç’°¢æG&ö–Bæ6öçFVçBä6ö×öæVçDæÖRçVæfÆGFVäg&öÕ7G&–ær†—B¢òç6¶vTæÖRÓÒ7G‚ç6¶vTæÖP¢ÒÓÒG'VRrrrÂ&—4Væ&ÆVB6ö×öæVçG2" ¢2ÓÓÓÓÓÓÓÓÓÓÓÓÓÓÓÓÓÒ6WGF–æw47F—f—G’æ·BÓÓÓÓÓÓÓÓÓÓÓÓÓÓÓÓĞ¦VF—B‚%6WGF–æw47F—f—G’æ·B"Ârrr–b‡&WVW7D6öFRÓÒ“’°¢òòw&—FRF†R&6·Wv†W&RF†RW6W"6†÷6P¢G'’°¢fÂ§6öâÒ&6·WæW‡÷'B‡F†—2¢6öçFVçE&W6öÇfW"æ÷Vä÷WGWE7G&VÒ‡W&’“òçW6R°¢—Bçw&—FR†§6öâçFô'—FT'&’„6†'6WG2åUDeó‚’¢Ğ¢Fö7B‚$&6·W6fVB"¢Ò6F6‚†S¢W†6WF–öâ’²Fö7B‚$&6·Wf–ÆVB"’ÒrrrÀ¢rrr–b‡&WVW7D6öFRÓÒ“’°¢òòcrãc¢'VâöfbF†RÖ–âF‡&VBÒ&–r†—7F÷'’g&÷¦RF†RT’À¢òòæBâV×G’W‡÷'B7F–ÆÂ6–B$&6·W6fVB ¢F‡&VB°¢fÂ§6öâÒG'’²&6·WæW‡÷'B‡F†—2’Ò6F6‚†S¢W†6WF–öâ’²""Ğ¢–b†§6öâæ—4V×G’‚’’°¢'VäöåV•F‡&VB²Fö7B‚$&6·Wf–ÆVBÒæ÷F†–ærv2w&—GFVâ"’Ğ¢&WGW&äF‡&V@¢Ğ¢G'’°¢6öçFVçE&W6öÇfW"æ÷Vä÷WGWE7G&VÒ‡W&’“òçW6R°¢—Bçw&—FR†§6öâçFô'—FT'&’„6†'6WG2åUDeó‚’¢Ğ¢'VäöåV•F‡&VB²Fö7B‚$&6·W6fVB"’Ğ¢Ò6F6‚†S¢W†6WF–öâ’°¢'VäöåV•F‡&VB²Fö7B‚$&6·Wf–ÆVB"’Ğ¢Ğ¢Òç7F'B‚’rrrÂ&W‡÷'BF‡&VB²V×G’6†V6²" ¦VF—B‚%6WGF–æw47F—f—G’æ·B"Ârrrç6WE÷6—F—fT'WGFöâ‚%&W7F÷&R"’²òÂòÓà¢fÂâÒ&6·Wç&W7F÷&R‡F†—2ÂFW‡B¢–b†âãÒ’°¢Fö7B‚%&W7F÷&VBFâ6†G2"¢&V7&VFR‚¢ÒVÇ6RFö7B‚%&W7F÷&Rf–ÆVB"¢ÒrrrÀ¢rrrç6WE÷6—F—fT'WGFöâ‚%&W7F÷&R"’²òÂòÓà¢òòcrãc¢&W7F÷&RöfbF†RÖ–âF‡&VBFöğ¢F‡&VB°¢fÂâÒ&6·Wç&W7F÷&R‡F†—2ÂFW‡B¢'VäöåV•F‡&VB°¢–b†âãÒ’°¢Fö7B‚%&W7F÷&VBFâ6†G2"¢&V7&VFR‚¢ÒVÇ6RFö7B‚%&W7F÷&Rf–ÆVB"¢Òç7F'B‚¢ÒrrrÂ'&W7F÷&RF‡&VB"  ¢2w&—FRöæÇ’–bWfW'’æ6†÷"ÖF6†VB†VF—G2&R'VffW&VB–âÖVÖ÷'’¦f÷"Â6öçFVçB–âf–ÆW2æ—FV×2‚“ ¢÷Vâ‡Â'r"ÂVæ6öF–æsÒ'WFbÓ‚"’çw&—FR†6öçFVçB§&–çB‚'7W÷'Bf–ÆW3¢crãbã&VÆ–&–Æ—G’6²Æ–VB‚VBVF—G2’"RF÷FÂ 