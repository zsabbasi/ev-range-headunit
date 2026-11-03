<#
.SYNOPSIS  Runs all tests that need no device: the range engine, the feed safety brakes, and the
           calibration tool self-test. Needs only a JDK and Python.
#>
param([string]$Jdk = "")
$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$Build = "$Root\build\test"

function Find-Tool([string]$name) {
    if ($Jdk) { $p = Join-Path $Jdk "$name.exe"; if (Test-Path $p) { return $p } }
    if ($env:JAVA_HOME) { $p = Join-Path $env:JAVA_HOME "bin\$name.exe"; if (Test-Path $p) { return $p } }
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    throw "$name not found: install a JDK or pass -Jdk"
}
$javac = Find-Tool "javac"
$java = Find-Tool "java"
$py = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python" }
$failed = 0
function Check($name, $ok) { if ($ok) { "PASS  $name" } else { "FAIL  $name"; $script:failed++ } }

New-Item -ItemType Directory -Force "$Build\engine", "$Build\feed", "$Build\feed-data" | Out-Null

# 1. range engine on the sample drive
& $javac --release 8 -Xlint:-options -d "$Build\engine" "$Root\app\com\evrange\app\RangeEngine.java" "$Root\app\test\EngineTest.java"
if ($LASTEXITCODE -ne 0) { throw "engine tests did not compile" }
$out = & $java -cp "$Build\engine" EngineTest "$Root\samples\ev_demo_sanitized.csv" 0 | Out-String
$m = [regex]::Match($out, "trip: ([\d.]+) km, ([\d.]+) kWh/100")
Check "engine: sample drive distance is about 2.63 km (calibrated 31.0 km/h at 3000 rpm)" ($m.Success -and [math]::Abs([double]$m.Groups[1].Value - 2.63) -lt 0.08)
Check "engine: sample drive consumption is about 15.8 kWh/100 km" ($m.Success -and [math]::Abs([double]$m.Groups[2].Value - 15.8) -lt 0.5)
Check "engine: save/load round trip is identical" ($out -match "round trip identical: true")

# 1b. power bar scale
& $javac --release 8 -Xlint:-options -d "$Build\engine" "$Root\app\com\evrange\app\PowerScale.java" "$Root\app\test\PowerScaleTest.java"
if ($LASTEXITCODE -ne 0) { throw "power scale tests did not compile" }
$out = & $java -cp "$Build\engine" PowerScaleTest | Out-String
Check "power bar: scale and thresholds" ($out -match "ALL POWER SCALE TESTS PASSED")
if ($out -notmatch "ALL POWER SCALE TESTS PASSED") { $out }

# 1c. speed from rpm / GPS
& $javac --release 8 -Xlint:-options -d "$Build\engine" "$Root\app\com\evrange\app\RangeEngine.java" "$Root\app\test\SpeedTest.java"
if ($LASTEXITCODE -ne 0) { throw "speed tests did not compile" }
$out = & $java -cp "$Build\engine" SpeedTest | Out-String
Check "speed: rpm conversion, GPS preference, stopped detection" ($out -match "ALL SPEED TESTS PASSED")
if ($out -notmatch "ALL SPEED TESTS PASSED") { $out }

# 1c2. the car's own range, drive-mode inference and per-mode consumption
& $javac --release 8 -Xlint:-options -d "$Build\engine" "$Root\app\com\evrange\app\CarRange.java" "$Root\app\com\evrange\app\RangeEngine.java" "$Root\app\test\CarRangeTest.java"
if ($LASTEXITCODE -ne 0) { throw "car range tests did not compile" }
$out = & $java -cp "$Build\engine" CarRangeTest | Out-String
Check "car range: D/S inference and per-mode consumption" ($out -match "ALL CAR RANGE TESTS PASSED")
if ($out -notmatch "ALL CAR RANGE TESTS PASSED") { $out }

# 1c3. charging while parked is neither regen nor consumption
& $javac --release 8 -Xlint:-options -d "$Build\engine" "$Root\app\com\evrange\app\RangeEngine.java" "$Root\app\test\ChargingTest.java"
if ($LASTEXITCODE -ne 0) { throw "charging tests did not compile" }
$out = & $java -cp "$Build\engine" ChargingTest | Out-String
Check "charging: detected, kept out of consumption and regen" ($out -match "ALL CHARGING TESTS PASSED")
if ($out -notmatch "ALL CHARGING TESTS PASSED") { $out }

# 1c4. speed-linked volume state and the floating bubble's show/hide rules
& $javac --release 8 -Xlint:-options -d "$Build\engine" "$Root\app\com\evrange\app\SpeedVol.java" "$Root\app\com\evrange\app\BubbleLogic.java" "$Root\app\test\BubbleTest.java"
if ($LASTEXITCODE -ne 0) { throw "bubble tests did not compile" }
$out = & $java -cp "$Build\engine" BubbleTest | Out-String
Check "bubble: speed-volume state, hide while reversing / standing / in the app" ($out -match "ALL BUBBLE TESTS PASSED")
if ($out -notmatch "ALL BUBBLE TESTS PASSED") { $out }
# 1d. car-info probe model (index names, change tracking, VIN never logged)
New-Item -ItemType Directory -Force "$Build\probe" | Out-Null
& $javac --release 8 -Xlint:-options -d "$Build\probe" "$Root\probe\com\evprobe\app\ProbeModel.java" "$Root\probe\test\ProbeModelTest.java"
if ($LASTEXITCODE -ne 0) { throw "probe tests did not compile" }
$out = & $java -cp "$Build\probe" ProbeModelTest | Out-String
Check "probe: names, change tracking, VIN excluded" ($out -match "ALL PROBE TESTS PASSED")
if ($out -notmatch "ALL PROBE TESTS PASSED") { $out }

# 2. data feed safety brakes, against a fake car library
& $javac --release 8 -Xlint:-options -d "$Build\feed" "$Root\hook\com\ts\evhook\EvFeedCore.java" "$Root\hook\test\com\lgb\canmodule\CanDataInfo.java" "$Root\hook\test\com\lgb\canmodule\CanJni.java" "$Root\hook\test\com\yyw\ts70xhw\FtSet.java" "$Root\hook\test\FeedTest.java"
if ($LASTEXITCODE -ne 0) { throw "feed tests did not compile" }
$out = & $java -cp "$Build\feed" FeedTest "$Build\feed-data" | Out-String
Check "feed: all safety-brake tests pass" ($out -match "ALL TESTS PASSED")
if ($out -notmatch "ALL TESTS PASSED") { $out }

# 3. calibration tool self-test
$out = & $py -I "$Root\tools\calibrate_speed.py" --selftest | Out-String
$m = [regex]::Match($out, "speed at 3000 rpm\s+:\s+([\d.]+)")
Check "calibration: recovers 32 km/h at 3000 rpm within 0.5" ($m.Success -and [math]::Abs([double]$m.Groups[1].Value - 32.0) -lt 0.5)
Check "calibration: recovers the 1.5 s offset" ($out -match "BEST OFFSET: \+1\.5")

if ($failed -gt 0) { "$failed test(s) FAILED"; exit 1 }
"ALL TESTS PASSED"
