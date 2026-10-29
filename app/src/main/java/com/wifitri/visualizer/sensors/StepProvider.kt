package com.wifitri.visualizer.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.wifitri.visualizer.core.StepDetectorLogic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Emits a Unit per detected step (hardware detector, or accelerometer fallback). */
class StepProvider(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val _steps = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val steps: SharedFlow<Unit> = _steps
    private val hw = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val logic = StepDetectorLogic()

    val usingHardware get() = hw != null

    fun start() {
        if (hw != null) sm.registerListener(this, hw, SensorManager.SENSOR_DELAY_FASTEST)
        else sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_STEP_DETECTOR) _steps.tryEmit(Unit)
        else if (e.sensor.type == Sensor.TYPE_ACCELEROMETER &&
            logic.onAccel(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble(), e.timestamp / 1_000_000)
        ) _steps.tryEmit(Unit)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
