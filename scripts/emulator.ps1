#Requires -Version 5.1
<#
One command to test Nova Drive on this PC's emulator (docs/EMULATOR_TESTING.md).

Boots the AVD detached (host GPU, host audio, webcam), waits for boot, applies adb root, host-mic
forwarding, the PC proxy and a Hengqin GPS fix, optionally installs the debug APK, opens the
app, puts the emulator window on screen and starts the host audio bridge (PC microphone and
speakers) detached, logging to the build directory.

  .\scripts\emulator.ps1 -InstallApk        # the desktop shortcut (nova_api34, arm64)
  .\scripts\emulator.ps1 -NoBridge          # emulator + app only
  .\scripts\emulator.ps1 -AvdName nova_api30 -Abi armeabi-v7a -InstallApk

Measured 2026-09-28 (docs/EMULATOR_TESTING.md): the real Amap map and full drives need the API 34
image (arm64) or the API 30 image with an armeabi-v7a install; API 30 + arm64 gets SIGILL.
#>
[CmdletBinding()]
param(
    [switch]$SetupSdk,
    [switch]$InstallApk,
    [string]$ApkPath,
    [string]$AvdName = "nova_api34",
    [string]$ImagePackage = "system-images;android-34;google_apis;x86_64",
    # New AVDs go to D: (C: is nearly full on this PC).
    [string]$AvdDir = "D:\android-avd",
    # The API 34 image translates arm64 only; on nova_api30 pass armeabi-v7a (see above).
    [ValidateSet("armeabi-v7a", "arm64-v8a")]
    [string]$Abi = "arm64-v8a",
    # The PC's local proxy as seen from the emulator; Baidu TLS needs it here. Empty skips it.
    [string]$HostProxy = "10.0.2.2:7897",
    [string]$GeoFix = "113.5767 22.2711",
    [switch]$NoBridge,
    [string]$Mic = "Microphone Array (适用于数字麦克风的英特尔® 智音技术)",
    [int]$BootTimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$buildDir = "C:\Users\Administrator\tools\nova-drive-build"

function Find-SdkRoot {
    $candidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, "C:\Users\Administrator\Android\Sdk")
    foreach ($candidate in $candidates) {
        if ($candidate -and ((Test-Path (Join-Path $candidate "platform-tools\adb.exe")) -or
                (Test-Path (Join-Path $candidate "cmdline-tools\latest\bin\sdkmanager.bat")))) {
            return (Resolve-Path $candidate).Path
        }
    }
    throw "Android SDK not found. Set ANDROID_SDK_ROOT or install it at C:\Users\Administrator\Android\Sdk."
}

function Invoke-Checked {
    param([string]$FilePath, [string[]]$Arguments)
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')" }
}

function Get-SdkManagerProxyArguments {
    # Read only the endpoint fields from standard proxy environment variables. Never echo the URI:
    # it may contain credentials. sdkmanager's CLI accepts host/port separately.
    foreach ($name in @("HTTPS_PROXY", "HTTP_PROXY")) {
        $raw = [Environment]::GetEnvironmentVariable($name)
        if (-not $raw) { continue }
        if ($raw -notmatch '^[a-zA-Z][a-zA-Z0-9+.-]*://') { $raw = "http://$raw" }
        $uri = $null
        if (-not [Uri]::TryCreate($raw, [UriKind]::Absolute, [ref]$uri)) {
            throw "$name is set but is not a valid proxy URL."
        }
        if ($uri.Scheme -notin @("http", "https")) { continue }
        $port = if ($uri.IsDefaultPort) { 80 } else { $uri.Port }
        return @("--proxy=http", "--proxy_host=$($uri.Host)", "--proxy_port=$port")
    }
    return @()
}

function Find-Serial {
    foreach ($device in (& $adb devices)) {
        if ($device -match '^(emulator-\d+)\s+device$') {
            $candidate = $Matches[1]
            $name = (& $adb -s $candidate emu avd name 2>$null | Select-Object -First 1)
            if ($name -and $name.Trim() -eq $AvdName) { return $candidate }
        }
    }
    return $null
}

$sdk = Find-SdkRoot
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME = $sdk
if (-not $env:JAVA_HOME) {
    foreach ($javaFallback in @("C:\Users\Administrator\tools\jdk-17.0.20.1+1", "C:\Users\Administrator\tools\jdk-17")) {
        if (Test-Path (Join-Path $javaFallback "bin\java.exe")) {
            $env:JAVA_HOME = $javaFallback
            $env:Path = "$javaFallback\bin;$env:Path"
            break
        }
    }
}
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$avdManager = Join-Path $sdk "cmdline-tools\latest\bin\avdmanager.bat"
$sdkManager = Join-Path $sdk "cmdline-tools\latest\bin\sdkmanager.bat"

if ($SetupSdk) {
    if (-not (Test-Path $sdkManager)) { throw "sdkmanager was not found at $sdkManager" }
    $setupArguments = @("--sdk_root=$sdk") + (Get-SdkManagerProxyArguments) + @("platform-tools", "emulator", $ImagePackage)
    Invoke-Checked $sdkManager $setupArguments
}

foreach ($required in @($adb, $emulator)) {
    if (-not (Test-Path $required)) { throw "Required Android SDK tool missing: $required. Run with -SetupSdk after installing SDK command-line tools." }
}

$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE ".android\avd" }
$avdIni = Join-Path $avdHome "$AvdName.ini"
if (-not (Test-Path $avdIni)) {
    # Same hardware as nova_api30 / nova_api34, the AVDs the measurements in the docs used.
    # (On this PC the API 34 image lives on D:\android-sdk-extra behind a junction in the SDK.)
    if (-not (Test-Path (Join-Path $sdk (($ImagePackage -replace ';', '\') + "\package.xml")))) {
        throw "Missing $ImagePackage. Run scripts\emulator.ps1 -SetupSdk first."
    }
    $avdPath = Join-Path $AvdDir "$AvdName.avd"
    Write-Host "Creating AVD $AvdName in $avdPath ($ImagePackage)..."
    "no" | & $avdManager "create" "avd" "-n" $AvdName "-k" $ImagePackage "--device" "pixel_5" "-p" $avdPath
    if ($LASTEXITCODE -ne 0) { throw "AVD creation failed ($LASTEXITCODE) for $AvdName." }
    $avdConfig = Join-Path $avdPath "config.ini"
    $config = @(Get-Content -LiteralPath $avdConfig)
    foreach ($setting in @("hw.gpu.enabled=yes", "hw.gpu.mode=host", "hw.ramSize=4096", "hw.keyboard=yes",
            "hw.audioInput=yes", "hw.audioOutput=yes", "disk.dataPartition.size=6G", "hw.cpu.ncore=4")) {
        $key = $setting.Split('=')[0]
        $pattern = "^$([regex]::Escape($key))\s*="
        if ($config -match $pattern) {
            $config = @($config | ForEach-Object { if ($_ -match $pattern) { $setting } else { $_ } })
        } else {
            $config += $setting
        }
    }
    Set-Content -LiteralPath $avdConfig -Value $config -Encoding ASCII
}

& $adb start-server | Out-Null
$serial = Find-Serial
if (-not $serial) {
    Write-Host "Starting emulator $AvdName..."
    # -no-snapshot-save: always a clean shutdown state. GLESDynamicVersion + host GPU: Amap's GL
    # map needs a GLES 3 context (createContext failed: 12288 without them).
    $emuArgs = @("-avd", $AvdName, "-no-snapshot-save", "-allow-host-audio", "-gpu", "host",
        "-feature", "GLESDynamicVersion", "-camera-back", "webcam0")
    $emuProcess = Start-Process -FilePath $emulator -ArgumentList $emuArgs -PassThru
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    while ((Get-Date) -lt $deadline -and -not $serial) {
        Start-Sleep -Seconds 2
        if ($emuProcess.HasExited) {
            throw "Emulator exited before registering with ADB (exit code $($emuProcess.ExitCode)). Check free disk space."
        }
        $serial = Find-Serial
    }
    if (-not $serial) { throw "Emulator did not register with ADB within $BootTimeoutSeconds seconds. Process ID: $($emuProcess.Id)" }
}

Write-Host "Waiting for Android boot on $serial (timeout ${BootTimeoutSeconds}s)..."
$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
do {
    $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null | Select-Object -First 1)
    if ($boot -and $boot.Trim() -eq "1") { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if (-not $boot -or $boot.Trim() -ne "1") { throw "Android did not finish booting on $serial within $BootTimeoutSeconds seconds." }

& $adb -s $serial root | Out-Null
Start-Sleep -Seconds 3
& $adb -s $serial wait-for-device
Invoke-Checked $adb @("-s", $serial, "emu", "avd", "hostmicon")
if ($HostProxy) { Invoke-Checked $adb @("-s", $serial, "shell", "settings", "put", "global", "http_proxy", $HostProxy) }
if ($GeoFix) { Invoke-Checked $adb (@("-s", $serial, "emu", "geo", "fix") + $GeoFix.Split(' ')) }
Write-Host "Root, host microphone, proxy and GPS fix applied on $serial."

if ($InstallApk) {
    $explicitApkPath = -not [string]::IsNullOrWhiteSpace($ApkPath)
    if (-not $explicitApkPath) { $ApkPath = Join-Path $buildDir "app\outputs\apk\debug\app-debug.apk" }
    if (-not (Test-Path -LiteralPath $ApkPath)) {
        throw "APK not found at '$ApkPath'. Build with .\gradlew.bat :app:assembleDebug first."
    }
    # -r keeps the app's data (the keys entered in 开发者设置). An install under the other ABI
    # needs an uninstall first, which clears them.
    $installed = (& $adb -s $serial shell dumpsys package com.novadrive.app 2>$null | Select-String "primaryCpuAbi=(\S+)")
    if ($installed -and $installed.Matches[0].Groups[1].Value -ne $Abi) {
        Write-Host "Installed ABI is $($installed.Matches[0].Groups[1].Value); reinstalling as $Abi (app data is cleared)."
        & $adb -s $serial uninstall com.novadrive.app | Out-Null
    }
    Invoke-Checked $adb @("-s", $serial, "install", "-r", "-g", "--abi", $Abi, (Resolve-Path -LiteralPath $ApkPath).Path)
}

Invoke-Checked $adb @("-s", $serial, "shell", "am", "start", "-W", "-n", "com.novadrive.app/.MainActivity")
$appPid = $null
$appDeadline = (Get-Date).AddSeconds(15)
do {
    $appPid = (& $adb -s $serial shell pidof com.novadrive.app 2>$null | Select-Object -First 1)
    if ($appPid) { break }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $appDeadline)
if (-not $appPid) { throw "com.novadrive.app did not start on $serial within 15 seconds." }
$abiLine = (& $adb -s $serial shell dumpsys package com.novadrive.app | Select-String "primaryCpuAbi=\S+" | Select-Object -First 1)
Write-Host "com.novadrive.app running on $serial ($($abiLine.Matches[0].Value))."

# The emulator window has opened off screen (y=-630) before; put it at the top left.
Add-Type -Namespace NovaDrive -Name Win -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
'@
$port = $serial -replace '^emulator-', ''
foreach ($proc in (Get-Process -Name "qemu-system-*" -ErrorAction SilentlyContinue)) {
    if ($proc.MainWindowHandle -ne [IntPtr]::Zero -and $proc.MainWindowTitle -like "*${AvdName}:$port*") {
        # SWP_NOSIZE | SWP_NOZORDER
        [NovaDrive.Win]::SetWindowPos($proc.MainWindowHandle, [IntPtr]::Zero, 100, 0, 0, 0, 0x0001 -bor 0x0004) | Out-Null
    }
}

if (-not $NoBridge) {
    $bridgeLog = Join-Path $buildDir "host_audio_bridge.log"
    $running = Get-CimInstance Win32_Process -Filter "Name like 'python%'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*host_audio_bridge.py*" }
    if ($running) {
        Write-Host "Host audio bridge already running (PID $($running[0].ProcessId)); log $bridgeLog"
    } else {
        $env:ANDROID_SERIAL = $serial
        $env:PYTHONIOENCODING = "utf-8"
        $bridgeArgs = "-u `"$repo\tools\speech-harness\host_audio_bridge.py`" --mic `"$Mic`""
        $bridge = Start-Process -FilePath "python" -ArgumentList $bridgeArgs -WorkingDirectory $repo -WindowStyle Hidden `
            -RedirectStandardOutput $bridgeLog -RedirectStandardError "$bridgeLog.err" -PassThru
        Write-Host "Host audio bridge started (PID $($bridge.Id)); speak to 小诺 through the PC microphone. Log: $bridgeLog"
    }
}

Write-Host "Emulator ready: $AvdName ($serial). Use adb -s $serial ... for all device commands."
