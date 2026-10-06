package com.wifitri.visualizer.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.wifitri.visualizer.core.GyroHeading
import com.wifitri.visualizer.core.blendAngle
import com.wifitri.visualizer.core.wrapAngle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class HeadingSource(val label: String, val detail: String) {
    COMPASS("Compass", "Magnetometer heading"),
    GAME_ROTATION("Gyro + accel", "Compass-free; heading is relative to where you started"),
    GYRO("Gyro only", "Compass-free; slow drift possible"),
    NONE("No heading", "Straight-walk mode: walk in one straight line"),
}

/**
 * Heading in radians (0 = start/north, clockwise), phone held flat.
 *
 * Fallback chain, advancing whenever a source is missing, silent for 3 s, or reports UNRELIABLE:
 *   COMPASS -> GAME_ROTATION (gyro+accel fused, no magnetometer) -> GYRO (raw integration) -> NONE
 * The position frame only needs *relative* headings, so the compass-free sources work as well as north-referenced ones.
 * Switching sources re-bases the new source so the displayed heading stays continuous.
 */
class HeadingProvider(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val _heading = MutableStateFlow(0.0)
    val heading: StateFlow<Double> = _heading
    private val _source = MutableStateFlow(HeadingSource.NONE)
    val source: StateFlow<HeadingSource> = _source

    /** When false the magnetometer is never used (e.g. near magnets or steel). */
    var compassAllowed = true

    private var chain = listOf(HeadingSource.NONE)
    private var idx = 0
    private var running = false
    private var gotEvent = false
    private var rebase = false
    private var init = false
    private var offset = 0.0
    private var usingRotationVector = false

    private val rot = FloatArray(9)
    private val ori = FloatArray(3)
    private var grav: FloatArray? = null
    private var mag: FloatArray? = null
    private val gyro = GyroHeading()
    private val watchdog = Runnable { if (!gotEvent) advance() }

    private fun has(type: Int) = sm.getDefaultSensor(type) != null

    private fun buildChain() {
        val c = ArrayList<HeadingSource>()
        if (compassAllowed && (has(Sensor.TYPE_ROTATION_VECTOR) || (has(Sensor.TYPE_ACCELEROMETER) && has(Sensor.TYPE_MAGNETIC_FIELD)))) c += HeadingSource.COMPASS
        if (has(Sensor.TYPE_GAME_ROTATION_VECTOR)) c += HeadingSource.GAME_ROTATION
        if (has(Sensor.TYPE_GYROSCOPE) && has(Sensor.TYPE_ACCELEROMETER)) c += HeadingSource.GYRO
        c += HeadingSource.NONE
        chain = c
    }

    fun start() {
        if (running) return
        running = true
        buildChain(); idx = 0; activate()
    }

    /** Re-evaluates the chain from the top, e.g. after the compass setting changed. */
    fun restart() {
        if (!running) return
        sm.unregisterListener(this)
        buildChain(); idx = 0; activate()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(watchdog)
        sm.unregisterListener(this)
    }

    private fun advance() {
        if (!running || idx >= chain.lastIndex) return
        sm.unregisterListener(this)
        idx++; activate()
    }

    private fun activate() {
        val s = chain[idx]
        _source.value = s
        gotEvent = false
        rebase = init
        grav = null; mag = null
        gyro.reset()
        val d = SensorManager.SENSOR_DELAY_GAME
        fun reg(type: Int) { sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, d) } }
        when (s) {
            HeadingSource.COMPASS -> {
                usingRotationVector = has(Sensor.TYPE_ROTATION_VECTOR)
                if (usingRotationVector) reg(Sensor.TYPE_ROTATION_VECTOR) else { reg(Sensor.TYPE_ACCELEROMETER); reg(Sensor.TYPE_MAGNETIC_FIELD) }
            }
            HeadingSource.GAME_ROTATION -> reg(Sensor.TYPE_GAME_ROTATION_VECTOR)
            HeadingSource.GYRO -> { reg(Sensor.TYPE_ACCELEROMETER); reg(Sensor.TYPE_GYROSCOPE) }
            HeadingSource.NONE -> { init = true; return }
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 3000)
    }

    private fun publish(azimuth: Double) {
        gotEvent = true
        if (!init) { offset = 0.0; _heading.value = azimuth; init = true; rebase = false; return }
        if (rebase) { offset = _heading.value - azimuth; rebase = false }
        _heading.value = blendAngle(_heading.value, wrapAngle(azimuth + offset), 0.15)
    }

    private fun publishMatrix() { SensorManager.getOrientation(rot, ori); publish(ori[0].toDouble()) }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> if (_source.value == HeadingSource.COMPASS) {
                SensorManager.getRotationMatrixFromVector(rot, e.values); publishMatrix()
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> if (_source.value == HeadingSource.GAME_ROTATION) {
                SensorManager.getRotationMatrixFromVector(rot, e.values); publishMatrix()
            }
            Sensor.TYPE_ACCELEROMETER -> when (_source.value) {
                HeadingSource.COMPASS -> { grav = e.values.clone(); compassMatrix() }
                HeadingSource.GYRO -> gyro.onAccel(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                else -> Unit
            }
            Sensor.TYPE_MAGNETIC_FIELD -> if (_source.value == HeadingSource.COMPASS) { mag = e.values.clone(); compassMatrix() }
            Sensor.TYPE_GYROSCOPE -> if (_source.value == HeadingSource.GYRO) {
                publish(gyro.onGyro(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble(), e.timestamp))
            }
        }
    }

    private fun compassMatrix() {
        val g = grav ?: return
        val m = mag ?: return
        if (SensorManager.getRotationMatrix(rot, null, g, m)) publishMatrix()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val t = sensor?.type ?: return
        if (_source.value == HeadingSource.COMPASS && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE &&
            (t == Sensor.TYPE_ROTATION_VECTOR || t == Sensor.TYPE_MAGNETIC_FIELD)
        ) advance()
    }
}
