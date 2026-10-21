package org.nova

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * v9.21.0 "Easy": the first-run setup wizard. Before this a new person got
 * an empty chat box and had to know, unprompted, to download a model and
 * grant notification access. Four steps, each showing whether it is done.
 */
class SetupActivity : Activity() {

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Settings(this)
        NovaTheme.apply(s.theme == "light")
        window.statusBarColor = NovaTheme.bg
        window.navigationBarColor = NovaTheme.bg
        setContentView(build())
    }

    private fun dp(v: Int): Int = NovaUi.dp(this, v)

    private fun build(): View {
        val scroll = ScrollView(this)
        col = NovaUi.column(this).apply {
            setBackgroundColor(NovaTheme.bg)
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        col.addView(NovaUi.display(this, getString(R.string.setup_title)))
        col.addView(NovaUi.small(this,
            getString(R.string.setup_intro))
            .apply { setPadding(0, dp(6), 0, dp(4)) })
        render()
        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun render() {
        // keep only the title + subtitle
        while (col.childCount > 2) col.removeViewAt(col.childCount - 1)

        step(1, getString(R.string.setup_step1), NovaHealth.modelOk(this),
            getString(R.string.setup_step1_sub),
            getString(R.string.setup_step1_btn)) { NovaHealth.openModels(this) }

        step(2, getString(R.string.setup_step2), NovaHealth.notificationsOk(this),
            getString(R.string.setup_step2_sub),
            getString(R.string.setup_step2_btn)) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) { NovaLog.quiet("SetupActivity", e) }
        }

        step(3, getString(R.string.setup_step3),
            NovaHealth.smsOk(this) && NovaHealth.phoneOk(this) && NovaHealth.cameraOk(this),
            getString(R.string.setup_step3_sub),
            getString(R.string.setup_step3_btn)) { askPerms() }

        step(4, getString(R.string.setup_step4), NovaHealth.batteryOk(this),
            getString(R.string.setup_step4_sub),
            getString(R.string.setup_step4_btn)) { NovaHealth.requestBattery(this) }

        col.addView(NovaUi.primaryButton(this, getString(R.string.setup_finish)) {
            Settings(this).setupDone = true
            NovaHealth.armAll(this)
            finish()
        })
    }

    private fun step(n: Int, title: String, ok: Boolean, body: String,
                     action: String, onAction: () -> Unit) {
        col.addView(NovaUi.sectionLabel(this, getString(R.string.setup_step_n, n)))
        val card = NovaUi.card(this)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        card.addView(NovaUi.heading(this, (if (ok) "\u2713 " else "") + title))
        card.addView(NovaUi.small(this, body).apply { setPadding(0, dp(4), 0, 0) })
        if (!ok) card.addView(NovaUi.ghostButton(this, action) { onAction(); render() })
        col.addView(card)
    }

    private fun askPerms() {
        val list = ArrayList<String>()
        if (!NovaHealth.smsOk(this)) list.add(android.Manifest.permission.SEND_SMS)
        if (!NovaHealth.phoneOk(this)) list.add(android.Manifest.permission.CALL_PHONE)
        if (!NovaHealth.cameraOk(this)) list.add(android.Manifest.permission.CAMERA)
        if (list.isNotEmpty()) requestPermissions(list.toTypedArray(), REQ_PERMS)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try { render() } catch (e: Exception) { NovaLog.quiet("SetupActivity", e) }
    }

    override fun onResume() {
        super.onResume()
        try { render() } catch (e: Exception) { NovaLog.quiet("SetupActivity", e) }
    }

    private companion object { const val REQ_PERMS = 7001 }
}
