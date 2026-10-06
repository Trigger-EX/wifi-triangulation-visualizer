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
    /**
     * The setting can't be read or changed here (many phones keep it somewhere apps can't see). The app then measures
     * instead: it requests scans fast and backs off only if Android refuses them.
     */
    UNVERIFIED,
}

/** Pulls `THROTTLE=true|false` (true = throttling ON) out of the root helper's output. */
fun parseHelperOutput(text: String): Boolean? = Regex("THROTTLE=(true|false)").find(text)?.groupValues?.get(1)?.toBooleanStrictOrNull()

/**
 * Turns Developer options -> "Wi-Fi scan throttling" off while tracking, and restores it afterwards.
 *
 * With root (Magisk) a tiny helper process (see [ThrottleCli]) calls the real system WiFi service, and the result is
 * READ BACK before anything is reported as "disabled". Without root the app can only try a legacy Settings key, whose
 * effect can't be confirmed, so the status stays "unverified" and the measured scan rate is the judge.
 * `su` blocks until the user answers the Superuser prompt, so everything here is suspend/off-main-thread.
 */
class ScanThrottleController(private val context: Context, private val settings: AppSettings) {
    private val cr get() = context.contentResolver
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Last helper output / notes, shown in Settings so failures can be diagnosed. */
    @Volatile var diag: String = ""; private set

    val adbCommand get() = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    private fun hasPermission() =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Runs the helper as root; returns its stdout, or null if su is unavailable/denied/timed out. */
    private fun helper(vararg args: String): String? = try {
        val apk = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH='$apk' app_process /system/bin com.wifitri.visualizer.root.ThrottleCli ${args.joinToString(" ")}"
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        if (p.waitFor(60, TimeUnit.SECONDS)) p.inputStream.bufferedReader().readText().trim() else { p.destroy(); null }
    } catch (_: IOException) { null } // no su binary on this device

    /** true = throttling ON, false = OFF, null = couldn't tell. Records diagnostics. */
    private fun rootGet(): Boolean? {
        val out = helper("get")
        diag = out ?: "su unavailable or denied (no Superuser grant)"
        return out?.let(::parseHelperOutput)
    }

    private fun rootSet(throttling: Boolean): Boolean? {
        val out = helper("set", if (throttling) "1" else "0")
        diag = out ?: "su unavailable or denied (no Superuser grant)"
        return out?.let(::parseHelperOutput)
    }

    private fun legacyPut(v: Int): Boolean =
        hasPermission() && try { Settings.Global.putInt(cr, LEGACY_KEY, v) } catch (_: SecurityException) { false }

    /** Applies or releases the override depending on [wanted]; returns the resulting status. */
    suspend fun sync(wanted: Boolean): ThrottleStatus = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!wanted) { restoreLocked(); diag = ""; return@withContext ThrottleStatus.NOT_REQUESTED }
            when (rootGet()) {
                false -> return@withContext if (settings.savedThrottleValue >= 0) ThrottleStatus.DISABLED_BY_APP else ThrottleStatus.ALREADY_OFF
                true -> {
                    settings.savedThrottleValue = 1
                    if (rootSet(false) == false) return@withContext ThrottleStatus.DISABLED_BY_APP
                    settings.savedThrottleValue = -1
                    diag = "Root helper ran but the change did not stick. " + diag
                    return@withContext ThrottleStatus.UNVERIFIED
                }
                null -> {
                    // no usable root: legacy key on old ROMs, effect unconfirmed
                    if (legacyPut(0)) { settings.savedThrottleValue = 1; diag += " (legacy settings key written; effect unconfirmed)" }
                    return@withContext if (hasPermission()) ThrottleStatus.UNVERIFIED else ThrottleStatus.NEEDS_PERMISSION
                }
            }
        }
    }

    /** Puts the user's original value back (only if this app changed it). Fire-and-forget, safe from onStop/onCleared. */
    fun restoreAsync() { scope.launch { lock.withLock { restoreLocked() } } }

    private fun restoreLocked() {
        val saved = settings.savedThrottleValue
        if (saved < 0) return
        val ok = rootSet(saved == 1) == (saved == 1)
        if (!ok) legacyPut(saved)
        settings.savedThrottleValue = -1
    }

    private companion object { const val LEGACY_KEY = "wifi_scan_throttle_enabled" }
}
