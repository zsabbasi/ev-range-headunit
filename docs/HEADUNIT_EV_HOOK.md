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

**Still open:** a second calibration drive to confirm the ratio; whether the 105 km/h top speed reads about 10,160 rpm; the cause of the app being force-stopped and of the 04:56 cold boot; the rated-range setting in the app (the NEDC row has been computed from 240 km, the user quoted 220 km).
