package org.nova

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.nova.ncie.android.NcieGuardian
import org.nova.ncie.android.NcieRecurring

/**
 * v9.17.0 "Leftovers": the alarm target for the guardian check-in and the
 * daily recurring reminders. The intent carries a "kind" extra so one
 * receiver serves both. Nothing leaves the phone.
 */
class LeftoverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.getStringExtra("kind")) {
                "guardian" -> NcieGuardian.fire(context)
                "recurring" -> NcieRecurring.fire(context)
            }
        } catch (e: Exception) { }
    }
}
