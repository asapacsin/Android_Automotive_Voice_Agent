#Requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$SetupSdk,
    [switch]$InstallApk,
    [string]$ApkPath,
    [string]$AvdName = "NovaDrive_API_30",
    [int]$BootTimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

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

$sdk = Find-SdkRoot
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME = $sdk
if (-not $env:JAVA_HOME) {
    $javaFallback = "C:\Users\Administrator\tools\jdk-17"
    if (Test-Path (Join-Path $javaFallback "bin\java.exe")) {
        $env:JAVA_HOME = $javaFallback
        $env:Path = "$javaFallback\bin;$env:Path"
    }
}
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$avdManager = Join-Path $sdk "cmdline-tools\latest\bin\avdmanager.bat"
$sdkManager = Join-Path $sdk "cmdline-tools\latest\bin\sdkmanager.bat"
$imagePackage = "system-images;android-30;google_apis;x86_64"

if ($SetupSdk) {
    if (-not (Test-Path $sdkManager)) { throw "sdkmanager was not found at $sdkManager" }
    $setupArguments = @("--sdk_root=$sdk") + (Get-SdkManagerProxyArguments) + @("platform-tools", "emulator", $imagePackage)
    Invoke-Checked $sdkManager $setupArguments
}

foreach ($required in @($adb, $emulator, $avdManager)) {
    if (-not (Test-Path $required)) { throw "Required Android SDK tool missing: $required. Run with -SetupSdk after installing SDK command-line tools." }
}
if (-not (Test-Path (Join-Path $sdk "system-images\android-30\google_apis\x86_64\package.xml"))) {
    throw "Missing $imagePackage. Run scripts\emulator.ps1 -SetupSdk to install the API 30 Google APIs x86_64 image."
}

$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } elseif ($env:USERPROFILE) { Join-Path $env:USERPROFILE ".android\avd" } else { Join-Path $env:HOME ".android\avd" }
$avdIni = Join-Path $avdHome "$AvdName.ini"
if (-not (Test-Path $avdIni)) {
    Write-Host "Creating AVD $AvdName (API 30, Google APIs, x86_64)..."
    "no" | & $avdManager "create" "avd" "-n" $AvdName "-k" $imagePackage "--device" "pixel_2"
    if ($LASTEXITCODE -ne 0) { throw "AVD creation failed ($LASTEXITCODE) for $AvdName." }
}

$avdConfig = Join-Path $avdHome "$AvdName.avd\config.ini"
if (Test-Path $avdConfig) {
    $config = Get-Content -LiteralPath $avdConfig
    foreach ($setting in @(
            "hw.audioInput=yes",
            "hw.keyboard=yes",
            "hw.ramSize=3072",
            "hw.cpu.ncore=4",
            "hw.lcd.width=1280",
            "hw.lcd.height=720",
            "hw.lcd.density=240",
            "hw.initialOrientation=landscape",
            "disk.dataPartition.size=2G"
        )) {
        $key = $setting.Split('=')[0]
        if ($config -match "^$([regex]::Escape($key))=") {
            $config = @($config | ForEach-Object { if ($_ -match "^$([regex]::Escape($key))=") { $setting } else { $_ } })
        } else {
            $config += $setting
        }
    }
    Set-Content -LiteralPath $avdConfig -Value $config -Encoding ASCII
}

& $adb start-server | Out-Null
$serial = $null
foreach ($device in (& $adb devices)) {
    if ($device -match '^(emulator-\d+)\s+device$') {
        $candidate = $Matches[1]
        $name = (& $adb -s $candidate emu avd name 2>$null | Select-Object -First 1)
        if ($name -eq $AvdName) { $serial = $candidate; break }
    }
}

if (-not $serial) {
    Write-Host "Starting visible emulator $AvdName..."
    $emuProcess = Start-Process -FilePath $emulator -ArgumentList @("-avd", $AvdName, "-no-snapshot-load", "-skin", "1280x720") -PassThru
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    while ((Get-Date) -lt $deadline -and -not $serial) {
        Start-Sleep -Seconds 2
        if ($emuProcess.HasExited) {
            throw "Emulator exited before registering with ADB (exit code $($emuProcess.ExitCode)). Check available disk space and emulator diagnostics."
        }
        foreach ($device in (& $adb devices)) {
            if ($device -match '^(emulator-\d+)\s+device$') {
                $candidate = $Matches[1]
                $name = (& $adb -s $candidate emu avd name 2>$null | Select-Object -First 1)
                if ($name -eq $AvdName) { $serial = $candidate; break }
            }
        }
    }
    if (-not $serial) { throw "Emulator did not register with ADB within $BootTimeoutSeconds seconds. Process ID: $($emuProcess.Id)" }
}

Write-Host "Waiting for Android boot on $serial (timeout ${BootTimeoutSeconds}s)..."
$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
do {
    $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null | Select-Object -First 1)
    if ($boot -eq "1") { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if ($boot -ne "1") { throw "Android did not finish booting on $serial within $BootTimeoutSeconds seconds." }

# The emulator defaults to forwarding the host microphone only when explicitly enabled.
Invoke-Checked $adb @("-s", $serial, "emu", "avd", "hostmicon")
Write-Host "Host microphone forwarding enabled on $serial."

if ($InstallApk) {
    $explicitApkPath = -not [string]::IsNullOrWhiteSpace($ApkPath)
    if (-not $explicitApkPath) { $ApkPath = "C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk" }
    if (-not (Test-Path -LiteralPath $ApkPath)) {
        if ($explicitApkPath) { throw "The explicitly selected APK does not exist: $ApkPath" }
        $repo = Split-Path -Parent $PSScriptRoot
        $apkCandidates = @(Get-ChildItem -LiteralPath $repo -Filter app-debug.apk -File -Recurse -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
        if ($apkCandidates.Count -eq 0) { throw "APK not found at '$ApkPath' or under the repository. Build with .\gradlew.bat :app:assembleDebug first." }
        $ApkPath = $apkCandidates[0].FullName
        Write-Host "Using discovered APK: $ApkPath"
    }
    Invoke-Checked $adb @("-s", $serial, "install", "-r", (Resolve-Path -LiteralPath $ApkPath).Path)
    Invoke-Checked $adb @("-s", $serial, "shell", "am", "start", "-W", "-n", "com.novadrive.app/.MainActivity")
    $appPid = $null
    $appDeadline = (Get-Date).AddSeconds(10)
    do {
        $appPid = (& $adb -s $serial shell pidof com.novadrive.app 2>$null | Select-Object -First 1)
        if ($appPid) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $appDeadline)
    if (-not $appPid) { throw "com.novadrive.app was installed, but its process did not start on $serial within 10 seconds." }
    Write-Host "Installed and launched com.novadrive.app on $serial."
}

Write-Host "Emulator ready: $AvdName ($serial). Use adb -s $serial ... for all device commands."
