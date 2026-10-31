<#
.SYNOPSIS
  Builds the read-only CarInfo probe (probe\) into build\probe\evprobe.apk. Same tool chain as app\build.ps1.
  The probe needs no permissions and calls only the unit's read method requestCarBaseInfo.
#>
param(
    [string]$AndroidHome = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }),
    [string]$Jdk = "",
    [string]$Out = "$PSScriptRoot\..\build\probe",
    [string]$Keystore = "$PSScriptRoot\..\build\debug.keystore",
    [string]$StorePass = "android"
)
$ErrorActionPreference = "Stop"
$Src = $PSScriptRoot

function Find-Tool([string]$name) {
    if ($Jdk) { $p = Join-Path $Jdk "$name.exe"; if (Test-Path $p) { return $p } }
    if ($env:JAVA_HOME) { $p = Join-Path $env:JAVA_HOME "bin\$name.exe"; if (Test-Path $p) { return $p } }
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    throw "$name not found: install a JDK or pass -Jdk"
}
$javac = Find-Tool "javac"
$keytool = Find-Tool "keytool"
$bt = (Get-ChildItem "$AndroidHome\build-tools" -Directory | Sort-Object Name | Select-Object -Last 1).FullName
$aj = "$AndroidHome\platforms\android-28\android.jar"
if (-not (Test-Path $aj)) { throw "missing $aj (install platform android-28)" }

New-Item -ItemType Directory -Force "$Out\classes", "$Out\dex" | Out-Null
$srcs = Get-ChildItem "$Src\com" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac --release 8 -Xlint:-options -cp $aj -d "$Out\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$cls = Get-ChildItem "$Out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --min-api 23 --lib $aj --output "$Out\dex" $cls
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
& "$bt\aapt2.exe" link --manifest "$Src\AndroidManifest.xml" -I $aj --min-sdk-version 23 --target-sdk-version 28 -o "$Out\base.apk"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }
$py = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python" }
& $py -I "$Src\..\scripts\add_dex.py" "$Out\base.apk" "$Out\dex\classes.dex" "$Out\evprobe_unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "adding classes.dex failed" }
& "$bt\zipalign.exe" -p -f 4 "$Out\evprobe_unsigned.apk" "$Out\evprobe_aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
if (-not (Test-Path $Keystore)) {
    New-Item -ItemType Directory -Force (Split-Path $Keystore) | Out-Null
    cmd /c "`"$keytool`" -genkeypair -keystore `"$Keystore`" -storepass $StorePass -keypass $StorePass -alias evrange -keyalg RSA -keysize 2048 -validity 10000 -dname CN=EvRangeDebug >nul 2>&1"
    if (-not (Test-Path $Keystore)) { throw "could not create keystore" }
}
$apk = "$Out\evprobe.apk"
if (Test-Path $apk) { Remove-Item $apk }
cmd /c "`"$bt\apksigner.bat`" sign --ks `"$Keystore`" --ks-pass pass:$StorePass --key-pass pass:$StorePass --out `"$apk`" `"$Out\evprobe_aligned.apk`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
"built $apk  ($((Get-Item $apk).Length) bytes, sha1 $((Get-FileHash $apk -Algorithm SHA1).Hash.ToLower()))"
