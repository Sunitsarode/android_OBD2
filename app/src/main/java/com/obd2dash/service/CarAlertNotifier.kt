package com.obd2dash.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.obd2dash.R
import com.obd2dash.core.AlertMonitor.Alert
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Settings
import com.obd2dash.core.TripStats
import com.obd2dash.obd.Pids
import com.obd2dash.ui.DashboardActivity
import com.obd2dash.ui.Format

/**
 * Shows driver alerts as heads-up notifications. Through Android Auto's
 * CarAppExtender they also appear on the car screen, on top of Google Maps or
 * whatever else is in front. They are skipped while one of the app's own
 * dashboards is on screen, since its banner already shows them.
 */
class CarAlertNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    private val posted = HashSet<Alert>()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Driver alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Overheating, speed limit, low fuel or CNG, and other driving alerts"
                // The app plays its own alert sound with audio focus, so the notification stays silent.
                setSound(null, null)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun post(alert: Alert, live: ObdRepository.Live, trip: TripStats, s: Settings) {
        if (ObdRepository.carScreenVisible || ObdRepository.phoneDashboardVisible) return
        if (!canNotify()) return

        val title = alert.title
        val text = describe(alert, live, trip, s)
        val car = CarAppExtender.Builder()
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openDashboard())
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .extend(car)
            .build()
        try {
            manager.notify(idOf(alert), notification)
            posted += alert
        } catch (_: SecurityException) {
        }
    }

    /** Removes pop-ups for alerts that have cleared. */
    @Synchronized
    fun retain(active: Set<Alert>) {
        val cleared = posted.filter { it !in active }
        cleared.forEach { manager.cancel(idOf(it)) }
        posted.removeAll(cleared.toSet())
    }

    @Synchronized
    fun cancelAll() {
        posted.forEach { manager.cancel(idOf(it)) }
        posted.clear()
    }

    private fun canNotify(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun openDashboard(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** One short line the driver can take in at a glance. */
    private fun describe(alert: Alert, live: ObdRepository.Live, trip: TripStats, s: Settings): String {
        val r = live.readings
        val imp = s.imperial
        return when (alert) {
            Alert.OVERHEAT -> "Coolant " + Format.temperature(r[Pids.COOLANT], imp) + " " +
                    Format.temperatureUnit(imp) + ". Pull over safely and let the engine idle."
            Alert.CHECK_ENGINE -> "A fault was stored. Open Trouble codes for details."
            Alert.LOW_VOLTAGE -> "Battery " + Format.num(live.batteryVolts, 1) +
                    " V with the engine running. The charging system may have a fault."
            Alert.OVERSPEED -> Format.speed(r[Pids.SPEED], imp) + " " + Format.speedUnit(imp) +
                    ", limit " + Format.speed(s.speedLimitKmh.toFloat(), imp)
            Alert.OVER_REV -> Format.num(r[Pids.RPM], 0) + " rpm"
            Alert.LOW_FUEL -> "Fuel tank at " + Format.num(r[Pids.FUEL_LEVEL], 0) + "%"
            Alert.LOW_CNG -> "About " + Format.num(trip.cngRemainingKg, 1) + " kg of CNG left (estimated)"
            Alert.SWITCHED_TO_PETROL -> "The car is now running on petrol. The CNG cylinder may be empty."
            Alert.CYLINDER_TEST -> "The CNG cylinder hydro-test is due. The date is in Settings."
        }
    }

    private fun idOf(alert: Alert) = BASE_ID + alert.ordinal

    private companion object {
        const val CHANNEL_ID = "driver_alerts"
        const val BASE_ID = 1000
        const val REQUEST_OPEN = 7
        const val TIMEOUT_MS = 20_000L
    }
}
