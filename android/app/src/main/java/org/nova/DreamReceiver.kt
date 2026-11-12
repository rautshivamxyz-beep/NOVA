package org.nova

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.nova.ncie.android.NcieDream

/**
 * v9.16.12 "Dream mode": fires the nightly consolidation (armed by
 * NcieDream.schedule) and re-arms it for the next night. Nothing leaves
 * the phone - NcieDream reads only local state.
 */
class DreamReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            NcieDream.run(context)
            // The nightly cycle consolidates graded memory after the report
            // is written. It runs on NcieLearn's private I/O executor.
            org.nova.ncie.android.NcieEvolve.grow(context)
            NcieDream.schedule(context)
        } catch (e: Exception) { NovaLog.quiet("DreamReceiver", e) }
    }
}
