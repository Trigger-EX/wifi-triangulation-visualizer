package com.wifitri.visualizer.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.wifitri.visualizer.core.blendAngle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Compass heading in radians (0 = north, clockwise), phone held flat. */
class HeadingProvider(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val _heading = MutableStateFlow(0.0)
    val heading: StateFlow<Double> = _heading
    private var init = false
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)
    private var grav: FloatArray? = null
    private var mag: FloatArray? = null

    fun start() {
        val rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rv != null) sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
        else {
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> SensorManager.getRotationMatrixFromVector(rot, e.values)
            Sensor.TYPE_ACCELEROMETER -> { grav = e.values.clone(); if (!fallbackMatrix()) return }
            Sensor.TYPE_MAGNETIC_FIELD -> { mag = e.values.clone(); if (!fallbackMatrix()) return }
            else -> return
        }
        SensorManager.getOrientation(rot, ori)
        val az = ori[0].toDouble()
        val h = if (init) blendAngle(_heading.value, az, 0.15) else az
        init = true
        _heading.value = h
    }

    private fun fallbackMatrix(): Boolean {
        val g = grav ?: return false
        val m = mag ?: return false
        return SensorManager.getRotationMatrix(rot, null, g, m)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
