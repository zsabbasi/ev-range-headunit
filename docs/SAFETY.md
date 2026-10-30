# Safety and risk

## What this software can and cannot do

- It **reads** car data through the head unit's own software and displays an estimate. It sends nothing to the car and nothing over the network. The app declares no internet permission.
- It is **not** a fuel gauge or a safety function. The estimate comes from the last few kilometres, whole-percent SOC and a distance ratio that must be calibrated. Keep your own reserve (20 to 30 %) and check pressures with a gauge, not this unit.
- With the vehicle off the unit suspends and nothing runs. It has no SIM; it can only use Wi-Fi or Bluetooth.

## The one genuinely risky part: the data feed

The feed runs **inside `com.ts.MainUI`**, the program that runs the whole head unit (including its reverse-camera screen). A mistake there can crash that program.

What happened during development (full detail in the development log, sections 10 and 12):
- The first logger polled the car library from its own thread. The library keeps thread-specific references internally, so this made `MainUI` abort with SIGABRT about every 4 to 10 minutes. Each crash restarted `MainUI` and reset Wi-Fi.
- The cause was found in a native crash record (a stale JNI reference passed to `GetFieldID` inside the library) and by the fact that, with the logger removed, the program ran for hours without a crash.
- The feed was redesigned. All calls into the car library now happen **only on the main thread**, the same thread the unit's own pages use. File output uses a separate thread that never touches the library. No broadcasts are sent.

## Safety brakes in the feed (tested on a laptop with a fake library, `scripts/test.ps1`)

| Brake | Trigger | Effect |
|---|---|---|
| Kill switch | file `evfeed.disable` exists | no library calls; resumes when removed |
| Crash-loop breaker | 3 process starts within 10 minutes | writes `evfeed.tripped`; feed stays off |
| Slow-poll breaker | 5 polls in a row over 150 ms | writes `evfeed.tripped` |
| Error breaker | 5 consecutive failures, or any linkage error | writes `evfeed.tripped` |
| Profile check | CAN type is not 356 | idles, makes no queries |

A tripped feed stays off until `evfeed.tripped` is deleted.

## What is still not proven

- Behaviour over many hours and many restarts beyond the tests in the development log.
- Behaviour while other apps use the car library at the same instant (it worked while the unit's own screens were used, but this is not exhaustive).
- Any other head unit, firmware or car. **Do not install on anything else.**

## Rules of thumb

1. Install only after the preflight checks in [INSTALL.md](INSTALL.md) pass.
2. Install and test **parked**. After installing, watch the process id and the crash-record count before driving.
3. Verify every step with an explicit end marker. A dropped connection can return an empty reply that looks like "nothing to report".
4. If anything reloads, freezes or misbehaves: create `evfeed.disable`, or uninstall `com.ts.evhook`.
5. The data feed package is signed with the **public** Android test platform key and uses the system user ID. That is what lets it run inside the unit's program; it is also why it must never be offered as a general app or to other devices.
6. Never publish raw logs, crash dumps or screenshots from your own unit without removing network names, addresses and location data.
