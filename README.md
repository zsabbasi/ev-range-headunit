# EV Range for a Lingbox head unit

A real-world range estimate for one specific small EV, computed from **what you actually drove recently** instead of the lab-cycle figure the car shows. It runs on the car's Android head unit (Lingbox, "Octapro 8529", board `825X_Pro`).

> **Experimental hobby project. Read [docs/SAFETY.md](docs/SAFETY.md) before installing anything.**
> One part of it runs inside the head unit's main program and crashed it repeatedly during development before it was redesigned. It is **not** a safety system. Never rely on it alone for range planning.

![Live screen](docs/images/app_live.png)

## What is in here

| Folder | What it is |
|---|---|
| `app/` | **EV Range app**: the screen (range, kWh/100 km, trip / day / all-time averages, 7-day chart, a live power bar scaled to this car's measured limits, speed, rated-range comparison). A normal Android app in its own process. |
| `hook/` | **Data feed** (`com.ts.evhook`): reads battery voltage, current, SOC, temperatures and motor rpm from the car through the head unit's own software and writes a small CSV. Runs inside `com.ts.MainUI`. Has built-in safety brakes. |
| `probe/` | **Read-only probe** (`com.evprobe.app`, no permissions): shows the unit's own car-info values live (gear, brake, hand brake, ACC, tyre pressure fields, a distance counter, ...) and highlights what changes, to find out what this car actually sends. Calls only the read method of the unit's car-info interface. Never logs the VIN. |
| `tools/` | Analysis scripts, including `calibrate_speed.py` (rpm vs GPS speed) and `vehicle_off_monitor.ps1`. |
| `samples/` | A sanitized sample drive for demo mode and tests. |
| `docs/` | The development log, the TPMS findings, and the install / safety / build guides. |
| `scripts/` | `test.ps1`: all tests that need no device. |

## How it works

```
car CAN bus -> head-unit MCU -> head-unit software (MainUI + libcan59)
                                      |
                  hook (main thread only, safety brakes)
                                      |  writes ev_log.csv (every 2 s while driving, every 30 s idle)
                                      v
                  EV Range app (own process) + GPS -> range estimate
```

- Energy comes from battery voltage x current, so it includes regen and hills.
- Range = remaining energy (capacity x SOC) / consumption over the **last 10 / 25 / 50 km** (selectable). Until there is enough distance it falls back to the trip average, the all-time average, then a default.
- Distance comes from GPS speed when there is a fix, otherwise from motor rpm with a calibrated ratio (see `tools/calibrate_speed.py`).
- With the vehicle off the unit suspends (tested): nothing is logged or sent.

## Compatibility: exactly one setup

This is built and tested **only** for:

| Item | Value |
|---|---|
| Head unit | Lingbox "Octapro 8529", board `825X_Pro` (alps / MediaTek-style), Android 9 (SDK 28) |
| Head-unit software | `com.ts.MainUI` versionName 1.1, APK SHA-1 `98d77350d621b09831bde02cdbf02409589cdf30` |
| Car CAN profile | type 356 (Jimai FL), subtype 3 (`Can_Com_Para.bin` = `64 01 03 00 00 00 00 00`) |
| Battery | 19 kWh (edit in app Settings) |

On anything else the hook may not install, may do nothing, or may behave unpredictably. The preflight checks in [docs/INSTALL.md](docs/INSTALL.md) exist to catch that; do them.

## Quick start

```powershell
.\scripts\test.ps1          # tests, no device needed
.\app\build.ps1             # build\app\evrange.apk   (debug-signed; use your own key to distribute)
.\hook\build.ps1            # build\hook\evhook.apk   (AOSP platform key: see docs/BUILD.md)
.\probe\build.ps1           # build\probe\evprobe.apk (read-only, no permissions)
```

Then follow [docs/INSTALL.md](docs/INSTALL.md) step by step (it verifies checksums at each step and explains how to undo everything).

## Calibrating the distance

The unit gives no vehicle speed in km/h, so distance needs a calibrated rpm-to-speed ratio. The measured ratio on the test car is **31.0 km/h at 3000 rpm** (0.0001722 km per motor revolution, checked against GPS to 0.6 % over a 31-minute drive); it is the app's default. To check it on your own unit, drive with the app open, pull `gps_rpm_log.csv` from `/storage/emulated/0/Android/data/com.evrange.app/files/`, run `py -I tools\calibrate_speed.py gps_rpm_log.csv`, and enter the printed "speed at 3000 rpm" in the app's Settings. Details in the development log, section 15.

## What is deliberately not in this repository

The manufacturer's own files (`MainUI.apk`, its native libraries, decompiled code), signing keys and keystores, and raw drive logs. The development log refers to them by name and checksum only. Do not add them. (Check the law where you live before reverse-engineering or redistributing anything of the manufacturer's.)

## Documentation

- [docs/INSTALL.md](docs/INSTALL.md): preflight checks, install, verify, uninstall, kill switch
- [docs/SAFETY.md](docs/SAFETY.md): what can go wrong and how it is contained
- [docs/BUILD.md](docs/BUILD.md): building and testing
- [docs/HEADUNIT_EV_HOOK.md](docs/HEADUNIT_EV_HOOK.md): full development log, findings and decoding tables
- [docs/TPMS_FINDINGS.md](docs/TPMS_FINDINGS.md): how the unit's two tyre-pressure systems work

## License

Not chosen yet. Until a license file is added, all rights are reserved by the author.
