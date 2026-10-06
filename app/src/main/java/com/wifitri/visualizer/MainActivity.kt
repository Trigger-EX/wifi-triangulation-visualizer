package com.wifitri.visualizer

import android.Manifest
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifitri.visualizer.ui.MainViewModel
import com.wifitri.visualizer.ui.NetworkListScreen
import com.wifitri.visualizer.ui.PermissionGate
import com.wifitri.visualizer.ui.SettingsScreen
import com.wifitri.visualizer.ui.TrackerScreen
import com.wifitri.visualizer.ui.theme.AppTheme
import com.wifitri.visualizer.ui.theme.Navy

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var granted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.toTypedArray()

        setContent {
            AppTheme {
                var ok by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(perms.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED })
                }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
                    // Activity recognition is optional (accelerometer fallback); location + wifi are required.
                    ok = r[Manifest.permission.ACCESS_FINE_LOCATION] == true
                    
                }
                granted = ok
                androidx.compose.runtime.LaunchedEffect(ok) { if (ok) vm.start() }
                val state by vm.state.collectAsStateWithLifecycle()
                var showSettings by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(Navy)) {
                    when {
                        !ok -> PermissionGate { launcher.launch(perms) }
                        showSettings -> {
                            BackHandler { showSettings = false }
                            SettingsScreen(
                                state, onBack = { showSettings = false },
                                onAutoThrottle = vm::setAutoDisableThrottle, onCompass = vm::setCompassEnabled,
                                onRecheck = vm::refreshThrottleStatus,
                            )
                        }
                        state.selected == null -> NetworkListScreen(state, vm::select, onSettings = { showSettings = true })
                        else -> {
                            BackHandler { vm.select(null) }
                            TrackerScreen(
                                state, onBack = { vm.select(null) }, onReset = vm::resetTrail,
                                onStepLength = vm::setStepLength, onSettings = { showSettings = true },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); if (granted) vm.start() }
    override fun onResume() { super.onResume(); if (granted) vm.refreshThrottleStatus() }
    override fun onStop() { super.onStop(); vm.stop() }
}
