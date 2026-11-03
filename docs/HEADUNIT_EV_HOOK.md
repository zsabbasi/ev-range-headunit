> **About this file.** It is the development log, kept as it was written. File paths in it refer to the
> original working folder (shown here as `<work>\`), which holds the manufacturer's files (`MainUI.apk`,
> its native libraries and the decompiled code) and raw logs, so it is **not** part of this repository.
> Where a path points into this repository, it has been rewritten (`app\`, `hook\`, `tools\`).
> Network names, addresses and the author's computer paths have been replaced by placeholders.

# Lingbox head unit: EV data logging and range project

Status as of 2026-10-30. Nothing has been installed on the head unit yet.

## 1. Goal

The car's range figure is wrong. It is based on NEDC and ignores recent driving. The aim is to read real battery and motor data from the head unit's CAN interface and compute range from actual consumption (or WLTP), then show it safely in the car.

## 2. Hardware and software facts

| Item | Value | How known |
|---|---|---|
| Car / head unit | Lingbox, "Octapro 8529" | user |
| Device model | `825X_Pro`, manufacturer/brand `alps`, platform `825X_Pro` (MediaTek/Autochips-style) | `getprop` |
| Android | SDK 28 (Android 9). The `ro.build.version.release` property says 10. | `getprop` |
| Build | `full_825X_Pro-Octa core 9 PPR1.180610.011 eng.root.20200426`, `test-keys` | `getprop` |
| USB port | `dual-role-usb20`, USB 2.0, dual-role (host mode in use) | logcat |
| Network | `<unit-ip>`, adb over TCP `:5555` | user, adb |
| Battery | 19 kWh (user stated; whether usable or gross is unknown) | user |
| CAN profile | type 356 (`CAN_JIMAI_FL`), subtype 3 | `Can_Com_Para.bin` |
| Screens in use | `com.ts.can.CanCarInfoSub1Activity` (BMS and motor pages), `CanJimaiFlEc01OverseasCarInfoActivity` | logcat |

Installed projection apps are CarPlay only (`com.autochips.carplay`, `com.ts.carplayapp`). There is no Google Android Auto client.

## 3. Where the car data comes from

```
car CAN bus -> MCU chip -> UART -> libts59xhw.so -> libcan59.so -> CanJni (Java) -> MainUI screens
```

- The CAN bus is read by a separate MCU. Android receives frames over a serial link.
- `libts59xhw.so` is the hardware layer (`c_McuModuleInit`, `c_uartopen`, `ioctl`, I2C) and also stores settings (`GetCanTp`/`SetCanTp`, `GetCanSubT`, `GetCanTpms`, `GetSpeedVol`/`SetSpeedVol`).
- `libcan59.so` (32-bit ARM, 2.5 MB) decodes per-car protocols (C++ classes such as `CCan`, `CCanT3Fl`, `CCan_Naveco_Ts`) and exposes JNI getters.
- Everything lives inside the `com.ts.MainUI` process (`/system/priv-app/MainUI/MainUI.apk`, 87,983,687 bytes).
- No other app can read the data. It is not in logcat or Android settings, and the mirroring AIDL services carry phone and media state only.
- A second reader on the serial link would split its bytes with `MainUI`. That could corrupt the unit's own displays and car controls, so this was ruled out.

### Decoding (from `com.ts.can.jimai.fl.*InfoView`)

| Value | Source field | Displayed as |
|---|---|---|
| Battery voltage | `JiMaiFl_BmsInfo.Dcdy` | `Dcdy` V |
| Battery current | `Dcdl` | `Dcdl - 500` A |
| SOC | `Soc` | `Soc` % |
| Cell temp max / min | `CellTempMax` / `CellTempMin` | raw - 40 °C |
| Motor temperature | `JiMaiFl_MotorInfo.Djwd` | raw - 40 °C |
| Motor controller temperature | `Djkzqwd` | raw - 40 °C |
| Motor speed | `Djzs` | `Djzs - 15000` rpm |

- The app requests data with `CanJni.CanCmdQueryData(52,0,0)` (BMS) and `(54,0,0)` (motor), then reads with `JiMaiFlGetBmsInfo` / `JiMaiFlGetMotorInfo`.
- The structures have fields no screen shows: `Nlhs`, `Bsmdfk` (BMS) and `Djmxdl`, `Djnjyxw`, `Sjnj` (motor). Their meaning is unknown.
- Readings seen on screen while parked: 328 V, 0 A, 52 %, cells 33/32 °C, motor 33 °C, controller 34 °C, 0 rpm.

### Other findings

- `Can_Com_Para.bin` (`/storage/emulated/0/TsCarInfo/`) is 8 bytes: `64 01 03 00 00 00 00 00`. It holds the CAN type (`0x0164` = 356), subtype 3 and two option bytes, written by `CanFunc.CanInit`. It is not a decoding table.
- `TPMS_V5.bin` (`TsStorage`) is saved TPMS settings (`StTpms.java`).
- There is no NEDC or range code in `MainUI`. The unit's range figure (launcher widget ids `tv_nedc_value`, `tv_battery_value`) is computed elsewhere and was not examined. Changing it would mean modifying the system image, which is not recommended.
- A built-in raw CAN logger exists: `com.ts.can.CanDataLogService`. It shows frames in a floating window (`RX:`/`TX:` plus hex) and writes `/storage/emulated/0/TsCarInfo/CanData-<yyyy-MM-dd-HH-mm-ss>.txt` when Save is pressed. It keeps frames in memory and writes only on Save. It has not been opened or tested.
- Speed in km/h: the unit receives it for speed-dependent volume, but I did not find the getter for the Jimai profile. `CanJni.GetSpeed(CAN_Speed)` is generic, and the logger records it, but it may be 0.
- A correction I made during the work: I first concluded the head unit had no vehicle data. That was wrong. It was based only on package names. The data is inside `MainUI`.

## 4. The hook (built, not installed)

**Approach:** patch `MainUI` so a background thread inside the same process polls the Jimai data. Because it runs in the same process and uses the same native library, there is no second reader.

**Why it can be installed without root:** the APK is signed with the public AOSP platform test key (certificate SHA-1 `27196e386b875e76adf700e7ea84e4c6eee33dfa`, SHA-256 `c8a2e9bc…2ab8`). I verified this against the key published in the Android source. A re-signed copy keeps the same signer.

**Changes in the patched APK:**
1. `classes.dex` was disassembled with baksmali 3.0.10. One call was inserted at the end of `CanFunc.CanInit(Context)`, wrapped in a catch-all:
   ```
   sget-object v0, Lcom/ts/can/CanFunc;->mContext:Landroid/content/Context;
   invoke-static {v0}, Lcom/ts/evlog/EvLogger;->start(Landroid/content/Context;)V
   ```
   It was then reassembled with smali. Comparing disassembly of original and rebuilt: 26 classes differ only by dropped redundant default static values (`= false`, `= 0x0`, `= null`), plus `CanFunc` with the hook.
2. `classes4.dex` was added. It contains `com.ts.evlog.EvLogger`, compiled with `javac --release 8` against `android-28` and converted with `d8`.
3. Old signature files were removed. The APK was zip-aligned (`zipalign -p 4`) and signed with the AOSP platform key (v1, v2 and v3 verify).
4. Only `classes.dex` and the signature files changed. `classes4.dex` is new.

**`EvLogger` behaviour:**
- It starts a low-priority daemon thread and waits 15 s after init.
- It only runs when `CanJni.GetCanType() == 356`. Otherwise it rechecks every 30 s.
- Every 2 s it sends the two query commands, waits 400 ms, reads BMS, motor and speed, and writes one line.
- Output is `/storage/emulated/0/TsCarInfo/ev_log.csv`, which rotates to `ev_log.1.csv` at 10 MB. `ev_latest.csv` holds the newest line.
- It broadcasts `com.ts.evlog.UPDATE` with extras `epoch_ms`, `volt`, `amp`, `soc`, `rpm`.
- It uses reflection only, so it has no compile-time link to `MainUI`, and every exception is swallowed.
- CSV columns: `epoch_ms,time,volt,amp,soc,cell_max,cell_min,motor_temp,ctrl_temp,rpm,speed_raw,speed_dw,bms_nlhs,bms_bsmdfk,motor_djmxdl,motor_djnjyxw,motor_sjnj,raw_dcdl,raw_djzs`

**Result of the laptop test:** against fake `CanJni`/`CanDataInfo` classes returning the parked readings, the CSV contained the expected decoded values. Two queries were sent per cycle, and a second `start()` call was ignored. The test used a copy of the source with only the output-path constant changed.

**Not tested:**
- The patched APK on the real unit.
- Whether the unit accepts it as an update over the system app.
- The sign and scale of the current under load (only 0 A was seen).
- Whether the speed field gives km/h.
- The effect of continuous query commands on the car.

**Install attempt (2026-10-30): FAILED, nothing changed.**
- The patched APK was pushed to `/data/local/tmp/MainUI_patched.apk` in 43 verified chunks (SHA-1 matched). `pm install -r` then failed with `INSTALL_FAILED_INVALID_APK: Package com.ts.MainUI is a persistent app. Persistent apps are not updateable.`
- So the earlier plan (install as an update, undo with `adb uninstall`) does not work. That statement was wrong.
- State after the failure: the system `MainUI.apk` is untouched (SHA-1 `98d77350d621b09831bde02cdbf02409589cdf30`, same as `Lingbox\MainUI.apk`).
- Probe results: `/system/xbin/su` exists, `ro.debuggable=1`, `ro.secure=1`, adb runs as `shell` (not root), dm-verity disabled, `/` (system) mounted read-only (ext4), no `oat` folder for MainUI.
- The remaining way to use the hook is to replace `/system/priv-app/MainUI/MainUI.apk` as root (remount read-write, copy, restore permissions, reboot). That edits the system partition and is not undoable with `adb uninstall`. Rollback would be copying the original file back. This has NOT been done and needs a separate decision.
- Leftovers on the unit from the attempt: `/data/local/tmp/MainUI_patched.apk` and `/data/local/tmp/mui/` (about 88 MB each).

## 5. Range script

`ev_range.py` computes real consumption and range from manual readings (time, odometer, dashboard SOC, optional dashboard range).

- Merges readings into segments of at least `--min-soc-drop` % SOC (default 5) and skips charging.
- Reports per-segment Wh/km and range at 100 %, then totals.
- `--window-km` (default 50) gives a recent-driving estimate.
- Compares the dashboard range with the measured one.
- `--gpx` cross-checks odometer distance.
- Tested on made-up data (`readings_sample.csv`) only.
- Planned improvement (not built): use voltage × current from `ev_log.csv` for real kWh instead of SOC steps.

Run with `py -I <work>\ev_range.py readings.csv` (`python` is not on the PATH, so use `py`).

## 6. Not done

- Installing the patched APK.
- Verifying the current sign and scale, and finding a speed source.
- Building the range app/display that reads `ev_latest.csv` and shows WLTP or driving-average range.
- Opening the unit's own CAN logger.
- Checking whether adb has root.
- A hash check of the Bluetooth-transferred APK against the unit's copy. Only the file size matched. The APK decompiled normally and its signature verified.

## 7. Files (all under `<work>` unless noted)

| Path | What |
|---|---|
| `Lingbox\MainUI.apk` | Original APK (87,983,687 bytes), received over Bluetooth |
| `hook_work\MainUI_patched.apk` | Patched, signed APK. SHA-256 `2493804C21BD5D6E933D380D9A30EC507FEB6A7A3C2675D29876368EEBEB77DB` |
| `hook_src\com\ts\evlog\EvLogger.java` | Logger source |
| `hook_src\build_apk.py` | Builds the unsigned APK from the original, patched dex and logger dex |
| `hook_work\` | `orig\classes.dex`, `smali_out\` (edited smali), `patched\classes.dex`, `dexout\classes.dex`, `verify_out\`, `test\` (laptop test) |
| `decompiled\` | jadx output of `MainUI.apk` (4,872 files); log in `decompiled_jadx.log` |
| `native_work\` | `libcan59.so`, `libts59xhw.so`, `libts70xicfg.so`, `libpymatch.so` |
| `tools\` | `jadx\` (1.5.6), `apktool\apktool.jar` (3.0.3, not used for the final build), `smali\` (smali/baksmali 3.0.10 and dependencies), `aosp_keys\` (public AOSP platform key), `strings_grep.py` |
| `ev_range.py`, `readings_sample.csv` | Range script and sample input |
| `pull_chunks.ps1` | Resumable chunked adb pull (written when Wi-Fi adb kept dropping; not used successfully) |
| `headunit_logcat_snapshot.txt`, `headunit_live_capture.txt` | Logcat captures |
| `screen.png`, `screen2.png` | Screenshots of the battery and motor pages |
| `top_dump.txt` | `dumpsys activity top` view hierarchy (contains no text values) |
| `apk_work\parts\000.part` | Leftover partial chunk from a failed pull |

**Left on the head unit:** `/storage/emulated/0/MainUI.apk` (copy of the system APK I made) and screenshots `/sdcard/s.png`, `/sdcard/s2.png`. They can be deleted.

## 8. Practical notes

- **adb:** `<android-sdk>\platform-tools\adb.exe`, not on the PATH. Connect with `adb connect <unit-ip>:5555`.
- **Why a USB cable did not work:** the laptop only has USB-C/USB4 ports, and the phone-style USB debugging path failed. The head unit was reached over Wi-Fi instead.
- **Unstable link:** the Wi-Fi adb session often drops to `offline`, especially with the laptop in the car and during large transfers. What helped: `adb disconnect`, then `connect`, then run the command immediately; kill and restart the adb server; and toggle ADB over network on the unit.
- **Windows shell:** `Start-Process -Wait` on `adb start-server` hangs forever. Call adb through `cmd /c` instead. `Set-Content -Encoding utf8` adds a BOM in PowerShell 5.1, which breaks `javac`.
- **Protected paths:** the sandbox blocks `Remove-Item` on `<drive>:\storage` and on wildcard paths. Don't try to work around it.
- **Large transfers:** an 88 MB adb pull never completed. The APK came over Bluetooth after it was copied to `/storage/emulated/0/` on the unit.
- **Safety:** an estimate from decoded CAN values is not a fuel gauge. SOC is non-linear near full and empty. Keep a reserve (e.g. 20 to 30 %) until the estimate has been compared against several real drives.

## 9. Update 2026-10-30: working in-process logger (no system changes)

**Root is not available.** `adb root` answers `cxj said not suport, 88` (the vendor disabled it), and `su` from the shell says `su: not allowed`. Replacing `/system/priv-app/MainUI/MainUI.apk` was therefore not possible, and the patched `MainUI_patched.apk` is unused. Nothing on `/system` was changed. The system APK still has SHA-1 `98d77350d621b09831bde02cdbf02409589cdf30`.

**What works instead: package `com.ts.evhook`** (`hook_work\evhook\evhook.apk`, 12,691 bytes, SHA-256 `91F3E356EE493BD7C7C10799A3FDEB61408F9E38343964EA1B5E4C8CAB340890`).
- Manifest: `android:sharedUserId="android.uid.system"`, `android:process="com.ts.MainUI"`, one exported service `.EvService`. Signed with the public AOSP platform key (same signer as `MainUI`).
- It installed normally (`pm install -r`, "Success"). It shows as userId 1000 / `android.uid.system`.
- Source: `hook_src\evhook\` (`AndroidManifest.xml`, `com\ts\evhook\EvService.java`, `EvLogger.java`, `add_dex.py`). Build chain: `javac --release 8`, `d8`, `aapt2 link`, `add_dex.py`, `zipalign`, `apksigner` with the platform key.
- Started with `adb shell am startservice -n com.ts.evhook/.EvService`. The service starts `EvLogger` and stops itself. The logger thread keeps running as long as the `MainUI` process lives.
- `EvLogger` gets `MainUI`'s own class loader with `createPackageContext("com.ts.MainUI", INCLUDE_CODE|IGNORE_SECURITY)`, resolves `CanJni` by reflection and polls it every 2 s. Same CSV format as section 4.
- `evhook_status.txt` in `TsCarInfo` records what happened.

**Verified on the unit (07:11 to 07:12):**
- The service ran in `MainUI`'s own process: pid 1277, uid 1000, with the class loader `PathClassLoader[... /system/priv-app/MainUI/MainUI.apk ...]`.
- The first data line was written about 3 s after start. `MainUI` kept the same pid and stayed up.
- Readings (parked, overnight): 328 V, 0 A, SOC 52 %, cells 30/29 °C, motor 27 °C, controller 28 °C, 0 rpm (occasionally -1 from noise). Unexplained raw fields: `Nlhs`=1, `Bsmdfk`=0, `Djmxdl`=1, `Djnjyxw`=108/109, `Sjnj`=17734.

**Not done / unknown:**
- **Auto-start (added, verified 2026-10-30):** `evhook` v1.1 (versionCode 2, `hook_work\evhook2\evhook.apk`, SHA-1 `fdcc01ae1de57f6afc9d90aa5b339847ceb77aae`) adds a content provider `.EvStarter` (authority `com.ts.evhook.starter`, `android:process="com.ts.MainUI"`). Android creates it whenever the `MainUI` process starts, and it starts the logger after a 45 s delay. Installing the update did NOT restart `MainUI` (same pid), so the provider only took effect at the next process start. After the user restarted the unit, `evhook_status.txt` showed `started in process pid=22657 uid=1000` (a new pid) at 20:37:15 and the first data line at 20:38:00, with no manual `am startservice`. Logging then continued normally (V=332, 0 A, SOC 74 %, rpm about 0). The logger dies whenever `MainUI` restarts and starts again by itself. Each restart leaves a gap of about 1 to 2 minutes in the CSV.
- Current sign and scale under load, and a speed source, are still unverified (needs a drive). `speed_raw` was 0 while parked.
- The display app and range calculation (steps 3 and 4 of the plan) are not built.
- Undo: `adb shell pm uninstall com.ts.evhook`.
- Leftovers on the unit: `/data/local/tmp/MainUI_patched.apk`, `/data/local/tmp/mui/`, `/data/local/tmp/evhook.apk`, `/storage/emulated/0/MainUI.apk`, `/sdcard/s.png`, `/sdcard/s2.png`.

## 10. Update 2026-10-30 evening: the logger was crashing MainUI and has been uninstalled

**Drive results (2 km outing, log in `drive_logs\ev_log_2026-10-30.csv`):**
- Current sign and scale confirmed: positive = discharge, negative = regen, whole amps (peaks +76 A / -25 A).
- Integrated V x I over 27 min of logged time: 519 Wh used, 102 Wh regen, 417 Wh net, against SOC 74 to 71 % (570 Wh +/- 190 Wh at 1 % resolution). The two agree.
- `speed_raw` and `speed_dw` were always 0, so there is no km/h speed from this CAN profile. Distance needs GPS (no fix seen while parked) or rpm calibrated against a known distance. 15,312 motor revolutions were logged; the distance depends on the unknown gear ratio.
- Analysis scripts: `tools\analyze_drive.py`, `tools\drive_energy.py`, `tools\restart_analysis.py`.

**The earlier interpretation of the restarts was wrong.** I first read the repeated ~43 s gaps as whole-system restarts. Process ages on the unit showed `system_server`, `zygote64` and `surfaceflinger` up for 10 h 24 min, so the Android system did not restart. Only `com.ts.MainUI` restarted (and `wpa_supplicant` was restarted with it, which is why Wi-Fi dropped and did not reconnect).

**Cause found in the crash records (`dumpsys dropbox`):**
- `SYSTEM_TOMBSTONE`: 10 native crashes of `com.ts.MainUI`, all `signal 6 (SIGABRT)`, between 21:05 and 21:47. In four of them the crashing thread is named **`EvLogger`** (my logger thread): 21:08:43, 21:15:02, 21:34:28, 21:47:35. The other MainUI aborts (21:05, 21:19, 21:22, 21:25, 21:30, 21:38) were on the main thread. Whether those would also happen without the logger is not known.
- `system_server_wtf`: 889 of the 1000 dropbox slots were `Sending non-protected broadcast com.ts.evlog.UPDATE from system ... com.ts.MainUI`. This is my broadcast, sent every 2.5 s. It almost certainly pushed older crash records out of the dropbox.
- `system_app_anr` at 21:23:21 is for `com.android.launcher`, blocked on a binder call into MainUI (`ITsCommon.nGetWorkMode`). That is a symptom of MainUI being dead or restarting, not a cause. No mention of `EvLogger` or `CanJni` in it.

**Action taken:** `com.ts.evhook` was uninstalled at 22:10 (`pm uninstall` printed `Success`; `pm list packages | grep -c evhook` printed `0`, both read with explicit end markers). My first uninstall attempt (about 21:55) was reported as done but had NOT worked: a dropped adb connection returned an empty reply that I misread as "package not found". The logger therefore kept running and crashing MainUI until 22:10. Lesson: confirm removal with an explicit end marker, never from empty output. The leftover APKs in `/data/local/tmp` (`evhook.apk`, `evhook2.apk`, `MainUI_patched.apk`, `mui/`) were deleted at the same time. Nothing in `/system` was ever changed.

**Root cause (backtrace of the 21:47:35 `EvLogger` crash, pid 11600, tid 11618):**
```
abort -> art::Runtime::Abort -> IndirectReferenceTable::AbortIfNoCheckJNI
 -> IndirectReferenceTable::GetChecked -> Thread::DecodeJObject
 -> art::JNI::GetFieldID -> libcan59.so CJNIHandler::SetIntField
 -> libcan59.so JiMaiFlGetMotorInfo  <- called from my EvLogger thread
```
ART aborts because `libcan59.so` passes an invalid (stale or wrong-thread) JNI reference to `GetFieldID`. `CJNIHandler` evidently keeps a cached class/object reference that is only valid for the thread that set it. The library is therefore NOT safe to call from a second thread. This most likely also explains the six aborts on MainUI's main thread: my thread and the UI thread share that cached state, so one thread leaves a reference the other cannot use. (The main-thread cases are an inference from this backtrace, not proven.)

**Consequence for any future logger:** it must call the `CanJni` getters and `CanCmdQueryData` only on MainUI's main thread (for example a `Handler(Looper.getMainLooper())` posting a short task every 2 s), the same thread the UI pages use. It must not send a custom broadcast from the system uid (that produced the `system_server_wtf` flood); write to files only.

**Stability check (22:11 to 22:35):** with the logger gone, `MainUI` (pid 22786) ran 25+ minutes without a restart and no new tombstones appeared after 22:10:50. Wi-Fi also stayed up (`wpa_supplicant` not restarted) after the user removed the second access point.

**Wi-Fi findings:** the unit's Wi-Fi (`<home-ssid>`, 2.4 GHz) was weak in the car (-77 to -88 dBm) and was roaming back and forth between two access points with the same name every ~14 s (BSSIDs `<mac-1>` and `<mac-2>`). Each switch cut the adb link. Auto-join attempts do happen (`CMD_START_CONNECT`); handshakes timed out at that signal level. `wifi_sleep_policy` is 2 (never). The user disconnected one of the access points, and the link became stable. The earlier theory that MainUI restarts reset Wi-Fi was mostly the user toggling Wi-Fi by hand.

**Home screen:** the launcher is an app-widget host (hostId 1024) but the user reports the main screen is not editable, so a home-screen widget is unlikely. The app is opened from the app list instead.

**Open:** (1) confirm that MainUI no longer crashes with the logger gone; (2) Wi-Fi auto-connect after a MainUI restart (probably resolves itself if the crashes stop); (3) a main-thread-only logger, tested briefly while parked with a close eye on tombstones; (4) the display app. Do not reinstall the old thread-based logger. Raw evidence: `/data/local/tmp/tomb.txt` and `/data/local/tmp/wifi.txt` on the unit; `drive_logs\anr_dropbox_2026-10-30.txt` on the laptop.

## 11. EV Range app (2026-10-30, installed, demo data only)

A normal app (`com.evrange.app`, signed with a debug key, own process, no system privileges, does not touch the CAN library). Source `app\`, build with `app\build.ps1`, output `app_build\evrange.apk`. Installed on the unit with `pm install -r`; permissions `READ_EXTERNAL_STORAGE` and `ACCESS_FINE_LOCATION` granted with `pm grant`. Open it from the unit's app list ("EV Range").

- **Screen (1024x600):** range in km (green >= 40, amber 20 to 40, red < 20), consumption in kWh/100 km and km/kWh, battery % and kWh, trip / today / all-time consumption, WLTP-based range (if set), a live power bar (regen on the negative side), a 7-day consumption chart, window buttons (10 / 25 / 50 km), Settings and New trip.
- **Range logic (`RangeEngine.java`):** energy from voltage x current; distance from GPS speed when a fix exists, otherwise from motor rpm x `kmPerMotorRev` (uncalibrated default 0.000211, from an assumed gear ratio 9 and 1.9 m wheel circumference). Range = (capacity x SOC) / consumption over the last N km, falling back to trip, then all-time, then 14 kWh/100 km. State (rolling buckets, per-day totals) is saved to the app's private storage every 30 s and on pause.
- **Data source:** reads `/storage/emulated/0/TsCarInfo/ev_log.csv` incrementally (same CSV format as the old logger). There is currently NO live feed, because the logger was removed (section 10). Demo mode (`am start -n com.evrange.app/.MainActivity --ez demo true`, or the checkbox in Settings) replays `ev_demo.csv`, a copy of the 2026-10-30 drive log, and never saves state.
- **Verified:** on the laptop (`app_src\evrange_test\EngineTest.java`: same numbers as the replay, save/load round trip identical) and on the unit in demo mode (screenshots `drive_logs\evrange_demo_screen*.png`: 104 km at 71 %, 12.9 kWh/100 km, trip 3.2 km). A first version counted the demo drive twice after a relaunch (saved demo state plus replay); fixed by never loading or saving state in demo mode.
- **Known limits:** the distance is still an estimate until calibrated (enter the real km of a trip in Settings and it computes `kmPerMotorRev`) or until GPS gets a fix; the 2 km drive could not be checked against a real distance; the Settings dialog needs a keyboard on the unit and has not been tested there; SOC is whole percent, so range steps in about 0.19 kWh increments.
- On the unit, the old logs were renamed to `ev_log_2026-10-30_old.csv` and `ev_latest_old.csv` so live mode starts clean.

## 12. Redesigned feed v2.0 (2026-10-30 23:00, installed, under observation)

**Why it is different from the logger that crashed MainUI (section 10):** all calls into the car library (`CanCmdQueryData`, `JiMaiFlGetBmsInfo`, `JiMaiFlGetMotorInfo`) happen only on MainUI's main thread, via a `Handler(Looper.getMainLooper())`, the same thread the unit's own CAN pages use. Each poll reads what the previous poll requested and then requests fresh data, so the main thread never sleeps. File output is done by a separate writer thread that never touches the library. No broadcast is sent. `GetSpeed` is not called.

**Packaging:** `com.ts.evhook` versionCode 3 / 2.0, shared uid `android.uid.system`, process `com.ts.MainUI` (same method as before), SHA-1 `07a1c53c03a71a76cefdfc965e1ff23e1ce1bbfc`, 16,787 bytes. Source `hook\` (`EvFeedCore.java` = logic, no Android dependencies; `EvFeed.java` = glue, handler and writer thread; `EvStarter.java` = content provider that starts the feed 60 s after the MainUI process starts; `EvService.java` = manual start `am startservice -n com.ts.evhook/.EvService`). Built with the same chain as before (javac, d8, aapt2, zipalign, apksigner with the AOSP platform key). The APK contains no `EvLogger` and no `com.ts.evlog.UPDATE` string.

**Safety brakes (all tested on the laptop with a fake library, 17 checks pass: `hook_work\feedtest`):**
- Kill switch: while `/storage/emulated/0/TsCarInfo/evfeed.disable` exists the feed makes no library calls (checks every 10 s); delete the file and it resumes.
- Crash-loop breaker: 3 process starts within 10 minutes writes `evfeed.tripped` and the feed stays off (start times are kept in `evfeed_starts.txt`).
- Slow-poll breaker: 5 polls in a row over 150 ms trips it.
- Error breakers: 5 consecutive failures, or any `LinkageError`, trips it immediately.
- A tripped feed stays off until `evfeed.tripped` is deleted (and MainUI restarts or `am startservice` is run again).
- It only runs when `CanJni.GetCanType()` is 356.

**Logging behaviour:** same CSV format as before (`speed_raw` and `speed_dw` are always 0). While driving (|current| >= 1 A or |rpm| >= 30) it writes every 2 s; while idle it writes at most one line per 30 s, plus one whenever SOC changes. Status messages go to `evfeed_status.txt`. `ev_log.csv` rotates at 10 MB.

**Observed on the unit:**
- Baseline before install: MainUI (pid 22786) had run 49 minutes with no restart, and no tombstone had appeared since 22:10:50, where before it crashed every 4 to 10 minutes with the old logger running. This strongly supports the old logger being the cause.
- Installing did not restart MainUI. The feed was started by hand at 23:02:28 and logged `start requested, pid=22786 uid=1000` and `resolved CanJni via PathClassLoader[... /system/priv-app/MainUI/MainUI.apk ...]`; the first reading arrived about 5 s later.
- Over the next 5 minutes MainUI kept pid 22786, the tombstone count stayed at 10, and about 2 lines per minute were written while idle (331 to 332 V, 0 A, SOC 70 %).
- The EV Range app was switched to live mode and showed LIVE, 70 % / 13.3 kWh, 95 km range based on the 14 kWh/100 km default (no distance yet), "NEDC-based" row 168 km (the rated-range setting is currently 240 km), no GPS fix (parked).

**Not yet verified:** behaviour while the user opens the unit's own battery and motor pages and other apps; a real drive (current under load, GPS distance, calibration); a restart of the MainUI process (the provider path, i.e. automatic start after boot); longer-term stability (hours). Undo: `adb shell pm uninstall com.ts.evhook` (confirm with `pm list packages | grep -c evhook` and an end marker). Quick pause without adb: create the file `evfeed.disable` in `TsCarInfo` with the unit's file manager.

## 13. First drive with the v2.0 feed (2026-10-30, 23:15 to 23:41)

**Stability:** `MainUI` (pid 22786) was 1 h 29 min old at 23:40, i.e. it did not restart during or after the drive; the tombstone count stayed at 10 (last one 22:10:50); Wi-Fi (`wpa_supplicant`) also stayed up. This is the first drive with the redesigned feed. (Pulled to `drive_logs\ev_log_drive2.csv`, 44,996 bytes, size matched on the unit.)

**Data (23:15 to 23:41, 15.3 min of logged time, moving 23:21 to 23:38):**
- Max 6,918 rpm, median moving rpm 2,337, 90th percentile 4,399; about 10 % of logged time between 2,800 and 3,200 rpm (17 samples between 2,900 and 3,100). The rpm scale is real: a 10x smaller scale would mean about 690 rpm at 36.7 kW peak, i.e. ~500 Nm, which is implausible for this motor.
- Energy: 866 Wh out, 117 Wh regen, 748 Wh net, peak discharge 36.7 kW, peak regen 9.9 kW. SOC 70 to 66 % = 760 Wh at 19 kWh (+/- 190 Wh). Energy and SOC agree again.
- 28,932 motor revolutions counted.

**Distance is still unresolved.** The app showed 6.1 km, which is exactly 28,932 revs x the old default 0.000211 km/rev: it was NOT GPS. Cause: the first app version paused tracking and GPS whenever it was not on screen, so the drive was processed afterwards from the log without GPS. The user's speed reference "3000 rpm = 32 km/h" gives 0.0001778 km/rev (gear ratio about 10.7 for a 1.9 m wheel), i.e. 5.14 km and 14.5 kWh/100 km, against 6.10 km and 12.3 kWh/100 km with the default. That is an 18 % difference in consumption. The 32 km/h pair comes from the user's reading of the car's speed display (not logged) and the dash may read a few percent high; ground truth needs the odometer or a drive with GPS tracking.

**App v0.2 (versionCode 2, installed 23:5x, sha1 `4e35b5228b9bd1aa18a11f41931b5caaabc220aa`):**
- New `Core` owns the engine, log reader and GPS and keeps ticking in the background; `TrackService` (foreground service, low-priority notification) keeps the process alive; `BootReceiver` starts it after boot; the activity only displays.
- The "NO RECENT DATA" warning now appears after 45 s, not 10 s (the feed logs once per 30 s while idle).
- New Settings field "Speed at 3000 motor rpm (km/h)" sets the rpm-to-distance factor directly. It affects future samples only; the 6.1 km already stored is not recomputed.
- Verified on the unit: foreground service running (`isForeground=true`); with the app sent to the background (the unit's launcher in front) the app's GPS request stayed active and the process stayed alive; state survived the reinstall (trip 6.1 km). Not yet verified: boot auto-start, and GPS-based distance on a real drive.

## 14. App v0.3.1, vehicle-off test, TPMS findings (2026-10-31)

**App v0.3 / 0.3.1 (installed, sha1 `3998f8ded55668c8059d0fe5a728c96fe93d476c`):** launcher icon added (range gauge + bolt, generated by `app_src\make_icon.py`, preview `app_src\icon_preview_512.png`); the engine now uses trapezoidal integration (average of consecutive readings) and a 20 rpm dead-band (headline totals changed by under 0.5 % on both recorded drives; the regen subtotal reads lower because power swings net out inside each interval); a calibration log `gps_rpm_log.csv` (rpm next to GPS speed, altitude and accuracy, no coordinates) is written to `/storage/emulated/0/Android/data/com.evrange.app/files/` while moving with a GPS fix; the `FLAG_KEEP_SCREEN_ON` flag was removed after `dumpsys power` showed the app's `SCREEN_BRIGHT_WAKE_LOCK` (WorkSource 10060) was the only wake lock held, which could have stopped the unit from sleeping with the vehicle off.

**Vehicle-off test (in progress):** `tools\vehicle_off_monitor.ps1` samples the unit every 30 s (ping, adb, kernel uptime, wlan0 bytes, wakefulness, wake locks, last log line) into `drive_logs\vehicle_off_monitor.csv`. First result: the unit answered normally until 00:11:23 and was unreachable from 00:11:55, i.e. within about 30 s of the vehicle being switched off. The unit has no SIM, operator or baseband (`gsm.sim.state` empty), so Wi-Fi and Bluetooth are its only links. Kernel uptime was about 46,212 s (12.8 h) at the last sample before the switch-off.

**Vehicle-off test result (00:11 to 00:26, 14.5 min off):** the unit was unreachable from 00:11:55 to 00:25:43 and answered again at 00:26:17. At that moment kernel uptime was 47,108 s, i.e. +896 s over 894 s of wall-clock: the unit did NOT reboot (`system_server` pid 680 and `MainUI` pid 22786 unchanged; `system_server` 13 h 05 min old). The Wi-Fi interface was reset (wlan0 byte counters restarted at 32,727 / 9,771 instead of continuing from 337,544 / 134,719) and `wpa_supplicant` is a fresh process started at the moment the unit came back, so Wi-Fi is powered down while the vehicle is off. The car-data feed wrote nothing between 00:11:44 and 00:25:40 (the idle logging interval is 30 s), so the CPU and all apps were frozen, not just the network. The feed resumed by itself at 00:25:40, about 35 s before Wi-Fi came back, with the same process, and the EV Range service survived. Power manager values (`mLastWakeTime`, uptime milliseconds, about 22,920 s) compared with kernel uptime (about 47,100 s) suggest roughly 24,000 s (about 6.7 h) of the 13 h since the 11:21 boot were spent suspended, because the kernel boottime clock counts suspend while the power manager's uptime does not. Conclusion: with the vehicle off the unit suspends; it cannot send data (no CPU, no Wi-Fi, no SIM). The "10 hours running" is mostly suspended time. Limits of this test: only 14.5 min; brief periodic wake-ups would go unseen by a 30 s ping monitor, so an overnight test with a check of the power manager's wake history would be needed to rule them out. The `adb shell date` call that would have given the exact time of that last reading failed (connection closed), so the "about 98 s ago" values are relative to a reading made roughly 1 to 2 minutes after 00:26.

**TPMS (from the decompiled `MainUI`; nothing was changed or called on the unit):** the unit contains two unrelated TPMS systems.
1. *The car's own, over CAN (profile Jimai FL, subtype 3):* `CanJiMaiFlTpmsInfoView` reads `JiMaiFl_AbsInfo` (`RstSta` reset state 0 ready / 1 resetting / 2 failed / 3 success, `TpmsFail` fault flag, `TpmsWarn` warning flag; query 53) and `JiMaiFl_TpmsInfo` (per wheel `Warn[4]`, `Temp[4]` = raw - 40 deg C, `Pres[4]` = raw x 0.01 bar; query 41). The "tap to reset" button sends `CanJni.CanCmdCarSet(8, 0, 0)` to the car. The pressure and temperature numbers are only drawn when `GetSubType() == 3`, which is this unit's configured subtype, so the number fields are enabled even though a car with only an ABS-based system may never fill them (they would show 0.0 bar / -40 deg C or stay "--"). What the car really sends has not been checked.
2. *The unit's own aftermarket receiver (`StTpms`, settings in `TPMS_V5.bin`):* talks over the unit's UART at 19200 baud (`Iop.UartOpen`, `UartRead`, `UartSend`, commands A6/B3) to an external receiver with up to 8 paired sensors (IDs, battery, temperature, pressure, warnings: no data, low power, no signal, leak, low/high pressure, high temperature), a sensor pairing ("study") screen, unit choice bar / kPa / psi and low/high pressure and temperature thresholds (`TPMSMainActivity`, `TPMSSetMainActivity`, `TpmsStudyActivity`). With no receiver and no sensors these screens have nothing to show. These are probably the menu options the user sees.
Open: look at what the unit's TPMS page actually shows with the car on (screenshot, read-only) before deciding whether the car sends any direct data. Reading `JiMaiFlGetTpmsInfo` from the feed was NOT added (extra native calls on the main thread carry the risk that caused the earlier crashes).

## 15. Speed calibration (GPS and rpm sync): tool ready, data still to collect (2026-10-31)

**Status:** unresolved. The distance for a trip is still uncertain (5.1 km with the user's "3000 rpm = 32 km/h" reading, 6.1 km with the old default of 38.0 km/h at 3000 rpm). The dashboard speed is not a reference: UNECE R39 allows a speedometer to read up to 10 % + 4 km/h above true speed and never below it, so the true speed at 3000 rpm is probably a little under 32 km/h.
**Why GPS and rpm must be synchronised:** the rpm in the log is up to 2 to 4 s old (read one 2 s poll after the request, plus the unit's own latency) and GPS speed has its own latency, so pairing them at the same timestamp gives a biased ratio unless the offset is found first.
**Tool:** `tools\calibrate_speed.py` (in the repository copy; `py -I calibrate_speed.py gps_rpm_log.csv`). It tries offsets from -6 to +6 s, picks the one with the smallest residual, fits speed = k x rpm through the origin at that offset, and prints km per motor revolution, the "speed at 3000 rpm" value for the app's Settings, the fit quality, the ratio per speed band (a gear change or slip would show as a step) and per road gradient (altitude is noisy). Self-test on a simulated drive (true 32.00 km/h at 3000 rpm, offset +1.50 s): recovered offset +1.50 s and 31.98 km/h, ratio flat across all speed bands.
**Data needed:** `gps_rpm_log.csv`, written by EV Range v0.3 or later to `/storage/emulated/0/Android/data/com.evrange.app/files/` while the car is moving with a GPS fix (rpm above 20 or GPS speed above 0.5 m/s). It did not exist on the unit at 2026-10-31 (no drive since v0.3 was installed). A good drive: outdoors with a GPS fix before moving off, a few minutes of steady speed at two or three different speeds, some acceleration and braking, ideally including a gentle slope. Then pull the file, run the tool, enter the speed-at-3000-rpm value in Settings, and re-check on a second drive (and against the odometer if noted).

## 16. Calibration drive, power limits and app 0.4 to 0.4.2 (2026-10-31)

**The drive:** 07:52 to about 09:00, GPS fix throughout (`gps_rpm_log.csv`: 729 rows 07:52:03 to 08:55:13), hard acceleration and, the user believes, maximum regen; top speed about 91 km/h (the car's 105 km/h top speed was not reached because of traffic). Pulled and size-verified (`ev_log_drive3.csv` 141,292 bytes, `gps_rpm_log_drive3.csv` 44,146 bytes). A first analysis window that started at 08:00 cut off the first eight minutes of the drive and produced a false 60 % gap between GPS and rpm distance; starting the window at the real first movement (07:51) removed it. Lesson: start analysis windows before the first movement.

**rpm-to-speed calibration (`tools/calibrate_speed.py`):** best offset -1.00 s, i.e. the rpm series lags the GPS series by about one second; **31.00 km/h at 3000 rpm** (0.010335 km/h per rpm = **0.0001722 km per motor revolution**), R2 0.9865, rms 1.96 km/h over 658 samples. The ratio is flat across speed bands (31.5 / 31.0 / 31.1 / 31.3 km/h at 3000 rpm for 5-20 / 20-40 / 40-60 / 60+ km/h), i.e. one fixed gear ratio. By gradient (GPS altitude is noisy): downhill 30.3, flat 31.1, uphill 31.5. The dashboard reading of 32 km/h at 3000 rpm was therefore about 3 % high, as the speedometer regulation (UNECE R39) predicts. The earlier assumed ratio (38.0 km/h at 3000 rpm) overstated every earlier distance by about 22 %.

**Cross-check, full drive (`tools/drive_summary.py`):** distance by calibrated rpm 10.95 km against GPS 10.88 km (0.6 % apart; 0.4 % on identical rows). Energy: 1,999 Wh out, 291 Wh regen (15 % recovered), net 1,707 Wh; SOC 66 to 56 % (1,900 Wh +/- 190). **Consumption 15.6 kWh/100 km (rpm) / 15.7 (GPS).** Recomputed with the calibrated ratio, the two earlier drives give 15.8 kWh/100 km (2.63 km) and 15.0 kWh/100 km (4.98 km), so the car uses about 15 to 16 kWh/100 km in this use, not the 12 to 13 first shown. Maximum rpm 8,877 (92 km/h by the ratio); 105 km/h corresponds to about 10,160 rpm.

**Power limits (`tools/power_peaks.py`, whole drive):** discharge plateau, the five strongest samples all 35.1 to 35.7 kW at 112 to 114 A and 313 V (a current limit; battery voltage sags from about 329 V to 312 to 313 V under full load); regen peak 11.1 kW (-34 A at 325 V), next 10.5, 10.5, 9.9 and 9.8 kW (-30 to -32 A). Percentiles: discharge p50 2.9, p90 11.3, p99 34.9 kW; regen p50 2.6, p90 8.2, p99 10.5 kW.

**App changes (versions 0.4, 0.4.1, 0.4.2; installed by `pm install -r`, SHA-1 of 0.4.2 `ae459cd66170b03a7d3d7f9f32d52bffc8ed01ad`):**
- Power bar scale from the measured peaks plus a 15 % allowance, rounded up: **-13 kW to +42 kW** (`PowerScale.java`, tested); thin ticks at the measured limits (-11.1 and +35.7 kW); the bar turns red from 85 % of the discharge peak (30.3 kW). Verified on the unit with replayed readings: +35.7 kW fills the bar to the right tick in red with headroom beyond, -11.1 kW fills green to the left tick.
- The calibrated ratio is the new default (`kmrev2` setting key; the old key is ignored) and the app rebuilds its saved history once from `ev_log.csv` (history version 2), after which the 7-day chart read Fri 15.0 and Sat 15.6 kWh/100 km, all-time 15.4, range 69 km at 56 %.
- A speed readout (power row, right end): GPS speed if it agrees with the rpm-derived speed (within 20 % or 5 km/h), otherwise rpm x calibrated ratio; 0 when the motor is below 20 rpm; "--" when the data is stale; the label shows (GPS) or (rpm). A first version trusted any fresh GPS fix and showed "0 km/h (GPS)" while the replayed motor was at 3000 rpm; the agreement rule fixed that (tested: `SpeedTest.java`).
- An update only installs over the existing app if it is signed with the same key. A build made with a new keystore was rejected (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and nothing changed; the build script takes `-Keystore` and `-StorePass` for this reason.

**Observations about the unit (same morning):** a cold boot at 04:56:09 (`SYSTEM_BOOT` in the dropbox; kernel uptime about 15,200 s at 09:09 against about 11,750 s awake). The feed started by itself after that boot (`start requested ... delay=60000 ms` at 04:56:07), confirming boot auto-start; its first poll ran at 07:51:27 because the unit was suspended in between (handler delays count awake time only). No crash record since 22:10:50 on 2026-10-30 (the older tombstones were purged by the system). The EV Range app was found `stopped=true` with no service running: something on the unit force-stops user apps (cause not identified), so a stopped app does not receive the boot broadcast. The app catches up from `ev_log.csv` whenever it is opened, and with the calibrated ratio the rpm distance is within about 1 % of GPS.

## 17. Top speed, power by speed, drive mode and regen level (2026-10-31)

**Top speed and rpm.** With the calibrated ratio, 10,000 rpm is 103.3 km/h and 105 km/h is 10,163 rpm. The user suspects a motor limit of about 10,000 rpm (about 104 km/h) and has seen 105 km/h on the dashboard once or twice, maybe downhill. That is consistent: the dashboard reads a few percent above true speed (UNECE R39), so a dashboard 105 km/h is roughly 100 to 103 km/h true, i.e. about 9,700 to 10,000 rpm, and downhill the wheels can push the motor to the limit. It is not verified: the highest rpm ever logged is 8,877 (92 km/h). The next time the car reaches that speed, the logged maximum rpm will confirm or refute it.

**Drive modes (D / S), from the user.** The car has D and S. In D the top speed is 65 km/h on the dashboard (about 63 to 64 km/h true, about 6,100 to 6,300 rpm by the ratio). The user states: if the speed is over 65 km/h the car was in S. By that rule drives 2 (max 6,918 rpm = 71 km/h; 5 samples above 6,300 rpm) and 3 (max 8,877 rpm = 92 km/h; 23 samples above 6,300 rpm) were at least partly in S. Drive 1 (max 4,998 rpm = 52 km/h, peak 24.5 kW / 76 A against about 36 kW / 114 to 115 A in drives 2 and 3) could be D or S; its lower peak may be gentler driving or a lower power limit in D, which the data cannot separate. The mode is not logged today.

**Correction (same day): `Qdms` is not D/S.** The EC01 screen item built on `JiMaiFl_Set2.Qdms` is labelled "Vehicle Start Mode" with two buttons, "Normal" (value 0, `use_ec01_bz`) and "Beginner" (value 1, `use_ec01_xs`); choosing Beginner asks for confirmation ("Drivers need to fasten their seat belts in order to start the driving vehicle"). It has nothing to do with D and S, which are most likely the gear selector. The earlier idea of logging `Qdms` to learn D versus S was wrong and is withdrawn. See section 18 for where gear information might come from.

**Regen level.** The EC01 screen shows three energy-recovery buttons that read `JiMaiFl_BmsInfo.Nlhs` as 2, 1 and 0. The logged `bms_nlhs` was 1 in every row of all three drives (958, 479 and 661 rows), so every drive used the middle regen level. The measured regen peak of 11.1 kW (-34 A; next 10.5, 10.5, 9.9, 9.8 kW) is therefore the peak at the middle level, not necessarily the car's maximum: the strongest level may regenerate more. The power bar's regen end (-13 kW) is tuned to the middle level. A drive at the strongest level would show whether it needs widening.

**Power by speed (`tools/power_by_speed.py`, all three drives).** Most power ever seen per motor-speed band: discharge 8.9 kW at 0-10 km/h, 27.1 at 10-21, 31.2 at 21-31, 33.2 at 31-41, 36.0 at 41-52, 36.7 at 52-62, 34.9 at 62-72, 34.3 at 72-83, 34.0 at 83-98 (few samples above 62 km/h); regen peaks 5.2, 7.6, 9.2, 10.5, 9.9, 11.1 and 9.1 kW in the same bands. So the roughly 35 kW limit is available from about 40 km/h up to at least 92 km/h under hard acceleration. Steady cruising draws far less: median 1.3 kW (0-10 km/h), 1.6 (10-20), 2.6 (20-30), 3.6 (30-40), 5.2 (40-50) and 6.5 kW (50-60), i.e. about 14 to 15 % of the +42 kW side of the bar at 40 to 60 km/h.

**The 52 km/h screenshot.** `speed2_high.png` was a synthetic test reading (+35.7 kW at 5,000 rpm) fed through the demo mode to check that the bar draws its maximum; it is not a measurement. It is nevertheless realistic: hard acceleration at 52 to 62 km/h reached 36.7 kW in the logs, while steady 52 km/h draws about 6 kW. The bar shows electrical power, not speed.

**Still open (sections 16 and 17):** a second calibration drive to confirm the ratio; whether the top speed really is about 10,000 rpm (the logged maximum so far is 8,877); how D, S, N and R appear in the data (parked gear test with the probe, section 18; `Qdms` is the start mode, not D/S); the regen peak at the strongest regen level (all drives so far were at the middle level; decision 2026-10-31: the user cannot brake any harder, so 11.1 kW is taken as the practical regen peak and the bar's -13 kW end stays; a lift-off at the strongest level from 50 to 60 km/h would be a gentle way to test it if ever wanted); whether D has a lower power limit than S; the cause of the app being force-stopped and of the 04:56 cold boot; nothing about the rated range: **decision (user, 2026-10-31): the app's rated range stays at 240 km.** Some documents quote 220 km, but the head unit's own native screen shows 240 km, and the app's "NEDC-based (car display)" row is meant to match that screen, so 240 is the value used (code default in `Core.java` and the Settings label were changed from 220 to 240 to agree; the installed 0.4.2 already holds 240 in its saved settings, so it behaves the same until the next build).

## 18. The car-settings screen, regen labels, the open car-info interface and the probe (2026-10-31)

**Regen level labels (decoded from the app's resources).** On the EC01 screen the item "Energy Recycle Intensity" (`use_ec01_nlhs`) has three buttons, in code order High (`CanCmdCarSet(4)`), Medium (`CanCmdCarSet(5)`) and Light (`CanCmdCarSet(3)`); the read-back is `Nlhs`: 2 = High, 1 = Medium, 0 = Light. The user was on Medium in every drive, which matches `bms_nlhs` = 1 in every logged row.

**Full settings map of that screen (label, values, command sent).** The unit sends these only when its own buttons are pressed; none of our software sends any.

| Item (English / Chinese label) | Values | Command |
|---|---|---|
| Horn sounds after locking the car / 闭锁鸣笛 (`Bsmdfk`) | ON, OFF | `CanCmdCarSet(7, 1 or 0)` |
| Automatically Door Lock / 行车落锁 (`Csls`) | OFF, 20 km/h, 40 km/h | `CanCmdCarSet4(4, 0/1/2)` |
| Vehicle Start Mode / 启动模式 (`Qdms`) | Normal, Beginner (seat belt required to drive) | `CanCmdCarSet4(1, 0)`; Beginner after a confirmation: `CanCmdCarSet4(1, 1)` |
| Lighting For Me / 伴我回家 (`Bwhj`, `Bwhjkqj`) | OFF, 15 s, 30 s, 45 s | `CanCmdCarSet4(3, 0..3)` |
| Driving Data Reset / 小计里程清零 | Clear (confirmation: "Do you need to clear the mileage?") | `CanCmdCarSet(2)` |
| TPMS Reset / 胎压复位 | Reset (confirmation) | `CanCmdCarSet(8)` |
| Energy Recycle Intensity / 能量回收 | High, Medium, Light | `CanCmdCarSet(4)`, `(5)`, `(3)` |

The car's base settings page (`CanJiMaiFlSetInfoView`) also has speed-dependent volume and "lock horn" items.

**Behaviour at the top speed (drive 3, 08:31:33).** Hard acceleration held 33.7 to 35.7 kW (108 to 114 A) all the way to 87.8 km/h; at the 91.7 km/h peak the car had just lifted off (-2.9 kW). Lift-off regen at the Medium level was only about -3 to -4 kW in the first sample after lifting. While braking the regen settled at about -9 kW (78 km/h), -8.8 (59), -7.8 (42) and -4.9 kW (26 km/h), falling to zero as the car stopped. The car slowed from 91.7 to 8 km/h in about 10 s (about 2.3 m/s2): for a car of roughly one tonne (an assumption) that is about 46 kW of braking power at 72 km/h against about 9 kW of regen, so the friction brakes carried most of it. That supports the user's view that the regen peak (about -9 to -11 kW, -28 to -34 A) is a limit of the car and not of how hard the pedal is pressed.

**Dashboard speed error (open).** The user reports reaching 100 on the dashboard in drive 3; the logged peak is 91.7 km/h true (8,877 rpm; the highest GPS speed was 91). That is about 9 % above true speed, against about 3 % at 31 km/h (dashboard 32 km/h at 3,000 rpm), so the dashboard error appears to grow with speed. If so, the section 17 estimate that a dashboard 105 means about 9,700 to 10,000 rpm is too high: at +9 % it would be about 96 km/h true (about 9,300 rpm), and a 10,000 rpm limit (103 km/h true) would read about 112 on the dashboard. A clean test: note the dashboard against the app's speed readout at steady 50 and 80 km/h.

**The unit's car-info interface is open to every app.** `com.ts.can.carinfo.CarInfoService` in `MainUI` is declared `exported=true` with no permission, action `com.ts.can.carinfo.CarInfoService`, and implements `ICarInfoService` (descriptor `com.ts.can.carinfo.ICarInfoService`). Read methods include `requestCarBaseInfo()` (transaction 6, an int[82]), `requestCarDoorInfo()` (4), `requestCarIllInfo()` (5) and `requestCanRecevieData(int)` (25). It also exposes `SendCmd`, `UartOpen`, `UartSend`, `UartRead` and `UartCanSend`, so **any app installed on the unit can send commands to the car's CAN bus and use its serial port**. That is a security weakness of the unit (do not install untrusted apps on it). Our tools only ever call the read method.
`requestCarBaseInfo()` only copies static Java fields (`Can.mCarMsg`), which the unit's own loop refreshes through `Can.updateCarInfo()` (`CanJni.GetCarInfoAidl`) in `CanFunc`; a client reading it does not enter the native library. The array layout (index: field): 0 Avalid, 1 SpeedDw, 2 Speed, 3 Rpm, 4 WaterTemp, 5 TpmsDw, 6 to 9 Tpms[0..3], 10 OilDw, 11 AveOil, 12 InstantOil, 13 EndurOil, 14 Distance, 15 BatV, 16 DistanceDw, 17 TurnLeft, 18 TurnRight, 19 DriveSafe, 20 HighBeam, 21 LowBeam, 22 FootBrake, 23 HandBrake, 24 Stalls (gear), 25 Ill, 26 WarnLed, 27 FrontFogLed, 28 RearFogLed, 29 to 38 assorted (Jqmwz, Syyl, Lqywd, Jyyl, Dqyl, Syyw, Zcbz, PassengerSafe, Bsqyw, RearOpen), 39 AveSpeed, 40 and 41 DriveDis1 and 2, 42 to 58 the VIN (never shown or logged by our tools), 59 WidthLight, 60 WiperSpeed, 61 to 66 doors (inferred), 67 OutTemp, 68 OilTemp, 69 DoorLock, 70 ChargeSta, 71 WarnSta, 72 ACC, 73 Fdjqdhsj, 74 Jqwd, 75 Ymtbxdwz, 76 Maintenance_Mileage, 77 Soc, 78 DriveMode, 79 to 81 seat belts.

**The probe (`probe/`, package `com.evprobe.app`).** A read-only app with no permissions that binds to that service and calls only transaction 6 once a second while its screen is open. It shows the key values, marks and lists every value that changed since it started, has buttons P, R, N, D, S and Other to record the moment of each gear change (`markers.csv`), and logs all values except the VIN to `carinfo_probe.csv` in its app files folder. Installed on the unit on 2026-10-31 (SHA-1 `0ce0c18ab1d8124f29f000eb12fbc2a57df54980`); the unit stayed healthy (same `MainUI` process, no new crash record; the one new record that day was the unrelated `sd_mdnsd` daemon, which also crashed twice the evening before).

**First reading, car parked and stationary (17 readings):** Speed 0, Rpm 0, Soc 0, Tpms0 to 3 0, TpmsDw 0, DriveMode 0, ACC 0, FootBrake 0, HandBrake 0, DoorLock 0, RlSafetyBelt 0; **Stalls (gear) 1; ChargeSta 1; Distance 15,466,698** (nothing changed). So this car does not fill speed, rpm, SOC or tyre pressures here; whether gear, brake, hand brake, drive mode and the large Distance counter respond is for the parked gear test. Whether `Distance` is the odometer is unknown (in metres it would be 15,467 km); it needs a comparison with the dashboard.

**Parked gear test (planned):** foot on the brake and handbrake on, ignition on; shift through the positions one at a time, wait a few seconds, tap the matching button on the probe, and note which fields are marked as changed; also press the brake and handbrake, open a door, buckle a belt. Then pull `carinfo_probe.csv` and `markers.csv` and map them. The user can see no S or D indicator on the head unit, so the gear may well not be transmitted; the test settles that.

## 19. First parked probe test: what the car-info interface really carries (2026-10-31)

**Setup.** Probe v0.1 running on the unit for 562 s (553 readings), the car parked and stationary at 56 % SOC. The user shifted through D, N, S and R and tapped the matching buttons (markers at 386.1 s D, 392.2 s N, 403.7 s S, 415.6 s R; no P tap, because P on this car is an old-fashioned handbrake lever, not a gear). Engaging R switches the unit to its reverse-camera screen, which sends the probe to the background; the probe only reads while it is on screen. The dashboard odometer read about 19,000 km at the time.

**What carries data (the last reading; only 5 of 65 logged fields are non-zero):** `Syyw` (index 34) = 56, which equals the battery SOC measured by the feed (56 %); `EndurOil` (index 13) = 136 and 124 (see below); `Distance` (index 14) = 15,466,698 and never changed; `Stalls` (index 24) = 1 and never changed, including while R was selected; `ChargeSta` (index 70) = 1. Everything else, including Speed, Rpm, Soc (index 77), the four tyre pressures, the handbrake, foot brake, ACC, DriveMode and the seat belt, stayed 0. So this car does not send its gear, brake or handbrake state, speed, rpm or tyre pressure through this interface (the handbrake and foot brake were probably not operated during this test, so those two are not proven absent).

**`Distance` is not the odometer in any obvious unit.** 15,466,698 against an odometer of about 19,000 km (ratio 803.7). It did not change while parked; whether it counts while driving is untested.

**`EndurOil` flips between 136 and 124, and the flips match the S button.** Timeline: 136 until 340.6 s; 124 from 340.6 to 348.6 s; 136 to 368.7 s; 124 from 368.7 to 370.7 s; 136 to 403.1 s; **124 from 403.1 to 413.2 s**; 136 to the end. The S marker is at 403.7 s (0.6 s after the drop, as expected if the user taps just after shifting) and R at 415.6 s (2.4 s after the return to 136). The two earlier dips happened before the first marker, so they were probably earlier shifts into S. On this reading, **S = 124 and D, N and R = 136**; the pairing is an inference that still needs confirming with an explicit, timed D/S toggle test.

**What `EndurOil` is.** It is the car's own remaining-range figure in km. At 56 % SOC: 136 km corresponds to a full-pack range of about 243 km (241 to 245 allowing for SOC rounding) and 124 km to about 221 km (219 to 223). Those are the 240 km and 220 km figures the user has been quoting: **the car's own range claim is about 240 km in D and about 220 km in S** (a constant per mode times SOC, not adaptive to driving). It explains why the head unit's own screen shows 240 while some documents say 220. Measured reality is about 15.5 kWh/100 km, i.e. about 123 km on a full 19 kWh pack, so at 56 % the car claims 136 km (D) or 124 km (S) against about 69 km by the app's estimate.

**Consequences (proposed, not built).** The app, in its own process and read-only, could read `EndurOil` and `Syyw` through the same open interface (no hook, no native call) to show the car's own live range next to its estimate, and could tell D from S by the ratio `EndurOil / Syyw` (about 2.43 in D and 2.21 in S; a threshold near 2.32 works while SOC is not very low). That would also allow consumption statistics per mode and a check that speeds above 65 km/h only occur in S.

**Still to do:** an explicit D / S toggle test with timed markers to confirm the pairing; pull the handbrake and press the brake pedal with the probe open (two untested fields); a probe run while driving to see whether `Distance` counts and what the fields do at speed.

## 20. App 0.5 to 0.7, feed 3.0 and 3.1, the fourth drive, the speed-linked volume and the air conditioning (2026-10-31 to 2026-11-03)

**App versions.**
- **0.5, the car's own range and drive mode.** A read-only client of the unit's car-info service (transaction 6 only, once a second, on its own thread) shows the car's range next to the app's estimate. D or S is worked out from the ratio of that range to the battery percent (about 2.43 km per percent in D, 2.21 in S; the threshold is the midpoint, about 2.32; unknown below 10 % battery or for implausible ratios). Consumption is kept separately for D and S, counting only samples taken while the mode was known. The first live reading was 136 km at 56 % (D).
- **0.5.1, a red glow around the screen edges in S.**
- **0.5.2, charging.** The power strip showed charging as REGEN, and charging energy was being counted as negative consumption. Standing still with current flowing in is now charging: drawn in blue, kept out of consumption and regen, and the saved history is rebuilt once from the log (history version 3). Regen while moving is unchanged.
- **0.6 to 0.6.2, readability and settings.** Lighter labels, white main text, a brighter bar track and buttons, bigger small print (for bright sun). A speed source choice in Settings (Auto, motor rpm, GPS; rpm is the default and also decides the counted distance). The default battery capacity is 19.14 kWh; a saved 19.0 is treated as the old default. The Settings dialog was taller than the unit's screen and hid the new options, so it now scrolls. The unit's screen is 1024 x 600 physical with a 1280 x 720 override: screenshots are 1024 x 600 but input coordinates are 1280 x 720 (scale by 1.25).
- **0.7, the floating range bubble and the speed-linked volume switch** (see below).

**D / S switch test (parked).** The car's range flipped between 136 and 124 km at 56 % three times, each within seconds of the user's switch. Over the following drive the range per percent was 2.42 in D (2.38 to 2.44) and 2.21 in S (2.18 to 2.23), so the threshold is safe. In the log D never exceeded 60 km/h and S reached 95; the car's own display limits D to about 65.

**The fourth drive (11:29 to 12:08, three legs, about 12.4 km, 67 to 54 % battery).** Energy out 2777 Wh, regen 467 Wh (17 % recovered), net 2310 Wh, 18.7 kWh/100 km (the usual figure is about 15.4; full-power runs and time in S explain it). Highest motor rpm 9683, which is 100 km/h true (the highest GPS speed was 101). Peak discharge 36.2 kW at 115 A (the pack voltage sags to 311 V); it sits at 34 to 36 kW from 40 to about 90 km/h and eases to about 33 kW at 90 to 100 km/h. Peak regen 10.3 kW at 31 A on the middle regen level, strongest at 52 to 62 km/h and about 9 to 9.5 kW above that. Pack voltage 330.6 V at rest and 314 V at about 110 A: a sag of 16.6 V, roughly 150 milliohm. **The motor temperature reached 151 °C** after about three minutes of repeated full-power pulls (104 to 151 °C), and fell back to about 124 °C within 36 s of easing off; the controller stayed at 46 to 48 °C and the cells at 31 to 34 °C. No power drop was seen at 140 to 150 °C. Over this drive the energy per percent of battery was about 18.4 kWh per 100 %, consistent with a 19.14 kWh pack within the whole-percent resolution.

**Speed.** The calibration holds: 31.0 to 31.3 km/h at 3000 rpm in every speed band from 5 to 100 km/h, and no consistent difference between uphill and downhill. **The dashboard reads high:** the user's paired readings were dashboard 63 against the app's 60, and dashboard 104 against 100 (about +3 to +4 km/h, +5 % and +4 %), inside the UNECE R39 allowance. The dashboard 105 seen earlier is therefore about 101 km/h true, and the car's real top speed is probably about 100 to 101 km/h. This replaces the earlier estimate of +9 % at 92 km/h. **GPS lag is not stable:** the best-fit offset between GPS and rpm was +1.5 s on the first drive, -1.0 s and 0.0 s on the two drives of 2026-10-31, all within the 2 s logging step, so no consistent lag can be claimed. The late drive's GPS was noisier (rms 6 against 2 km/h), which is the better argument for rpm as the default.

**Battery and BMS fields: what exists.** The CAN profile (Jimai FL) defines only seven battery fields: pack voltage (`Dcdy`), current (`Dcdl`, raw minus 500 is amps), SOC, hottest and coldest cell (raw minus 40 is degrees), the regen level (`Nlhs`) and the horn-on-lock setting (`Bsmdfk`). The motor structure has rpm, motor and controller temperature and three values that are not decoded (`Djmxdl`, `Djnjyxw`, `Sjnj`). **There is no state of health, no cell voltages, no capacity, no charge or discharge limits and no insulation or pack-temperature field**; a few other car profiles in the unit's code define an `SOH` field, this one does not. The unit also has a raw-frame debug call (`CanGetDataDebug`, up to 101 values) that has not been tried; the CAN box probably forwards no more than the decoder uses.

**The speed-linked volume.** For this CAN type the unit adds volume with speed from the car's own signal (`Sdylbczy`) only while its internal setting number 70 is 0 (`CanIF.VolWithSpeed`, applied by `MainUI.SyncVolBySpeed` through `Evc.evol_vol_add_by_speed`). The unit has an on/off switch for it ("Speed with volume", writes setting 70 through `FtSet.SetCanS`) on the base car-settings page, but for this sub-type that page is replaced by the EC01 screen, which has no such item: there is no way to turn it off on the screen.
- **Feed 3.0 may write exactly this one setting.** It checks every 10 s, reads setting 70, and if a request file holds exactly `0` or `1` and differs from the current state, writes it with the unit's own call and reads it back. At most 6 changes an hour; a read-back that differs blocks further writes; any failure disables only this feature and never trips the feed; the kill switch and the CAN-type check come first. The request file is `speedvol_request.txt` in the app's own storage folder (no permission needed to write it) or in the feed's folder; the feed reports `evfeed_speedvol.txt` (`setting70`, `boost` ON/OFF/UNKNOWN, `request`, `note`).
- **App 0.7 Settings** shows "Now: ON / OFF / unknown" and a "Stop the speed-linked volume" checkbox (greyed when the feed's state is unknown or older than 90 s), and asks for confirmation before it writes the request. Any other app could write the same request file; the worst it can do is switch this one setting.
- **Observed:** setting 70 read 0 (boost on) before; the user ticked the box on 2026-11-03 and the feed set it to 1 and read it back (boost OFF). **Not yet verified on a drive:** that the volume then stays constant as speed rises.

**Restarting the unit's main process.** Installing the feed package does not restart `com.ts.MainUI`, so a new feed version only runs after the process restarts. `am force-stop` does nothing to this persistent system app. `am crash com.ts.MainUI` makes the system restart it (a crash entry is logged, the unit's music and screen drop for about a minute, and the feed starts 60 s later). The crash-loop breaker allows two starts in 10 minutes.

**The floating range bubble (app 0.7).** A small draggable bubble with the live range (outline green, amber, red, or blue while charging; "S" or "charging" underneath) drawn over other screens with `TYPE_APPLICATION_OVERLAY`. It needs the "Draw over other apps" permission, which the unit has no screen for; it is granted over adb: `appops set com.evrange.app SYSTEM_ALERT_WINDOW allow`. Tested on the unit: it draws over the unit's own home screen, a tap opens the app, it is hidden while the app is in front and while parked in the default mode. **Camera safety:** selecting R puts the reverse camera on screen before the car moves, so the default mode, "only while driving", shows the bubble only while the motor rpm is forward (30 or more); the "always" mode hides it while reversing (negative rpm) and for 8 s afterwards but cannot know that R was just selected. Negative rpm does occur in the logs (62 rows over several drives, small values, parking-style moves). **Not yet verified:** hiding while actually reversing. Its default position is the empty middle of the unit's top bar; a long press hides it until the app restarts.

**Air conditioning.** For this profile the unit does have an AC screen (`CanJimaiEc01OverseasFlACActivity`), but it only displays state read through `GetACInfo`; its buttons only record the time of the touch and send no command. Feed 3.1 logs that state read-only to `ac_log.csv` (inner-loop / recirculation, A/C, auto, fan, temperatures, vent modes, one error flag) on change and once a minute; it sends nothing to the car. **Result:** with the car on, three on/off cycles of the recirculation button produced no change in any column (the inner-loop and face-vent flags stayed at 1, everything else at 0), so those are placeholders and **the car's air conditioning is not connected to the head unit on this profile**. There is therefore nothing to read, remind about or control from the unit; the generic AC commands in the unit's code were not tried and not recommended, because nothing shows this car's CAN box accepts them.

**Still open.** Verify on a drive that the volume stays constant with the boost off; check that the bubble hides while reversing; the `Distance` counter (not the odometer) while driving; the raw-frame debug call; whether the true top speed is a limiter or the motor's maximum.

**Real-world range from four days of driving (2026-10-30 to 2026-11-03, 210 km in 25 trips of 3 km or more, plus shorter legs).** Distance from motor rpm (31.0 km/h at 3000 rpm); energy is the integral of pack voltage times current, regen netted; standing still with current flowing in (charging) is excluded.
- **Usable energy per 100 % of battery: about 17.9 kWh** (nominal pack 19.14 kWh, so about 93 %). Three discharges with no logging gap agree: 17.8 kWh per 100 % (70 to 56 %), 17.9 (58 to 12 %, 46 points on one day) and 17.9 (100 to 57 %, 43 points). The other split periods gave 28 kWh per 100 %, which is impossible: **the unit sleeps overnight while charging and logs nothing**, so SOC jumped 12 to 37 % and 29 to 61 % across two 10-hour gaps with no energy recorded. Energy per percent can only be taken over periods without such a gap, and the logged "charged" energy is an under-count.
- **Consumption.** Moving only: 12.7 kWh/100 km (210 km). Including energy used while standing still (air conditioning, fans, electronics: 3.06 kWh over the four days, about 1.5 kWh/100 km), 14.2 kWh/100 km, which is what the app's all-time figure shows. Trips of 3 km or more: median 13.5, quartiles 12.2 and 14.7, best 10.5, worst 19.5 kWh/100 km. The driving was mostly city: median trip average speed 22 km/h (range 12 to 39).
- **By speed (net):** below 20 km/h 25.6 kWh/100 km (stop and go, 14.5 % of the distance); 20 to 40 km/h 11.6; **40 to 60 km/h 9.2 (the best, 43 % of the distance)**; 60 to 80 km/h 14.5 and above 80 km/h 12.1 are based on only 8 and 4 km and must not be trusted. The fourth drive (hard acceleration to 100 km/h) gave 18.7.
- **By drive mode:** D 11.8 kWh/100 km over 125 km, S 18.2 over 14.5 km (about 54 % more, and the S distance includes full-power runs); 70 km were driven before mode logging existed.
- **Range on a full charge (100 % to 0 %, 17.9 kWh):** your driving so far including standing-still drain 14.2 kWh/100 km gives about 126 km; moving only, about 141 km; trip quartiles 122 to 147 km, best trip 170, worst 92; steady 40 to 60 km/h about 195 km; stop and go below 20 km/h about 70 km; S mode or spirited driving (18.2 to 18.7) about 96 to 98 km. Keeping a 10 % reserve takes about 10 % off. Motorway cruising (steady 80 to 100 km/h) has not been measured. **The car's own claim is 243 km in D and 221 km in S, so realistic range is about 52 to 58 % of it.** The app's live estimate used 19.14 kWh, about 7 % too optimistic; 17.9 kWh matches the measurement.

**Log cleanup (app 0.7.1, feed 3.2).** Every growing log now keeps one old copy: once it passes its limit it becomes `name.1.ext` (replacing the previous one) and a fresh file starts, so no log takes more than about twice its limit. Limits: the feed's data log 10 MB, the AC log 2 MB, the feed's status file 256 KB; the app's GPS and rpm calibration log 3 MB (about 400 KB per hour of driving) and car-info log 1 MB. All together under about 40 MB on a unit with 23 GB free. The app copes with the data log rotating (it starts again from the top and skips anything already counted).
