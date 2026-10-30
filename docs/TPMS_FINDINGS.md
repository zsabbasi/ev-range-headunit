> **About this file.** Paths such as `<work>\decompiled\sources\` refer to the original working folder,
> which holds the manufacturer's decompiled code and is **not** part of this repository.

# TPMS findings: Lingbox head unit (Octapro 8529, board `825X_Pro`), car CAN profile Jimai FL

Written 2026-10-31. Source of everything below: the decompiled `MainUI.apk` (`<work>\decompiled\sources\`, jadx output of `<work>\Lingbox\MainUI.apk`, SHA-1 `98d77350d621b09831bde02cdbf02409589cdf30`) plus observations from the unit. **Nothing about TPMS was called, changed or written on the unit.** Where something is inferred and not verified, it says so.

## 1. Summary

- The unit contains **two unrelated TPMS systems**:
  - **A. The car's own TPMS, over CAN.** It is what the car's tyre-pressure warning uses. In this car it is believed to be an ABS-based (indirect) system. The unit shows it and can send a "reset" command.
  - **B. The unit's own aftermarket TPMS receiver, over a serial (UART) port.** It expects an external receiver and up to 8 sensors. Its menus (pairing, thresholds, units) are probably the options visible in the unit's menu. With no receiver and no sensors it has nothing to show.
- The reset button on the car TPMS page sends `CanJni.CanCmdCarSet(8, 0, 0)` to the car. The unit only displays the reply.
- The car TPMS page has number fields (pressure bar, temperature) switched on because this unit's CAN subtype is 3. Whether the car actually sends numbers is **not verified**.
- To find out, look at the unit's TPMS page with the car on (screenshot is read-only). Do not add TPMS reads to the data feed without care (section 8).

## 2. The two systems side by side

| | A. Car TPMS (CAN) | B. Aftermarket receiver (UART) |
|---|---|---|
| Link | CAN bus via the MCU and `libcan59.so` | Serial port at 19200 baud (`Iop.UartOpen(19200)`, `libts70xhw`) |
| Sensors | None needed if the car is ABS-based | Up to 8 wireless tyre sensors, paired in the unit |
| Code | `com.ts.can.jimai.fl.CanJiMaiFlTpmsInfoView`, `CanJni.JiMaiFlGetAbsInfo`, `JiMaiFlGetTpmsInfo` | `com.ts.MainUI.StTpms`, `com.ts.main.tpms.TPMSMainActivity`, `TPMSSetMainActivity`, `TpmsStudyActivity` |
| Settings stored | None on the unit (the car holds the baseline) | `/storage/emulated/0/TsStorage/TPMS_V5.bin` (10 integers) |
| Reset / learn | "Tap to reset" sends `CanCmdCarSet(8,0,0)` | "Study" pairs a sensor to a wheel position |
| Needs hardware you may not have | No | Yes: receiver + sensors |

## 3. System A: the car's TPMS over CAN

### 3.1 Data the unit asks for and reads
`CanJiMaiFlTpmsInfoView.QueryData()` sends two requests to the car: `CanCmdQueryData(53, 0, 0)`, waits 5 ms, then `CanCmdQueryData(41, 0, 0)`. `ResetData()` then reads two structures from the native library:

`JiMaiFl_AbsInfo` (CAN query 53, the ABS / indirect-TPMS status):

| Field | Meaning (from how the view uses it) |
|---|---|
| `RstSta` | Reset state: 0 = ready ("tap to reset"), 1 = resetting, 2 = reset failed (button text turns red), 3 = reset succeeded |
| `TpmsFail` | Non-zero = TPMS fault: all four tyre icons go to the warning state and the status text shows "TPMS fail" in red |
| `TpmsWarn` | Non-zero = TPMS warning: all four tyre icons go to the warning state and the text shows the warning in red |

`JiMaiFl_TpmsInfo` (CAN query 41, per-wheel values):

| Field | Meaning |
|---|---|
| `Pres[4]` | Pressure per wheel. Shown as `raw x 0.01`, unit label "BAR" |
| `Temp[4]` | Temperature per wheel. Shown as `raw - 40` degrees C |
| `Warn[4]` | Per-wheel warning flag; any non-zero lights that tyre's warning icon |
| Wheel order | Index 0..3, drawn as two columns by two rows (index % 2 = left/right, index / 2 = front/rear); the exact wheel mapping is not decoded |

### 3.2 When the numbers are drawn (the "subtype 3" rule)
In `InitUI()`: `boolean z = CanJni.GetSubType() == 3;` then the pressure text, temperature text and the "BAR" label are shown only if `z` is true. Otherwise only the tyre icons and the status text are drawn.
- This unit's `Can_Com_Para.bin` is `64 01 03 00 00 00 00 00` (CAN type 0x0164 = 356 = Jimai FL, **subtype 3**), so the number fields are enabled.
- Enabled does not mean the car sends valid numbers. A car with only an ABS-based system may leave `Pres`/`Temp` empty. They would then read 0.0 bar and -40 deg C, or the fields would stay "--" until the first update.
- Other subtypes seen in the code: subtype 1 selects a different screen for the CAN type (`CanJimaiFlEc02CarInfoActivity`), and subtype 2 hides the TPMS entry in the car-info list.

### 3.3 Warning logic as coded (`ResetData`)
- A tyre icon is "warning" if `Warn[i] > 0` or `TpmsFail > 0` or `TpmsWarn > 0`.
- Status text: `TpmsFail > 0` gives "TPMS fail" (red); else any `Warn[i] > 0` or `TpmsWarn > 0` gives the pressure warning text (red); else the normal text (white).
- The pressure and temperature numbers are only refreshed when the structure has been updated at least once (`UpdateOnce`) and, in the polling mode, when `Update` is set.

### 3.4 The reset
`onTouch` of the reset button (tag 1) calls `CanJni.CanCmdCarSet(8, 0, 0)`: a one-shot command to the car (command 8). The car's ABS/ESP module does the real work; the unit shows progress through `RstSta`. This matches the user's description: "reset tyre deflation via ABS sensor".

## 4. System B: the unit's own aftermarket TPMS (`StTpms`)

### 4.1 Hardware path
`Iop.UartOpen(19200)` opens a serial port through the unit's hardware library; `Iop.UartRead` / `Iop.UartSend` move bytes. There is also an `ExUart*` set (a second serial port) in the same class. Whether the receiver connects through a dedicated wire, a USB-serial adapter or the harness is **not known**; ask Lingbox or check the harness for a TPMS connector.

### 4.2 Frame format (the "Tw" protocol, `nTpmsType = 1`)
Frames are 10 bytes: `F8 55 07 <cmd> <d4> <d5> <d6> <d7> FF <chk>` where `chk` is the XOR of bytes 0 to 8 (`GetCheckSumTw`).

| Cmd | Direction | Meaning (from the code) |
|---|---|---|
| `A6` | unit to receiver | Query / select sensor position `i` (1 to 4); data bytes `FF FF FF FF` |
| `A8` | unit to receiver | Set warning parameters (`SetWarnParat`): `d4`=1 then 2; `d5` = temperature limit + 50; `d6` = high-pressure limit; `d7` = low-pressure limit. Pressure limits are converted from the saved value with `/ 0.14504 / 3` (psi to kPa, then 3 kPa per count) |
| `B1` | receiver to unit | Sensor learning report: position in `d4` (1 to 4), 3-byte sensor ID in `d5..d7`; the unit answers with `C1` echoing the same bytes |
| `C1` | unit to receiver | Acknowledge of `B1` / `B3` |
| `B3` | receiver to unit | State change report (also acknowledged with `C1`) |
| `B6` | receiver to unit | Tyre data: `d4` = position (1 to 4), `d5` = temperature raw, `d6` = pressure raw, `d7` = flags |
| `B7` | receiver to unit | Sensor warning for position `d4` (sets the "signal" warning) |
| `D1` | receiver to unit | Sensor ID report for position `d4` (1 to 4) |

`B6` decoding as coded: temperature = `d5 - 50` deg C; pressure = `d6 x 3` kPa; low-pressure flag = bit 7 of `d7`; battery = `(d7 & 7) + 25` (low-battery warning when the result is 27 or less; units not stated in the code); if both `d5` and `d6` are 255 the sensor is treated as having no signal; bits 4 to 6 of `d7` can flag a sensor position (1 to 4) with a warning.

### 4.3 The other protocol (`nTpmsType = 0`, commands 6, 7, 8)
`DealCmd6` (sensor state per position: ids 0, 1, 16, 17 mean wheels 0 to 3), `DealCmd7` (sensor ID per position) and `DealCmd8` (data): pressure = `raw x 3.44` kPa (3.44 kPa is 0.5 psi), temperature = `raw - 50` deg C, flags in `Cmd[6]`: bit 3 = pressure warning, bit 4 = voltage warning, bit 5 = signal warning. Which receivers use which protocol is not documented in the code; `nTpmsType` is set by `SetTpmsType()` from the unit's configuration.

### 4.4 Settings file `/storage/emulated/0/TsStorage/TPMS_V5.bin`
Ten integers (index: field): 0 `nWriteFlag` (1 = valid), 1 `nOnOffFlag`, 2 `nTempDW` (temperature unit), 3 `nTempHigh`, 4 `nPskDW` (pressure unit: 0 or 1; mapping to kPa / bar / psi not decoded), 5 `nPskHigh`, 6 `nPskLow`, 7 `nWarnTime`, 8 `nPsiHigh`, 9 `nPsiLow`. Created with defaults on first use.
Defaults: type 0: temperature limit 80, high 300, low 160, unit 0. Type 1: temperature limit 80, high 50, low 26, unit 1. Allowed ranges: type 0: pressure 200 to 800, temperature up to 200; type 1 (Tw): low 20 to 35, high 40 to 60, temperature 60 to 100.
The user saw `TPMS_V5.bin` in `TsStorage`, i.e. the unit has initialised this settings file.

### 4.5 Menus
`TPMSMainActivity` (main view), `TPMSSetMainActivity` (settings: units bar / kPa / psi, temperature unit, limits) and `TpmsStudyActivity` (pairing sensors to wheel positions: front left 0, front right 1, rear left 2, rear right 3). String resources seen: `tpms_general_bar`, `tpms_general_kpa`, `tpms_general_psi`.

## 5. How an ABS-based (indirect) TPMS works in general
(General background, not from the unit's code.)
- It has no pressure sensors. It uses the ABS wheel-speed sensors. An under-inflated tyre has a slightly smaller rolling radius, so it turns faster than the others at the same road speed; the system watches for that difference (some systems also watch vibration frequency).
- It must be **reset (calibrated)** after inflating or changing tyres, or after rotating them. It then needs a period of driving to learn. The reset button on the unit starts that.
- Known limits: it reports a loss relative to the baseline you set (so a wrong baseline means a wrong warning); it can miss slow equal loss on all four tyres; it is less reliable at low speed, on rough surfaces and in strong cornering; it gives no pressure number.
- A practical routine: set all four tyres to the specified pressure when cold, then use the reset, then drive steadily for a while.

## 6. An idea for later: a rough indirect check from our own data
Our rpm and GPS log (`gps_rpm_log.csv`, written by the EV Range app) gives the motor revolutions per km. If tyre pressure changes the rolling radius by a few percent, that ratio would shift slightly over time. This could serve as a very rough indication of tyre pressure drift; it is not a substitute for sensors and has not been tested.

## 7. Open questions
1. What does the unit's TPMS page show with the car on: real pressures and temperatures, "--", 0.0 / -40, or only warning icons? (A screenshot answers this.)
2. Does the car send `Pres`/`Temp`/`Warn` at all, or only the ABS flags?
3. Is a TPMS connector wired on the unit's harness (UART)? Is a USB variant supported?
4. Which receiver protocol (`nTpmsType` 0 or 1) matches the unit, and which kits are known to work with it?
5. Does `RstSta` change when the reset button is pressed, and how long does the car take?

## 8. Cautions
- **Do not call the car library from other threads.** An earlier logger that called `JiMaiFlGetMotorInfo` from its own thread crashed `MainUI` repeatedly (see `HEADUNIT_EV_HOOK.md`, sections 10 and 12). Reading `JiMaiFlGetAbsInfo` / `JiMaiFlGetTpmsInfo` would be the same kind of call. If it is ever added to the feed it must be on the main thread, behind the same safety brakes, tested parked.
- Do not edit `TPMS_V5.bin` by hand; the unit rewrites it and a wrong value changes warning thresholds.
- Do not send frames to the UART without a known receiver; the protocol above is read from code and has not been confirmed against a device.
- Never rely on this unit's TPMS alone for safety. Check pressures with a gauge.

## 9. Where to look in the code
All paths are under `<work>\decompiled\sources\`.

| What | File (approximate lines) |
|---|---|
| Car TPMS page, reset button, display rules | `com\ts\can\jimai\fl\CanJiMaiFlTpmsInfoView.java` (onTouch 50-67, subtype rule 85, ResetData 136-187, QueryData 190-194) |
| Data structures | `com\lgb\canmodule\CanDataInfo.java`: `JiMaiFl_AbsInfo`, `JiMaiFl_TpmsInfo` |
| Native entry points | `com\lgb\canmodule\CanJni.java`: `JiMaiFlGetAbsInfo`, `JiMaiFlGetTpmsInfo`, `CanCmdQueryData`, `CanCmdCarSet`, `BZTpmsSet` |
| Aftermarket receiver logic | `com\ts\MainUI\StTpms.java` (constants 12-40, TPMS_SAVE 128-142, defaults 179-201, UartOpen 237-244, DealCmd6/7/8 259-364, SetWarnParat 407-433, DealCmdB1 435-473, DealCmdB6 492-544, checksum 546-552, SendCmdA6 554-568) |
| Aftermarket screens | `com\ts\main\tpms\TPMSMainActivity.java`, `TPMSSetMainActivity.java`, `TpmsStudyActivity.java` |
| Many other cars' TPMS screens (for comparison) | `com\ts\can\<brand>\...Tpms*.java` |
| Native libraries | `<work>\native_work\libcan59.so` (CAN decoding), `libts70xicfg.so` and `libts59xhw.so` (hardware layer) |
| Unit settings in use | `/storage/emulated/0/TsCarInfo/Can_Com_Para.bin` (8 bytes), `/storage/emulated/0/TsStorage/TPMS_V5.bin` |

## 10. Verified versus not verified

| Statement | Status |
|---|---|
| Two separate TPMS systems exist in `MainUI` | Verified in the code |
| Reset sends `CanCmdCarSet(8,0,0)` | Verified in the code |
| This unit is configured with CAN subtype 3 | Verified (`Can_Com_Para.bin` read from the unit) |
| Number fields are drawn when subtype is 3 | Verified in the code |
| The car is ABS-based without sensors | From the user; not checked |
| The car sends (or does not send) pressure and temperature | **Not verified** |
| The aftermarket receiver protocol works with any specific kit | **Not verified** |
| What the TPMS page actually shows | **Not yet looked at** |
