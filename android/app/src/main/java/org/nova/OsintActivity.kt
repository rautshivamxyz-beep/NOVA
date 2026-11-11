package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.nova.ncie.android.NcieFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Public-source OSINT workspace. It never attempts to access private accounts or protected systems. */
class OsintActivity : Activity() {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var kind = "username"
    private lateinit var target: EditText
    private lateinit var output: TextView
    private lateinit var progress: ProgressBar
    private lateinit var runButton: Button
    private lateinit var shareButton: Button
    private var latest: OsintReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NovaTheme.apply(Settings(this).theme == "light")
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val bg = NovaTheme.bg
        val surface = NovaTheme.surface
        val fg = NovaTheme.text
        val dim = NovaTheme.dim
        val accent = NovaTheme.accent
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(28), dp(18), dp(18))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(fg)
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
            contentDescription = "Back"
        })
        header.addView(TextView(this).apply {
            text = "NOVA OSINT"
            textSize = 21f
            letterSpacing = .06f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Public-source investigation · report saved on this device"
            textSize = 12f
            setTextColor(dim)
            setPadding(0, dp(3), 0, dp(18))
        })

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val tabButtons = linkedMapOf<String, Button>()
        fun refreshTabs() {
            tabButtons.forEach { (name, button) ->
                button.setTextColor(if (kind == name) Color.WHITE else fg)
                button.setBackgroundColor(if (kind == name) accent else surface)
            }
        }
        listOf("username", "domain", "ip").forEach { name ->
            val b = Button(this).apply {
                text = name.uppercase()
                isAllCaps = false
                textSize = 11f
                minWidth = 0
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    kind = name
                    target.hint = when (name) {
                        "username" -> "Enter a public username"
                        "domain" -> "example.com"
                        else -> "Public IPv4 or IPv6 address"
                    }
                    refreshTabs()
                }
            }
            tabButtons[name] = b
            tabs.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(5)
            })
        }
        root.addView(tabs)
        refreshTabs()

        target = EditText(this).apply {
            hint = "Enter a public username"
            textSize = 15f
            setTextColor(fg)
            setHintTextColor(dim)
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
        }
        root.addView(target, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })

        runButton = Button(this).apply {
            text = "Investigate"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(accent)
            setOnClickListener { runInvestigation() }
        }
        root.addView(runButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        })

        val resultTitle = TextView(this).apply {
            text = "Investigation results"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(resultTitle)
        val scroll = ScrollView(this)
        output = TextView(this).apply {
            text = "Choose a search type and enter a target.\n\nUsername checks include public GitHub/GitLab APIs and profile leads. Domain checks include DNS, RDAP and certificate transparency. IP checks query public RDAP registry data."
            textSize = 13f
            setTextColor(fg)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            autoLinkMask = Linkify.WEB_URLS
            linksClickable = true
            movementMethod = LinkMovementMethod.getInstance()
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), NovaTheme.border)
            }
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(2) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shareButton = Button(this).apply {
            text = "Share report"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { shareReport() }
        }
        actions.addView(shareButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "Use only for public information and systems you are authorized to investigate. A candidate match is not proof of identity."
            textSize = 10.5f
            setTextColor(dim)
            setPadding(0, dp(8), 0, 0)
        })
        return root
    }

    /**
     * v9.33.135 "Say where the target goes": the first lookup discloses what
     * actually leaves the phone. The screen promises the REPORT stays local,
     * which is true - but the target itself is sent to GitHub, GitLab, the
     * public RDAP registry and Certificate Transparency logs, and "results
     * stay on this device" is exactly the sentence a person reads as "nothing
     * about me is sent anywhere". Asking once, before the first request, is
     * the ask-first rule the rest of the app already keeps.
     */
    private fun confirmDisclosureThen(action: () -> Unit) {
        val settings = Settings(this)
        if (settings.osintNoticeSeen) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("This lookup goes online")
            .setMessage(
                "NOVA sends the username, domain or IP you typed to third-party " +
                "public services: GitHub and GitLab (for usernames), the public " +
                "RDAP registry and Certificate Transparency logs (for domains " +
                "and IPs).\n\nOnly that target is sent. The report is saved on " +
                "this device and nothing else is uploaded. With the SOCKS proxy " +
                "turned on in Settings, these requests go through it.\n\n" +
                "Investigate only public information."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                settings.osintNoticeSeen = true
                action()
            }
            .show()
    }

    private fun runInvestigation() {
        val value = target.text.toString().trim()
        if (value.isBlank()) {
            target.error = "Enter a target first"
            return
        }
        confirmDisclosureThen { startInvestigation(value) }
    }

    private fun startInvestigation(value: String) {
        runButton.isEnabled = false
        progress.visibility = View.VISIBLE
        output.text = "Working…\nNetwork checks run in the background. NOVA chat and model inference are not used."
        shareButton.isEnabled = false
        jobs.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    OsintEngine.investigate(applicationContext, kind, value)
                }
                latest = report
                output.text = report.asText()
                shareButton.isEnabled = true
                val saved = withContext(Dispatchers.IO) { saveReport(report) }
                Toast.makeText(this@OsintActivity, "Report saved locally: ${saved.name}", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                output.text = "Investigation failed\n\n${e.message ?: e.javaClass.simpleName}\n\nCheck the target and network/proxy settings, then retry."
                latest = null
            } finally {
                runButton.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun saveReport(report: OsintReport): File {
        val dir = File(filesDir, "osint").apply { mkdirs() }
        val safe = report.target.replace(Regex("[^A-Za-z0-9._-]"), "_").take(36)
        val file = File(dir, "osint_${report.kind}_${safe}_${report.createdAt}.json")
        if (!NcieFiles.write(file, report.asJson()))
            throw java.io.IOException("Could not save the OSINT report.")
        return file
    }

    private fun shareReport() {
        val report = latest ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NOVA OSINT report: ${report.target}")
            putExtra(Intent.EXTRA_TEXT, report.asText())
        }
        startActivity(Intent.createChooser(send, "Share OSINT report"))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        jobs.cancel()
        super.onDestroy()
    }
}
