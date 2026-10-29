# Plan summary
Kotlin/Compose single-module app. `core/` pure-JVM logic (ApLocator, Pdr, RssiFilter, StepDetectorLogic, Guidance),
`sensors/` (heading, steps), `wifi/` (scanner with 30 s ticker and throttle detection), `ui/` (ViewModel, list, tracker, radar).
Estimation: weighted RSSI plane-fit gradient + path-loss (n=2.5) grid/Gauss-Newton fit with a soft P0 prior; fit used once the walk is non-collinear.
