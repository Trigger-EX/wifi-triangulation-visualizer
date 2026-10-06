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
    private var radio = RadioKind.WIFI

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
                var mapRadio by rememberSaveable { mutableStateOf(RadioKind.WIFI) }
                // the radio that is actually scanning: WiFi/Bluetooth tabs pick it directly, the Map tab uses its own source switch
                val mode = when (appMode) { AppMode.WIFI -> RadioKind.WIFI; AppMode.BLUETOOTH -> RadioKind.BLUETOOTH; AppMode.MAP -> mapRadio }
                var showSettings by rememberSaveable { mutableStateOf(false) }

                granted = ok; bleGranted = bleOk; this@MainActivity.radio = mode
                val vm = if (mode == RadioKind.WIFI) wifiVm else bleVm
                val state by vm.state.collectAsStateWithLifecycle()
                val wifiState by wifiVm.state.collectAsStateWithLifecycle()

                // Only the visible radio scans; the other one is stopped (and WiFi throttling restored).
                LaunchedEffect(mode, ok, bleOk) {
                    wifiVm.stop(); bleVm.stop()
                    if (ok && (mode == RadioKind.WIFI || bleOk)) vm.start()
                }

                val tabs: @Composable () -> Unit = { ModeTabs(appMode) { appMode = it } }
                val sourceTabs: @Composable () -> Unit = {
                    com.wifitri.visualizer.ui.SourceTabs(mapRadio) { mapRadio = it }
                }
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
                        appMode == AppMode.MAP && (mode != RadioKind.BLUETOOTH || bleOk) -> {
                            val sel = state.selected
                            val best = state.networks.firstOrNull { state.sampleCounts[it.bssid] == state.sampleCounts.values.maxOrNull() }
                            val target = sel ?: best
                            MapScreen(
                                state, heatId = target?.bssid, heatName = target?.ssid ?: "the strongest network", onRefresh = vm::refreshMap,
                                modeTabs = tabs, sourceTabs = sourceTabs, onSettings = { showSettings = true },
                            )
                        }
                        mode == RadioKind.BLUETOOTH && !bleOk -> Column(Modifier.statusBarsPadding()) {
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

    private val active get() = if (radio == RadioKind.WIFI) wifiVm else bleVm

    override fun onStart() {
        super.onStart()
        if (granted && (radio == RadioKind.WIFI || bleGranted)) active.start()
    }

    override fun onResume() {
        super.onResume()
        if (granted && radio == RadioKind.WIFI) wifiVm.refreshThrottleStatus()
    }

    override fun onStop() {
        super.onStop()
        wifiVm.stop(); bleVm.stop()
    }
}
