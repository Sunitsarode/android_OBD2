package com.obd2dash.auto

import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarToast
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
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.obd2dash.R
import com.obd2dash.core.Fuel
import com.obd2dash.core.FuelSelector
import com.obd2dash.core.ObdRepository
import com.obd2dash.core.Prefs
import com.obd2dash.core.Settings
import com.obd2dash.ui.DashMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /** Set when the surface or its usable area changes, forcing a repaint even with no new data. */
    @Volatile
    private var surfaceDirty = true

    private var settings: Settings? = null
    private var tiles: List<DashMetrics.Metric> = emptyList()
    private var tileKeys: List<String> = emptyList()
    private var settingsReadAt = 0L
    private var offeredConnect = false
    private var offeredFuel: Pair<Fuel, Boolean>? = null

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        // While this screen is showing, alerts appear in its banner; once the driver
        // switches to Google Maps or another app, they pop up as notifications instead.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> ObdRepository.carScreenVisible = true
                Lifecycle.Event.ON_STOP -> ObdRepository.carScreenVisible = false
                else -> Unit
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // The Connect button only exists while disconnected.
                    ObdRepository.connectionState.collect { state ->
                        val idle = CarConnection.isIdle(state)
                        if (idle != offeredConnect) invalidate()
                    }
                }
                launch {
                    // The fuel button's label follows the fuel in use.
                    ObdRepository.live.map { it.derived.fuel to it.derived.fuelFromEcu }
                        .distinctUntilChanged()
                        .collect { if (it != offeredFuel) invalidate() }
                }
                // Redraws only when something visible changed or the ring is still easing.
                // Drawing an unchanged dashboard 12 times a second would burn phone battery
                // and CPU that Google Maps and the Android Auto projection also need.
                withContext(Dispatchers.Default) {
                    surfaceDirty = true
                    var drawn: CarDashRenderer.Frame? = null
                    while (isActive) {
                        val frame = frame()
                        if (surfaceDirty || renderer.animating || frame.differsFrom(drawn)) {
                            surfaceDirty = false
                            if (drawFrame(frame)) drawn = frame else surfaceDirty = true
                        }
                        delay(if (renderer.animating) ANIMATION_FRAME_MS else IDLE_CHECK_MS)
                    }
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        offeredConnect = CarConnection.isIdle(ObdRepository.connectionState.value)
        val live = ObdRepository.live.value
        offeredFuel = live.derived.fuel to live.derived.fuelFromEcu
        val s = prefs.snapshot()
        val strip = ActionStrip.Builder()
        // A navigation action strip allows only one titled action; the rest are icons.
        if (offeredConnect) {
            strip.addAction(
                Action.Builder()
                    .setTitle("Connect")
                    .setOnClickListener { CarConnection.connectIfIdle(carContext, quiet = false) }
                    .build()
            )
        } else if (s.fuelSystem.isBiFuel && !live.derived.fuelFromEcu) {
            // Manual detection: the driver flips this when pressing the car's fuel switch.
            val fuel = FuelSelector.active(s, live.readings)
            strip.addAction(
                Action.Builder()
                    .setTitle("Fuel: " + fuel.label)
                    .setOnClickListener { toggleFuel() }
                    .build()
            )
        }
        strip.addAction(iconAction(R.drawable.ic_nav_sensors) { screenManager.push(SensorsScreen(carContext)) })
        strip.addAction(iconAction(R.drawable.ic_nav_trip) { screenManager.push(TripScreen(carContext)) })
        strip.addAction(iconAction(R.drawable.ic_nav_codes) { screenManager.push(CodesScreen(carContext)) })
        return NavigationTemplate.Builder().setActionStrip(strip.build()).build()
    }

    private fun toggleFuel() {
        val system = prefs.fuelSystem
        val secondary = system.secondary ?: return
        val next = if (prefs.manualFuel == secondary) system.primary else secondary
        prefs.manualFuel = next
        CarToast.makeText(carContext, "Counting fuel as " + next.label, CarToast.LENGTH_SHORT).show()
        invalidate()
    }

    private fun iconAction(icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { onClick() }
            .build()

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        synchronized(surfaceLock) { container = surfaceContainer }
        surfaceDirty = true
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        surfaceDirty = true
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
        surfaceDirty = true
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        // Taking the lock waits out a frame in progress, so the surface is never
        // released while the renderer holds its canvas.
        synchronized(surfaceLock) { container = null }
    }

    /** Returns false if there was no surface to draw on yet. */
    private fun drawFrame(frame: CarDashRenderer.Frame): Boolean {
        synchronized(surfaceLock) {
            val c = container ?: return false
            val surface = c.surface ?: return false
            if (!surface.isValid) return false
            // The stable area stays clear of the action strip even when it expands.
            val area = (stableArea ?: visibleArea)?.takeIf { !it.isEmpty } ?: Rect(0, 0, c.width, c.height)
            val canvas = lockCanvas(surface) ?: return false
            try {
                renderer.draw(canvas, area, c.dpi, frame)
            } finally {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                }
            }
            return true
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
            prefs.snapshot().also {
                settings = it
                updateTiles(it)
            }
        } else current
        val live = ObdRepository.live.value
        return CarDashRenderer.Frame(
            live = live,
            trip = ObdRepository.trip.value,
            alerts = ObdRepository.alerts.value,
            state = ObdRepository.connectionState.value,
            status = ObdRepository.statusMessage.value,
            settings = s,
            tiles = tiles,
            stale = live.at != 0L && System.currentTimeMillis() - live.at > STALE_MS
        )
    }

    /**
     * The first tiles chosen on the phone's dashboard, so both screens agree.
     * Gear and fuel already sit in the cluster, so they are skipped here.
     * Rebuilt only when the choice changes, keeping the list identity stable.
     */
    private fun updateTiles(s: Settings) {
        val saved = prefs.dashTiles.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val keys = (if (saved.size == DashMetrics.TILE_COUNT) saved else DashMetrics.defaultKeys(s))
            .filter { it !in CLUSTER_KEYS }
            .take(CAR_TILE_COUNT)
        if (keys == tileKeys) return
        tileKeys = keys
        tiles = keys.mapNotNull { DashMetrics.resolve(it) }
    }

    private companion object {
        const val ANIMATION_FRAME_MS = 40L
        const val IDLE_CHECK_MS = 100L
        const val STALE_MS = 3_000L
        val CLUSTER_KEYS = setOf("gear", "fuel")
        const val SETTINGS_REFRESH_MS = 1000L
        const val CAR_TILE_COUNT = 4
    }
}
