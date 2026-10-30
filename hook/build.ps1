<#
.SYNOPSIS
  Builds the data feed package (hook\) into build\hook\evhook.apk.

  IMPORTANT: this package declares android:sharedUserId="android.uid.system", so it only installs on a
  unit whose firmware is signed with the public AOSP platform test key. The script downloads that
  public key from the Android source tree, checks its fingerprint, and signs with it. It is meant for
  the exact unit described in docs\ and nothing else. Read docs\SAFETY.md first.
#>
param(
    [string]$AndroidHome = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }),
    [string]$Jdk = "",
    [string]$Out = "$PSScriptRoot\..\build\hook",
    [string]$KeyDir = "$PSScriptRoot\..\build\aosp_keys"
)
$ErrorActionPreference = "Stop"
$Src = $PSScriptRoot
$ExpectedCertSha1 = "27196e386b875e76adf700e7ea84e4c6eee33dfa"

function Find-Tool([string]$name) {
    if ($Jdk) { $p = Join-Path $Jdk "$name.exe"; if (Test-Path $p) { return $p } }
    if ($env:JAVA_HOME) { $p = Join-Path $env:JAVA_HOME "bin\$name.exe"; if (Test-Path $p) { return $p } }
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    throw "$name not found: install a JDK or pass -Jdk"
}
$javac = Find-Tool "javac"
$bt = (Get-ChildItem "$AndroidHome\build-tools" -Directory | Sort-Object Name | Select-Object -Last 1).FullName
$aj = "$AndroidHome\platforms\android-28\android.jar"
if (-not (Test-Path $aj)) { throw "missing $aj (install platform android-28)" }

# the public AOSP platform test key (not a secret: it is published in the Android source tree)
New-Item -ItemType Directory -Force $KeyDir | Out-Null
foreach ($n in "platform.pk8", "platform.x509.pem") {
    $dest = "$KeyDir\$n"
    if (-not (Test-Path $dest)) {
        $u = "https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/$n`?format=TEXT"
        $b64 = (Invoke-WebRequest $u -UseBasicParsing).Content
        if ($b64 -is [byte[]]) { $b64 = [Text.Encoding]::ASCII.GetString($b64) }
        [IO.File]::WriteAllBytes($dest, [Convert]::FromBase64String($b64.Trim()))
    }
}
$pem = Get-Content "$KeyDir\platform.x509.pem" -Raw
$der = [Convert]::FromBase64String(($pem -replace '-----[A-Z ]+-----', '' -replace '\s', ''))
$sha1 = [BitConverter]::ToString([Security.Cryptography.SHA1]::Create().ComputeHash($der)).Replace('-', '').ToLower()
if ($sha1 -ne $ExpectedCertSha1) { throw "platform certificate fingerprint $sha1 is not the expected $ExpectedCertSha1" }

New-Item -ItemType Directory -Force "$Out\classes", "$Out\dex" | Out-Null
$srcs = Get-ChildItem "$Src\com" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac --release 8 -Xlint:-options -cp $aj -d "$Out\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$cls = Get-ChildItem "$Out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --min-api 21 --lib $aj --output "$Out\dex" $cls
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
& "$bt\aapt2.exe" link --manifest "$Src\AndroidManifest.xml" -I $aj --min-sdk-version 23 --target-sdk-version 28 -o "$Out\base.apk"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }
$py = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python" }
& $py -I "$Src\..\scripts\add_dex.py" "$Out\base.apk" "$Out\dex\classes.dex" "$Out\evhook_unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "adding classes.dex failed" }
& "$bt\zipalign.exe" -p -f 4 "$Out\evhook_unsigned.apk" "$Out\evhook_aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
$apk = "$Out\evhook.apk"
if (Test-Path $apk) { Remove-Item $apk }
cmd /c "`"$bt\apksigner.bat`" sign --key `"$KeyDir\platform.pk8`" --cert `"$KeyDir\platform.x509.pem`" --out `"$apk`" `"$Out\evhook_aligned.apk`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
"built $apk  ($((Get-Item $apk).Length) bytes, sha1 $((Get-FileHash $apk -Algorithm SHA1).Hash.ToLower()))"
