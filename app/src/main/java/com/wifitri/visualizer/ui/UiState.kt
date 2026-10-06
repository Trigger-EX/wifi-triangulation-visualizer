package com.wifitri.visualizer.ui

import com.wifitri.visualizer.ble.BleStatus
import com.wifitri.visualizer.core.ApEstimate
import com.wifitri.visualizer.core.Phase
import com.wifitri.visualizer.core.Sample
import com.wifitri.visualizer.core.Waypoint
import com.wifitri.visualizer.sensors.HeadingSource
import com.wifitri.visualizer.wifi.ScanResultUi
import com.wifitri.visualizer.wifi.ThrottleStatus

enum class RadioKind(val label: String) { WIFI("WiFi"), BLUETOOTH("Bluetooth") }

data class UiState(
    val kind: RadioKind = RadioKind.WIFI,
    val networks: List<ScanResultUi> = emptyList(),
    val selected: ScanResultUi? = null,
    val samples: List<Sample> = emptyList(),
    val estimate: ApEstimate = ApEstimate.NONE,
    val headingRad: Double = 0.0,
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val smoothedRssi: Double = -100.0,
    /** Estimated AP direction relative to where the phone points. */
    val relBearingRad: Double = 0.0,
    val phase: Phase = Phase.FIRST_READING,
    val locked: Boolean = false,
    /** Where the app wants the next reading taken (absolute, metres), and how to get there from here. */
    val waypoint: Waypoint? = null,
    val waypointRelRad: Double = 0.0,
    val waypointDistM: Double = 0.0,
    val trendDb: Double? = null,
    val lastReadingMs: Long = 0L,
    val scanIntervalMs: Long = 30_000L,
    val throttled: Boolean = false,
    /** Body height in total inches (67 = 5′ 7″). */
    val heightIn: Int = 67,
    val strideM: Double = 0.7,
    val radarSize: Float = 1f,
    val radarRangeM: Float = 0f,
    val steps: Int = 0,
    val headingSource: HeadingSource = HeadingSource.NONE,
    val autoDisableThrottle: Boolean = false,
    val compassEnabled: Boolean = true,
    val throttleStatus: ThrottleStatus = ThrottleStatus.NOT_REQUESTED,
    val adbCommand: String = "",
    val bleStatus: BleStatus = BleStatus.OK,
    val hideUnnamed: Boolean = false,
    /** Samples already collected per network/device (all of them, not just the selected one). */
    val sampleCounts: Map<String, Int> = emptyMap(),
    val totalReadings: Int = 0,
)
