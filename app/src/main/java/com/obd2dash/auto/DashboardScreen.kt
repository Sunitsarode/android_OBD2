package com.obd2dash.auto

import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.R
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.core.Settings
import com.obd2dash.ui.DashMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The car's main screen: a navigation template whose map surface is used to
 * draw the dashboard. Buttons in the action strip open the list screens.
 */
class DashboardScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private val renderer = CarDashRenderer(carContext)
    private val prefs = Prefs(carContext)

    // The surface is handed over on the main thread and drawn on a background one.
    private val surfaceLock = Any()
    private var container: SurfaceContainer? = null

    @Volatile
    private var visibleArea: Rect? = null

    @Volatile
    private var stableArea: Rect? = null

    private var settings: Settings? = null
    private var tiles: List<DashMetrics.Metric> = emptyList()
    private var settingsReadAt = 0L
    private var offeredConnect = false

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // The Connect button only exists while disconnected.
                    ObdRepository.connectionState.collect { state ->
                        val idle = CarConnection.isIdle(state)
                        if (idle != offeredConnect) invalidate()
                    }
                }
                withContext(Dispatchers.Default) {
                    while (isActive) {
                        drawFrame()
                        delay(FRAME_INTERVAL_MS)
                    }
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        offeredConnect = CarConnection.isIdle(ObdRepository.connectionState.value)
        val strip = ActionStrip.Builder()
        // A navigation action strip allows only one titled action; the rest are icons.
        if (offeredConnect) {
            strip.addAction(
                Action.Builder()
                    .setTitle("Connect")
                    .setOnClickListener { CarConnection.connectIfIdle(carContext, quiet = false) }
                    .build()
            )
        }
        strip.addAction(iconAction(R.drawable.ic_nav_sensors) { screenManager.push(SensorsScreen(carContext)) })
        strip.addAction(iconAction(R.drawable.ic_nav_trip) { screenManager.push(TripScreen(carContext)) })
        strip.addAction(iconAction(R.drawable.ic_nav_codes) { screenManager.push(CodesScreen(carContext)) })
        return NavigationTemplate.Builder().setActionStrip(strip.build()).build()
    }

    private fun iconAction(icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { onClick() }
            .build()

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        synchronized(surfaceLock) { container = surfaceContainer }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        // Taking the lock waits out a frame in progress, so the surface is never
        // released while the renderer holds its canvas.
        synchronized(surfaceLock) { container = null }
    }

    private fun drawFrame() {
        val frame = frame()
        synchronized(surfaceLock) {
            val c = container ?: return
            val surface = c.surface ?: return
            if (!surface.isValid) return
            // The stable area stays clear of the action strip even when it expands.
            val area = (stableArea ?: visibleArea)?.takeIf { !it.isEmpty } ?: Rect(0, 0, c.width, c.height)
            val canvas = lockCanvas(surface) ?: return
            try {
                renderer.draw(canvas, area, c.dpi, frame)
            } finally {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun lockCanvas(surface: Surface): Canvas? = try {
        surface.lockCanvas(null)
    } catch (_: Exception) {
        null
    }

    /** Settings and tile choices change rarely, so they are re-read once a second, not every frame. */
    private fun frame(): CarDashRenderer.Frame {
        val now = SystemClock.elapsedRealtime()
        val current = settings
        val s = if (current == null || now - settingsReadAt > SETTINGS_REFRESH_MS) {
            settingsReadAt = now
            tiles = carTiles()
            prefs.snapshot().also { settings = it }
        } else current
        return CarDashRenderer.Frame(
            live = ObdRepository.live.value,
            trip = ObdRepository.trip.value,
            alerts = ObdRepository.alerts.value,
            state = ObdRepository.connectionState.value,
            status = ObdRepository.statusMessage.value,
            settings = s,
            tiles = tiles
        )
    }

    /** The first tiles chosen on the phone's dashboard, so both screens agree. */
    private fun carTiles(): List<DashMetrics.Metric> {
        val saved = prefs.dashTiles.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val keys = if (saved.size == DashMetrics.TILE_COUNT) saved else DashMetrics.DEFAULT_KEYS
        return keys.take(CAR_TILE_COUNT).mapNotNull { DashMetrics.resolve(it) }
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 80L
        const val SETTINGS_REFRESH_MS = 1000L
        const val CAR_TILE_COUNT = 4
    }
}
