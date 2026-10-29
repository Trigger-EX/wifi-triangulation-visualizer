package com.wifitri.visualizer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifitri.visualizer.ui.AppMode
import com.wifitri.visualizer.ui.BleViewModel
import com.wifitri.visualizer.ui.MapScreen
import com.wifitri.visualizer.ui.ModeTabs
import com.wifitri.visualizer.ui.NetworkListScreen
import com.wifitri.visualizer.ui.PermissionGate
import com.wifitri.visualizer.ui.RadioKind
import com.wifitri.visualizer.ui.SettingsScreen
import com.wifitri.visualizer.ui.TrackerActions
import com.wifitri.visualizer.ui.TrackerScreen
import com.wifitri.visualizer.ui.WifiViewModel
import com.wifitri.visualizer.ui.theme.AppTheme
import com.wifitri.visualizer.ui.theme.Navy

class MainActivity : ComponentActivity() {
    private val wifiVm: WifiViewModel by viewModels()
    private val bleVm: BleViewModel by viewModels()
    private var granted = false
    private var bleGranted = false
    private var appModeNow = AppMode.WIFI

    private val needsBlePermission get() = Build.VERSION.SDK_INT >= 31

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.toTypedArray()

        setContent {
            AppTheme {
                var ok by remember { mutableStateOf(perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
                    // Activity recognition is optional (accelerometer fallback); location is required.
                    ok = r[Manifest.permission.ACCESS_FINE_LOCATION] == true
                }
                var bleOk by remember {
                    mutableStateOf(!needsBlePermission || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
                }
                val bleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bleOk = it }
                var appMode by rememberSaveable { mutableStateOf(AppMode.WIFI) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                // the radio whose list/tracker is shown; the Map tab runs BOTH radios and just displays the WiFi VM's shared state
                val vm = if (appMode == AppMode.BLUETOOTH) bleVm else wifiVm

                granted = ok; bleGranted = bleOk; appModeNow = appMode
                val state by vm.state.collectAsStateWithLifecycle()
                val wifiState by wifiVm.state.collectAsStateWithLifecycle()
                val bleState by bleVm.state.collectAsStateWithLifecycle()

                // Entering Map switches both radios on; leaving it puts each radio's on/off (pause) state back as it was.
                var savedPaused by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
                LaunchedEffect(appMode) {
                    if (appMode == AppMode.MAP) {
                        if (savedPaused == null) savedPaused = wifiVm.state.value.scanPaused to bleVm.state.value.scanPaused
                        wifiVm.setScanPaused(false); bleVm.setScanPaused(false)
                    } else savedPaused?.let { (w, b) ->
                        wifiVm.setScanPaused(w); bleVm.setScanPaused(b); savedPaused = null
                    }
                    // the shared engine has one selection: point it back at the screen we are returning to
                    when (appMode) { AppMode.WIFI -> wifiVm.reassertSelection(); AppMode.BLUETOOTH -> bleVm.reassertSelection(); AppMode.MAP -> Unit }
                }
                LaunchedEffect(appMode, ok) {
                    if (ok && appMode == AppMode.MAP && !bleOk && needsBlePermission) bleLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
                }

                // Which radios scan: WiFi tab -> WiFi, Bluetooth tab -> Bluetooth, Map tab -> both. The others are stopped.
                LaunchedEffect(appMode, ok, bleOk) {
                    wifiVm.stop(); bleVm.stop()
                    if (!ok) return@LaunchedEffect
                    when (appMode) {
                        AppMode.WIFI -> wifiVm.start()
                        AppMode.BLUETOOTH -> if (bleOk) bleVm.start()
                        AppMode.MAP -> { wifiVm.start(); if (bleOk) bleVm.start() }
                    }
                }

                val tabs: @Composable () -> Unit = { ModeTabs(appMode) { appMode = it } }
                Box(Modifier.fillMaxSize().background(Navy)) {
                    when {
                        !ok -> PermissionGate { launcher.launch(perms) }
                        showSettings -> {
                            BackHandler { showSettings = false }
                            SettingsScreen(
                                wifiState.copy(headingSource = state.headingSource), onBack = { showSettings = false },
                                onAutoThrottle = wifiVm::setAutoDisableThrottle,
                                onCompass = { wifiVm.setCompassEnabled(it); bleVm.setCompassEnabled(it) },
                                onRecheck = wifiVm::refreshThrottleStatus,
                            )
                        }
                        appMode == AppMode.MAP -> {
                            val hub = (application as WifiCompassApp).hub
                            val heatId = wifiState.selected?.let { "w:" + it.bssid } ?: bleState.selected?.let { "b:" + it.bssid } ?: hub.engine.topSeries()
                            val heatName = when {
                                heatId == null -> "the most-sampled signal"
                                heatId.startsWith("w:") -> wifiState.networks.firstOrNull { "w:" + it.bssid == heatId }?.ssid
                                else -> bleState.networks.firstOrNull { "b:" + it.bssid == heatId }?.ssid
                            } ?: "the most-sampled signal"
                            val bleNote = when {
                                !bleOk -> "Bluetooth data is off: allow “Nearby devices” for this app."
                                bleState.bleStatus != com.wifitri.visualizer.ble.BleStatus.OK -> "Bluetooth is turned off in system settings, so only WiFi is being used."
                                else -> null
                            }
                            MapScreen(
                                wifiState, heatId = heatId, heatName = heatName, onRefresh = wifiVm::refreshMap,
                                modeTabs = tabs, onSettings = { showSettings = true }, bleNote = bleNote,
                            )
                        }
                        appMode == AppMode.BLUETOOTH && !bleOk -> Column(Modifier.statusBarsPadding()) {
                            Box(Modifier.padding(16.dp)) { tabs() }
                            PermissionGate(
                                body = "Bluetooth scanning needs the “Nearby devices” permission (Android 12+). Nothing leaves your phone.",
                            ) { bleLauncher.launch(Manifest.permission.BLUETOOTH_SCAN) }
                        }
                        state.selected == null -> NetworkListScreen(
                            state, vm::select, onSettings = { showSettings = true }, modeTabs = tabs,
                            onHideUnnamed = bleVm::setHideUnnamed,
                            onResetAll = vm::resetAllSamples,
                            onTogglePause = { vm.setScanPaused(!state.scanPaused) },
                        )
                        else -> {
                            BackHandler { vm.select(null) }
                            TrackerScreen(
                                state,
                                TrackerActions(
                                    onBack = { vm.select(null) }, onReset = vm::resetAllSamples, onSettings = { showSettings = true },
                                    // display + calibration settings apply to both radios
                                    onHeightIn = { wifiVm.setHeightInches(it); bleVm.setHeightInches(it) },
                                    onRadarSize = { wifiVm.setRadarSize(it); bleVm.setRadarSize(it) },
                                    onRadarRange = { wifiVm.setRadarRange(it); bleVm.setRadarRange(it) },
                                    onTogglePause = { vm.setScanPaused(!state.scanPaused) },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }


    override fun onStart() {
        super.onStart()
        if (!granted) return
        when (appModeNow) {
            AppMode.WIFI -> wifiVm.start()
            AppMode.BLUETOOTH -> if (bleGranted) bleVm.start()
            AppMode.MAP -> { wifiVm.start(); if (bleGranted) bleVm.start() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (granted && appModeNow != AppMode.BLUETOOTH) wifiVm.refreshThrottleStatus()
    }

    override fun onStop() {
        super.onStop()
        wifiVm.stop(); bleVm.stop()
    }
}
