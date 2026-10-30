# Samples the head unit every -IntervalSec seconds (ping, adb, uptime, Wi-Fi bytes, wakefulness, wake locks,
# last log line) so you can see what it does when the vehicle is switched off.
# Example: .\vehicle_off_monitor.ps1 -Ip <unit-ip> -DurationMin 60
param(
    [int]$DurationMin = 60,
    [int]$IntervalSec = 30,
    [string]$Out = ".\vehicle_off_monitor.csv",
    [Parameter(Mandatory = $true)][string]$Ip   # the unit's Wi-Fi address
)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = "${Ip}:5555"

function Run-Adb([string]$args2, [int]$timeout = 25) {
    $j = Start-Job { param($a, $d, $c) cmd /c "`"$a`" -s $d $c 2>&1" } -ArgumentList $adb, $dev, $args2
    if (Wait-Job $j -Timeout $timeout) { $r = Receive-Job $j; Remove-Job $j -Force; return ($r | Out-String) }
    Stop-Job $j; Remove-Job $j -Force; return $null
}

"time,ping,adb,uptime_s,wlan_rx_bytes,wlan_tx_bytes,wakefulness,susp_blocker,partial_wakelocks,last_log_time,last_amp,last_rpm" | Set-Content $Out -Encoding ascii
$end = (Get-Date).AddMinutes($DurationMin)
while ((Get-Date) -lt $end) {
    $t = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $ping = if (Test-Connection $Ip -Count 1 -Quiet -ErrorAction SilentlyContinue) { 1 } else { 0 }
    $adbOk = 0; $up = ""; $rx = ""; $tx = ""; $wake = ""; $susp = ""; $pwl = ""; $ll = ""; $amp = ""; $rpm = ""
    if ($ping -eq 1) {
        $d = cmd /c "`"$adb`" devices" 2>&1 | Out-String
        if ($d -notmatch [regex]::Escape($dev) + "\s+device") {
            cmd /c "`"$adb`" disconnect $dev >nul 2>&1"
            cmd /c "`"$adb`" connect $dev" 2>&1 | Out-Null
            Start-Sleep 1
        }
        $o = Run-Adb "shell `"cat /proc/uptime; grep wlan0 /proc/net/dev; dumpsys power | grep -E 'mWakefulness=|mHoldingWakeLockSuspendBlocker'; dumpsys power | grep -c PARTIAL_WAKE_LOCK; tail -n 1 /storage/emulated/0/TsCarInfo/ev_log.csv; echo ENDMARK`""
        if ($o -and $o -match "ENDMARK") {
            $adbOk = 1
            $lines = $o -split "`r?`n"
            if ($lines[0] -match "^(\d+)") { $up = $Matches[1] }
            foreach ($l in $lines) {
                if ($l -match "wlan0:\s*(\d+)\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+(\d+)") { $rx = $Matches[1]; $tx = $Matches[2] }
                elseif ($l -match "mWakefulness=(\w+)") { $wake = $Matches[1] }
                elseif ($l -match "mHoldingWakeLockSuspendBlocker=(\w+)") { $susp = $Matches[1] }
                elseif ($l -match "^\d+$" -and $l.Trim() -ne $up) { $pwl = $l.Trim() }
                elseif ($l -match "^(\d{13}),(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d),(-?\d+),(-?\d+),(-?\d+),.*?,.*?,.*?,.*?,(-?\d+)") { $ll = $Matches[2]; $amp = $Matches[4]; $rpm = $Matches[6] }
            }
        }
    }
    "$t,$ping,$adbOk,$up,$rx,$tx,$wake,$susp,$pwl,$ll,$amp,$rpm" | Add-Content $Out -Encoding ascii
    Start-Sleep $IntervalSec
}
"done" | Add-Content "$Out.done" -Encoding ascii
