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

## 4. Undo

```
adb shell "pm uninstall com.ts.evhook; echo UNINSTALL_DONE"
adb shell "pm list packages | grep -c evhook; echo CHECK_DONE"   # must print 0 before CHECK_DONE
```
Remove the app with `pm uninstall com.evrange.app`. Neither touches the unit's own software.

## 5. Kill switch (no computer needed)

Create an empty file named `evfeed.disable` in `/storage/emulated/0/TsCarInfo/` with the unit's file manager. The feed pauses within 10 seconds and resumes when the file is deleted. If the feed ever stops itself (`evfeed.tripped` appears, with the reason inside), it stays off until you delete that file and restart the feed.
