# Install guide

For the exact setup in the README only. Use a computer on the same network as the head unit, with `adb` (Android SDK platform-tools). Replace `<unit-ip>` with the unit's Wi-Fi address and keep the car **parked** throughout.

Connect adb over the network (enable "USB debugging / ADB over network" on the unit first):

```
adb connect <unit-ip>:5555
```

The Wi-Fi link can drop. After any dropped command, **do not assume it worked**: re-check with an explicit marker (`; echo DONE`) as shown below. An empty reply is not a success.

## 1. Preflight (stop if any check fails)

```
adb shell "getprop ro.board.platform; getprop ro.build.version.sdk; getprop ro.product.manufacturer; echo P_DONE"
```
Expected: `825X_Pro`, `28`, `alps`.

```
adb shell "sha1sum /system/priv-app/MainUI/MainUI.apk; echo S_DONE"
```
Expected: `98d77350d621b09831bde02cdbf02409589cdf30`.

```
adb shell "od -An -tx1 /storage/emulated/0/TsCarInfo/Can_Com_Para.bin; echo C_DONE"
```
Expected: `64 01 03 00 00 00 00 00`.

```
adb shell "pm list packages | grep -c evhook; echo X_DONE"
```
Expected: `0` (not installed yet).

## 2. The app first (low risk)

```
adb push build\app\evrange.apk /data/local/tmp/evrange.apk
adb shell "sha1sum /data/local/tmp/evrange.apk; echo H_DONE"      # must equal the file on your computer
adb shell "pm install -r /data/local/tmp/evrange.apk; echo I_DONE" # expect Success
adb shell "pm grant com.evrange.app android.permission.READ_EXTERNAL_STORAGE"
adb shell "pm grant com.evrange.app android.permission.ACCESS_FINE_LOCATION"
```
Optional demo: copy `samples/ev_demo_sanitized.csv` to `/storage/emulated/0/TsCarInfo/ev_demo.csv`, then
`adb shell am start -n com.evrange.app/.MainActivity --ez demo true`. Switch demo off in Settings (or `--ez demo false`).

Open it from the unit's app list ("EV Range"). It shows "NO RECENT DATA" until the feed runs.

**Updating the app:** Android only accepts an update signed with the **same key** as the installed copy. Build with the same keystore (`app\build.ps1 -Keystore <file> -StorePass <password>`); a build signed with a different key is refused (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and nothing changes. **If the unit stops the app:** it was observed marked "stopped" (something on the unit force-stops user apps; cause not identified). A stopped app does not receive the boot broadcast; open it once and it catches up from `ev_log.csv`.

## 3. The data feed (the risky part)

Read [SAFETY.md](SAFETY.md) first. Then:

```
adb push build\hook\evhook.apk /data/local/tmp/evhook.apk
adb shell "sha1sum /data/local/tmp/evhook.apk; echo H_DONE"
adb shell "pm install -r /data/local/tmp/evhook.apk; echo I_DONE"
```
Installing does not restart anything and does not start the feed. Start it by hand once:

```
adb shell am startservice -n com.ts.evhook/.EvService
```

Check, about 10 seconds later:

```
adb shell "cat /storage/emulated/0/TsCarInfo/evfeed_status.txt; tail -n 3 /storage/emulated/0/TsCarInfo/ev_log.csv; echo F_DONE"
adb shell "ps -A -o PID,ETIME,NAME | grep com.ts.MainUI; dumpsys dropbox | grep -c SYSTEM_TOMBSTONE; echo M_DONE"
```
Expected: a `resolved CanJni` line, fresh data rows, and `MainUI` keeping its process id with the tombstone count unchanged. **Watch it for several minutes, then open the unit's own battery and motor pages and the radio**, before driving.

After that the feed starts by itself about 60 seconds after every `MainUI` start (boot or restart).

**Updating the feed:** installing a new feed version does **not** restart `MainUI`, so the old code keeps running until the process restarts. `am force-stop com.ts.MainUI` does nothing (it is a persistent system app). `adb shell am crash com.ts.MainUI` makes the system restart it: music, radio and the screen drop for about a minute, the feed starts again 60 s later, and a crash entry is logged. Do it parked, and no more than twice within 10 minutes (the crash-loop breaker). Check afterwards that `evfeed_status.txt` shows a new start and `evfeed.tripped` does not exist.

## 3b. Optional features (app 0.7 and later, feed 3.0 and later)

- **Floating range bubble.** The unit has no screen for the "Draw over other apps" permission; grant it over adb:
  ```
  adb shell "appops set com.evrange.app SYSTEM_ALERT_WINDOW allow; appops get com.evrange.app SYSTEM_ALERT_WINDOW; echo G_DONE"
  ```
  Then choose the mode in the app's Settings: off, only while driving (default) or always. Read [the notes on the reverse camera in SAFETY.md](SAFETY.md#the-floating-bubble-and-the-reverse-camera) first.
- **Speed-linked volume off.** Needs feed 3.0 or later, running. Open Settings: it shows "Now: ON (volume rises with speed)"; tick "Stop the speed-linked volume", save and confirm. Settings shows OFF within about 10 s. To undo, untick and confirm. The feed's state is in `evfeed_speedvol.txt`. See [SAFETY.md](SAFETY.md#the-one-setting-the-feed-can-write) for what the feed will and will not do.
- **Air-conditioning log (feed 3.1 and later).** `ac_log.csv` in the feed's folder records the air-conditioning state the unit receives, when it changes and once a minute (read-only). On the test car nothing in it ever changed, which means the car's air conditioning is not connected to the unit; if yours changes when you press the buttons, it is.
- **Log cleanup.** Every growing log keeps one old copy: when it passes its limit it becomes `name.1.ext` and a fresh file starts, so nothing takes more than about twice its limit (data log 10 MB, AC log 2 MB, feed status 256 KB, the app's calibration log 3 MB and car-info log 1 MB). Nothing needs doing by hand.

## 4. Undo

```
adb shell "pm uninstall com.ts.evhook; echo UNINSTALL_DONE"
adb shell "pm list packages | grep -c evhook; echo CHECK_DONE"   # must print 0 before CHECK_DONE
```
Remove the app with `pm uninstall com.evrange.app`. Neither touches the unit's own software.

## 5. Kill switch (no computer needed)

Create an empty file named `evfeed.disable` in `/storage/emulated/0/TsCarInfo/` with the unit's file manager. The feed pauses within 10 seconds and resumes when the file is deleted. If the feed ever stops itself (`evfeed.tripped` appears, with the reason inside), it stays off until you delete that file and restart the feed.
