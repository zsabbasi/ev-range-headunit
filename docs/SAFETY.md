# Safety and risk

## What this software can and cannot do

- It **reads** car data through the head unit's own software and displays an estimate. It sends nothing to the car and nothing over the network. The app declares no internet permission. The air-conditioning state is only read and logged (and on the test car it turned out not to be connected at all).
- **One exception, off by default:** since feed 3.0 the feed can change a single setting of the head unit itself (not the car): the speed-linked volume, see [The one setting the feed can write](#the-one-setting-the-feed-can-write).
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

## The one setting the feed can write

The unit adds volume as the car speeds up, and on this car type its own switch for it is not shown on any screen. The feed (3.0 and later) can turn it off. It is the only thing the feed ever writes.

- **Nothing happens unless you ask.** The feed acts only when a request file (`speedvol_request.txt`) holds exactly `1` (stop the speed-linked volume) or `0` (restore it). The app's Settings dialog writes that file, after a confirmation. With no file, the feed only reads the setting and reports it in `evfeed_speedvol.txt`.
- **Limits:** it checks every 10 s, changes the setting only if it differs from the request, at most 6 times an hour, reads the value back after each write, and stops writing for good if the read-back differs. Any failure here switches off only this feature; it never trips the feed. The kill switch and the CAN-type check come first.
- **Anyone on the unit can ask.** Any app that can write the request file can switch this one setting; it cannot do anything else through the feed. Remove the request file to stop asking, and use `0` to undo.
- **Undo:** untick the box in Settings and confirm, or write `0` into the request file.

## The floating bubble and the reverse camera

The app can draw a small range bubble over other screens (it needs the "Draw over other apps" permission, granted over adb). The unit's reverse camera comes up as soon as R is selected, before the car moves, so the bubble must not cover it:

- **Default, "only while driving":** the bubble shows only while the motor turns forward (30 rpm or more), so it is hidden whenever the car stands still or reverses.
- **"Always":** hidden while reversing (negative rpm) and for 8 s afterwards, but it **cannot know that R was just selected** while the car is still standing, and can cover part of the camera view until you move. Not recommended.
- Hiding while actually reversing has been designed and unit-tested but **not yet verified in the car**. Test it parked, with the car safe, before relying on it. A long press hides the bubble until the app restarts; turning it off in Settings removes it.

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

## The unit itself accepts commands from any app

The head unit's car-info service (`com.ts.can.carinfo.CarInfoService`, inside `com.ts.MainUI`) is declared **exported with no permission**. Besides read methods it offers `SendCmd`, `UartCanSend` and the serial-port calls, so **any app installed on the unit can send commands to the car's CAN bus**. This is a weakness of the unit, not of this project. What follows from it:

- Do not install apps from unknown sources on this unit.
- The probe in this repository is read-only: it requests no permissions and calls only `requestCarBaseInfo()` (transaction 6). It never calls the command or serial methods, and it neither shows nor logs the vehicle identification number.
- The app and the data feed never send anything to the car.
