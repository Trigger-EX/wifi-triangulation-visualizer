# 📡 WiFi Compass

An Android app (Kotlin + Jetpack Compose) that scans nearby WiFi networks, lets you pick one, and points an animated
arrow toward where that access point probably is. It cross-references each scan's signal strength with your relative
position (dead-reckoned from the step detector + compass) and estimates the direction.

## Features
- Colourful network list with heat-coloured signal bars; the strongest network pulses.
- Big glowing compass arrow that springs toward the estimated AP, pulsing rings (faster when the signal is stronger) and a confidence ring.
- Hot/cold guidance banner ("🔥 Hotter!", "❄ Colder", "⬅ Turn left" …).
- Heads-up radar showing your trail (dots coloured by RSSI), the estimated AP star with an uncertainty circle, and north marker.

## How it works
1. **Position:** each detected step moves you `stepLength` metres along the compass heading (pedestrian dead reckoning, x = east, y = north).
2. **Signal:** RSSI of the selected BSSID is Kalman-smoothed and stored as a sample `(x, y, rssi)` on every fresh scan.
3. **Estimate:** a weighted plane fit of RSSI over position gives a gradient direction (works from ~6 samples); once the walk has
   width (an L or zig-zag), a log-distance path-loss model is fitted by grid search + Gauss–Newton to get an actual AP position.
4. **Guidance:** the world bearing is converted to a bearing relative to where the phone points.

Pure logic lives in `core/` (no Android imports) with JVM unit tests in `app/src/test`.

## No compass? Fallback heading
Position tracking only needs *relative* headings, so a broken or disturbed compass isn't fatal. The app walks down a fallback chain
(automatically when a sensor is missing, silent for 3 s, or reports UNRELIABLE; or forced via Settings → "Use compass"):
1. **Compass** (rotation vector / magnetometer)
2. **Gyro + accelerometer** (`TYPE_GAME_ROTATION_VECTOR`, no magnetometer; heading is relative to where you started)
3. **Gyro only** (`core/GyroHeading`: integrates rotation about the gravity axis)
4. **Straight-walk mode**: no heading sensors at all, so walk in a straight line and use hot/cold guidance.

The active source is shown on the tracking screen, and switching sources keeps the displayed heading continuous.

## Settings
- **Disable WiFi scan throttling**: Android only lets an app change Developer options → "Wi-Fi scan throttling" if it holds
  `WRITE_SECURE_SETTINGS`, which can't be requested at runtime. Grant it once:
  `adb shell pm grant com.wifitri.visualizer android.permission.WRITE_SECURE_SETTINGS`.
  The app then switches throttling off while open, scans every ~6 s, and restores your original value when you leave.
- **Use compass**: turn off to force the compass-free methods.

## Build
Open the folder in Android Studio (it provisions the Gradle wrapper), or run `gradle wrapper --gradle-version 8.9`
and then `./gradlew installDebug`. Requires JDK 17 and an Android SDK with API 34.

## Tips & limits
- Android throttles foreground scans to **4 per 2 minutes** (API 28+), so samples arrive slowly. For fast updates turn on Settings → "Disable WiFi scan throttling" (or switch it off manually in
  *Developer options → Wi-Fi scan throttling*). Walk slowly and make an L-shaped or zig-zag path for a good fix.
- Hold the phone flat, pointing the way you walk. Compass drift and indoor multipath make this an estimate, not a survey tool.
- Use "Reset trail" if the dead-reckoned position drifts.
