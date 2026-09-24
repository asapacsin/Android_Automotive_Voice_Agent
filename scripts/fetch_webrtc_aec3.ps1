# Vendors WebRTC AEC3 sources required by app/src/main/cpp/CMakeLists.txt, at the pinned revision
# recorded in docs/THIRD_PARTY_NATIVE.md. scripts/cloud_setup.sh pins the same revision.
$ErrorActionPreference = "Stop"
$pin = "2cec2f52e26646f93bd2d5498bbabf59cba18da9"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app\src\main\cpp\third_party\webrtc-aec3"
if (Test-Path (Join-Path $dest ".git")) {
    $head = (git -C $dest rev-parse HEAD).Trim()
    if ($head -eq $pin) {
        Write-Host "webrtc-aec3 already at pinned $pin"
        exit 0
    }
    Write-Host "webrtc-aec3 is at $head, not the pinned $pin; fetching the pin"
} else {
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    git -C $dest init -q
    git -C $dest remote add origin https://github.com/Enaium/webrtc-aec3.git
}
git -C $dest fetch -q --depth 1 origin $pin
git -C $dest checkout -q --detach FETCH_HEAD
# CMake globs these sources at configure time; a configuration cached without them links nothing.
$cxx = Join-Path $root "app\.cxx"
if (Test-Path $cxx) { Remove-Item -Recurse -Force $cxx }
Write-Host "webrtc-aec3 at pinned $pin in $dest"
