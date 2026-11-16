# Data-collection protocol for tuning the WiFi compass

Goal: separate the two error sources, (A) walking-position error (steps + heading) and (B) signal-to-direction error (RSSI model),
so each can be fixed on its own. Use **Start recording** (list or tracking screen), then **⚑ Mark spot** / **📍 I'm at the AP**, then **■ Stop & share**.
Note what every marker means (M1, M2, AP1 …) in the chat message with the file.

Setup for every run: gyro or compass heading as normal, height set, "Disable WiFi scan throttling" ON if you can, track ONE of your own
routers/APs whose position you know, phone held flat pointing the way you walk, a tape measure or floor tiles for distances.

## Run 1: walking accuracy only (no WiFi needed)
Pick a start point (Mark spot = M1). Walk a rectangle 6 m x 4 m (use floor tiles/tape) at normal pace, back to exactly the start, Mark spot (M2).
Repeat once walking slowly and once walking naturally while holding the phone as you normally would. Tell me the true side lengths.
→ shows step-length error, heading drift, and whether steps are over/under-counted.

## Run 2: pure signal field, standing still (no walking error)
Choose 8-10 points on a 1.5-2 m grid in one room, tape-measured from the AP (tell me the coordinates in metres, AP = origin).
Stand at each point for ~30 s without moving (phone flat), tap Mark spot at the start of each. Include the point right next to the AP (📍 I'm at the AP).
→ gives RSSI vs true distance for the real environment: noise level, path-loss exponent, how much walls/people matter.

## Run 3: the real task, with ground truth
Same room/AP. Start 5-8 m from the AP, not in line of sight if possible (the hard case). Follow the app's guidance until it "locks on" or for 5 minutes,
then walk to the AP and tap 📍 I'm at the AP when you touch it. Do 3 attempts from different start positions, and one with the app's
suggestions deliberately ignored (just wander an L shape).
→ lets me replay the estimator offline and compare its bearing at every moment with the true bearing to the AP.

## Extras that help
- Tell me: phone model, which heading source Settings shows, whether you hold it in hand/pocket, anyone moving around, band (2.4/5 GHz) of the AP.
- If the estimate gets worse with more data, keep recording past the point it goes wrong, and tell me the time it started to look wrong.
