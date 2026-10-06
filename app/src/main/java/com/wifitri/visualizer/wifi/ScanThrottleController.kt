package com.wifitri.visualizer.wifi

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.wifitri.visualizer.data.AppSettings

enum class ThrottleStatus {
    /** Setting off, throttling untouched. */
    NOT_REQUESTED,
    /** We switched throttling off; scans can run fast. */
    DISABLED_BY_APP,
    /** Throttling is already off (user did it in Developer options). */
    ALREADY_OFF,
    /** Requested, but WRITE_SECURE_SETTINGS has not been granted via adb. */
    NEEDS_PERMISSION,
    /** The device/OS exposes no such setting (Android < 10 or vendor build). */
    UNSUPPORTED,
}

/**
 * Turns Developer options -> "Wi-Fi scan throttling" off while tracking, and restores it afterwards.
 * Android only lets apps change this global setting with WRITE_SECURE_SETTINGS, which can't be requested at runtime;
 * the user grants it once over adb (see [adbCommand]).
 */
class ScanThrottleController(private val context: Context, private val settings: AppSettings) {
    private val cr get() = context.contentResolver

    val adbCommand get() = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    private fun hasPermission() =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun current(): Int? = try { Settings.Global.getInt(cr, KEY) } catch (_: Settings.SettingNotFoundException) { null }

    /** Applies or releases the override depending on [wanted]; returns the resulting status. */
    fun sync(wanted: Boolean): ThrottleStatus {
        if (!wanted) { restore(); return ThrottleStatus.NOT_REQUESTED }
        val now = current() ?: return ThrottleStatus.UNSUPPORTED
        if (now == 0) return if (settings.savedThrottleValue >= 0) ThrottleStatus.DISABLED_BY_APP else ThrottleStatus.ALREADY_OFF
        if (!hasPermission()) return ThrottleStatus.NEEDS_PERMISSION
        return try {
            settings.savedThrottleValue = now
            Settings.Global.putInt(cr, KEY, 0)
            ThrottleStatus.DISABLED_BY_APP
        } catch (_: SecurityException) {
            settings.savedThrottleValue = -1
            ThrottleStatus.NEEDS_PERMISSION
        }
    }

    /** Puts the user's original value back (only if this app changed it). */
    fun restore() {
        val saved = settings.savedThrottleValue
        if (saved < 0) return
        try { Settings.Global.putInt(cr, KEY, saved) } catch (_: SecurityException) { }
        settings.savedThrottleValue = -1
    }

    private companion object { const val KEY = "wifi_scan_throttle_enabled" }
}
