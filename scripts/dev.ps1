#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$javaHome = "C:\Users\Administrator\tools\jdk-17"
$sdkRoot = "C:\Users\Administrator\Android\Sdk"
$gradleHome = "C:\Users\Administrator\tools\gradle-8.11.1"
$gradleZip = "C:\Users\Administrator\tools\gradle-8.11.1-bin.zip"
$repo = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = $javaHome
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_HOME = $sdkRoot
$env:Path = "$javaHome\bin;$sdkRoot\cmdline-tools\latest\bin;$env:Path"
if (-not $env:GRADLE_OPTS) {
    $env:GRADLE_OPTS = "-Djava.net.preferIPv4Stack=true"
}
$javaTmp = "C:\Users\Administrator\tools\java-tmp"
New-Item -ItemType Directory -Force -Path $javaTmp | Out-Null
$env:TEMP = $javaTmp
$env:TMP = $javaTmp
$env:TMPDIR = $javaTmp

if (-not (Test-Path "$gradleHome\bin\gradle.bat")) {
    Write-Host "Extracting Gradle 8.11.1..."
    Expand-Archive -LiteralPath $gradleZip -DestinationPath (Split-Path $gradleHome) -Force
}

$platform = Join-Path $sdkRoot "platforms\android-34"
if (-not (Test-Path $platform)) {
    Write-Host "Installing Android SDK platform 34 and build-tools 34.0.0..."
    $sdkmanager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
    cmd /c "echo y| `"$sdkmanager`" --sdk_root=$sdkRoot --licenses"
    & $sdkmanager --sdk_root=$sdkRoot "platforms;android-34" "build-tools;34.0.0"
}

Set-Location $repo
& "$gradleHome\bin\gradle.bat" @args
