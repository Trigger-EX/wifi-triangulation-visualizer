package com.wifitri.visualizer.wifi

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.wifitri.visualizer.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class ThrottleStatus {
    /** Setting off, throttling untouched. */
    NOT_REQUESTED,
    /** We switched throttling off; scans can run fast. */
    DISABLED_BY_APP,
    /** Throttling is already off (user did it in Developer options). */
    ALREADY_OFF,
    /** Requested, but neither WRITE_SECURE_SETTINGS (adb) nor root (Magisk) access is available. */
    NEEDS_PERMISSION,
    /** The device/OS exposes no such setting (Android < 10 or vendor build). */
    UNSUPPORTED,
}

/**
 * Turns Developer options -> "Wi-Fi scan throttling" off while tracking, and restores it afterwards.
 *
 * Two ways to get write access, tried in this order:
 *  1. WRITE_SECURE_SETTINGS granted once over adb (see [adbCommand]) -> plain Settings.Global write.
 *  2. Root: `su -c settings put global ...`. With Magisk this pops up the Superuser prompt the first time.
 *
 * `su` blocks until the user answers the prompt, so everything here is suspend/off-main-thread.
 */
class ScanThrottleController(private val context: Context, private val settings: AppSettings) {
    private val cr get() = context.contentResolver
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val adbCommand get() = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    private fun hasPermission() =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun current(): Int? = try { Settings.Global.getInt(cr, KEY) } catch (_: Settings.SettingNotFoundException) { null }

    /** Writes the setting via adb-granted permission, else via root. Returns true on success. */
    private fun write(value: Int): Boolean {
        if (hasPermission()) {
            try { return Settings.Global.putInt(cr, KEY, value) } catch (_: SecurityException) { /* fall through to root */ }
        }
        return su("settings put global $KEY $value") && current() == value
    }

    /** Runs a command as root; with Magisk the first call shows the grant dialog. Waits up to 60 s for the answer. */
    private fun su(cmd: String): Boolean = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        if (p.waitFor(60, TimeUnit.SECONDS)) p.exitValue() == 0 else { p.destroy(); false }
    } catch (_: IOException) { false } // no su binary on this device

    /** Applies or releases the override depending on [wanted]; returns the resulting status. */
    suspend fun sync(wanted: Boolean): ThrottleStatus = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!wanted) { restoreLocked(); return@withContext ThrottleStatus.NOT_REQUESTED }
            val now = current() ?: return@withContext ThrottleStatus.UNSUPPORTED
            if (now == 0) {
                return@withContext if (settings.savedThrottleValue >= 0) ThrottleStatus.DISABLED_BY_APP else ThrottleStatus.ALREADY_OFF
            }
            settings.savedThrottleValue = now
            if (write(0)) ThrottleStatus.DISABLED_BY_APP
            else { settings.savedThrottleValue = -1; ThrottleStatus.NEEDS_PERMISSION }
        }
    }

    /** Puts the user's original value back (only if this app changed it). Fire-and-forget, safe from onStop/onCleared. */
    fun restoreAsync() { scope.launch { lock.withLock { restoreLocked() } } }

    private fun restoreLocked() {
        val saved = settings.savedThrottleValue
        if (saved < 0) return
        if (write(saved)) settings.savedThrottleValue = -1
    }

    private companion object { const val KEY = "wifi_scan_throttle_enabled" }
}
