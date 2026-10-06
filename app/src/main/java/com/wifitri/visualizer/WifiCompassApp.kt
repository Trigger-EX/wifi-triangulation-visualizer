package com.wifitri.visualizer

import android.app.Application
import com.wifitri.visualizer.tracking.TrackingHub

class WifiCompassApp : Application() {
    val hub: TrackingHub by lazy { TrackingHub(this) }
}
