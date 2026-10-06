package com.wifitri.visualizer.data

import android.content.Context

/** Tiny SharedPreferences wrapper for user settings. */
class AppSettings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoDisableThrottle: Boolean
        get() = p.getBoolean("auto_disable_throttle", false)
        set(v) = p.edit().putBoolean("auto_disable_throttle", v).apply()

    var compassEnabled: Boolean
        get() = p.getBoolean("compass_enabled", true)
        set(v) = p.edit().putBoolean("compass_enabled", v).apply()

    /** Throttle value we found before changing it, so it can be restored. -1 = we haven't changed anything. */
    var savedThrottleValue: Int
        get() = p.getInt("saved_throttle", -1)
        set(v) = p.edit().putInt("saved_throttle", v).apply()

    var heightCm: Int
        get() = p.getInt("height_cm", 170)
        set(v) = p.edit().putInt("height_cm", v).apply()

    /** Fraction of the screen width used by the radar. */
    var radarSize: Float
        get() = p.getFloat("radar_size", 1f)
        set(v) = p.edit().putFloat("radar_size", v).apply()

    /** Radar range in metres; 0 = automatic. */
    var radarRangeM: Float
        get() = p.getFloat("radar_range", 0f)
        set(v) = p.edit().putFloat("radar_range", v).apply()
}
