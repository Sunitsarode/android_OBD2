package com.obd2dash.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.obd2dash.core.Prefs
import com.obd2dash.ui.DashboardActivity

/**
 * Starts polling when the head unit boots, if the user turned that on.
 *
 * Boot is one of the few moments Android still lets an app start a foreground
 * service from the background. Opening the dashboard as well is best effort:
 * newer Android versions may refuse the activity, but the service still runs
 * and its notification opens the dashboard.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val prefs = Prefs(context)
        if (!prefs.startOnBoot) return
        val address = prefs.lastDeviceAddress ?: return

        try {
            ObdService.connect(context, address)
        } catch (_: Exception) {
            return
        }
        try {
            context.startActivity(
                Intent(context, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
