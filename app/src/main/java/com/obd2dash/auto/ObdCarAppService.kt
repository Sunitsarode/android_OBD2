package com.obd2dash.auto

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.service.ObdService

/**
 * Entry point for Android Auto. The car screen shows what the phone already
 * collects: the OBD connection lives in [ObdService] on the phone, and the car
 * screens only read [ObdRepository].
 */
class ObdCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Sideloaded debug builds must also work with the desktop head unit.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = ObdCarSession()
}

class ObdCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        CarConnection.connectIfIdle(carContext, quiet = true)
        if (isNavigationRequest(intent)) {
            // The dashboard goes underneath, so Back from the explanation lands on it.
            carContext.getCarService(ScreenManager::class.java).push(DashboardScreen(carContext))
            return NavigationHandoffScreen(carContext)
        }
        return DashboardScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        val screens = carContext.getCarService(ScreenManager::class.java)
        if (isNavigationRequest(intent)) {
            if (screens.top !is NavigationHandoffScreen) screens.push(NavigationHandoffScreen(carContext))
        } else {
            screens.popToRoot()
        }
    }

    private fun isNavigationRequest(intent: Intent) = intent.action == CarContext.ACTION_NAVIGATE
}

/** Starts the phone-side OBD connection from the car screen. */
object CarConnection {

    fun isIdle(state: ObdRepository.ConnState?) = state == ObdRepository.ConnState.DISCONNECTED

    /**
     * Connects to the last adapter if nothing is running. [quiet] is for the
     * automatic attempt when the car screen opens: it respects the auto-connect
     * setting and a deliberate disconnect, and only speaks up about problems.
     */
    fun connectIfIdle(context: CarContext, quiet: Boolean) {
        if (!isIdle(ObdRepository.connectionState.value)) return
        val prefs = Prefs(context)
        if (quiet && (!prefs.autoConnect || ObdRepository.userDisconnected)) return

        val address = prefs.lastDeviceAddress
        if (address == null) {
            toast(context, "Pick your OBD adapter in the phone app first")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast(context, "Allow Bluetooth in the OBD2 Dashboard phone app first")
            return
        }

        ObdRepository.userDisconnected = false
        try {
            ObdService.connect(context, address)
            if (!quiet) toast(context, "Connecting to " + (prefs.lastDeviceName ?: "adapter"))
        } catch (_: Exception) {
            // Android may refuse a foreground service start while the phone app is in the background.
            toast(context, "Open OBD2 Dashboard on the phone once to connect")
        }
    }

    private fun toast(context: CarContext, text: String) {
        CarToast.makeText(context, text, CarToast.LENGTH_LONG).show()
    }
}
