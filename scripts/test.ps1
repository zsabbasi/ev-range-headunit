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
Check "engine: sample drive distance is about 3.2 km" ($m.Success -and [math]::Abs([double]$m.Groups[1].Value - 3.22) -lt 0.1)
Check "engine: sample drive consumption is about 12.9 kWh/100 km" ($m.Success -and [math]::Abs([double]$m.Groups[2].Value - 12.9) -lt 0.4)
Check "engine: save/load round trip is identical" ($out -match "round trip identical: true")

# 2. data feed safety brakes, against a fake car library
& $javac --release 8 -Xlint:-options -d "$Build\feed" "$Root\hook\com\ts\evhook\EvFeedCore.java" "$Root\hook\test\com\lgb\canmodule\CanDataInfo.java" "$Root\hook\test\com\lgb\canmodule\CanJni.java" "$Root\hook\test\FeedTest.java"
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
