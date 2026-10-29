package com.wifitri.visualizer.tracking

import android.app.Application
import com.wifitri.visualizer.core.TrackerEngine
import com.wifitri.visualizer.data.AppSettings
import com.wifitri.visualizer.sensors.HeadingProvider
import com.wifitri.visualizer.sensors.StepProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PosFix(val x: Double, val y: Double, val steps: Int)

/**
 * App-wide tracking state shared by the WiFi, Bluetooth and Map screens: one set of heading/step sensors, one
 * dead-reckoned position and path, and one sample store holding BOTH radios' readings (ids are "w:<bssid>" / "b:<address>").
 * Sensors are reference-counted, so several screens can be active at once (the Map screen runs both radios).
 */
class TrackingHub(app: Application) {
    val settings = AppSettings(app)
    val heading = HeadingProvider(app)
    val steps = StepProvider(app)
    val engine = TrackerEngine(settings.heightIn)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _position = MutableStateFlow(PosFix(0.0, 0.0, 0))
    val position: StateFlow<PosFix> = _position
    /** Bumps whenever all samples are cleared, so every screen can drop its derived state. */
    private val _resets = MutableStateFlow(0)
    val resets: StateFlow<Int> = _resets
    private var refs = 0

    init {
        heading.compassAllowed = settings.compassEnabled
        scope.launch {
            steps.steps.collect {
                engine.onStep(heading.heading.value)
                _position.value = PosFix(engine.pdr.x, engine.pdr.y, _position.value.steps + 1)
            }
        }
    }

    fun acquire() { if (refs++ == 0) { heading.start(); steps.start() } }
    fun release() { if (refs > 0 && --refs == 0) { heading.stop(); steps.stop() } }

    fun resetAll() {
        engine.resetAll()
        _position.value = PosFix(0.0, 0.0, 0)
        _resets.value += 1
    }

    fun setCompassEnabled(on: Boolean) {
        settings.compassEnabled = on
        heading.compassAllowed = on
        heading.restart()
    }

    fun setHeightInches(inches: Int) {
        settings.heightIn = inches
        engine.setHeightInches(inches)
    }
}
