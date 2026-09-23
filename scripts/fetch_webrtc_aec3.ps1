# Vendors WebRTC AEC3 sources required by app/src/main/cpp/CMakeLists.txt
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app\src\main\cpp\third_party\webrtc-aec3"
if (Test-Path (Join-Path $dest ".git")) {
    Write-Host "webrtc-aec3 already present at $dest"
    exit 0
}
git clone --depth 1 https://github.com/Enaium/webrtc-aec3.git $dest
Write-Host "cloned webrtc-aec3 to $dest"
