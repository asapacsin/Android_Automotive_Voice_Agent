param([string[]]$Steps, [int]$Wait = 12)
# Speech harness driver (SPEC-004 A-live). Debug builds only; every say:/speak: step spends Baidu quota.
# Steps: launch | start | stop | say:<name> | speak:<text> | gain:on|off | tap:<regex> | sleep:<s> | climate:<arg>
# Push the PCM files first:  adb push speech\<name>.pcm /sdcard/Android/data/com.novadrive.app/files/test_speech/
$adb = "C:\Users\Administrator\Android\Sdk\platform-tools\adb.exe"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& $adb start-server 2>$null | Out-Null
& $adb logcat -c
$interesting = 'test_speech|flex_event type=(input_audio_buffer\.speech|response\.created|error)|transcript=|tool=|flex_response_done|flex_empty|flex_user_text|vision_|nav_resolve_candidates|nav_route_candidates|nav_navigation_started|nav_stopped|nav_flow|error=|state=(ERROR|CONNECTING|DISCONNECTED)|debug_tool'
foreach ($step in $Steps) {
    "## $step"
    if ($step -eq 'launch') { & $adb shell am start -n com.novadrive.app/.MainActivity | Out-Null; Start-Sleep -Seconds 8; continue }
    if ($step -like 'sleep:*') { Start-Sleep -Seconds ([int]$step.Substring(6)); continue }
    if ($step -like 'tap:*') {
        $pat = $step.Substring(4)
        & $adb shell uiautomator dump /sdcard/h.xml | Out-Null
        $x = (& $adb shell cat /sdcard/h.xml) -join ""
        $m = [regex]::Match($x, 'text="' + $pat + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($m.Success) {
            & $adb shell input tap ([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)) ([int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2))
            "  tapped: $pat"
        } else { "  NOT FOUND: $pat" }
        Start-Sleep -Seconds $Wait
        continue
    }
    if ($step -like 'climate:*') {
        & $adb shell am broadcast -n com.novadrive.app/.DebugToolReceiver --es tool climate --es arg $step.Substring(8) | Out-Null
        Start-Sleep -Seconds 1
        continue
    }
    & $adb shell am broadcast -n com.novadrive.app/.DebugToolReceiver --es tool voice --es arg $step | Out-Null
    $pause = if ($step -eq 'start') { 5 } elseif ($step -eq 'stop') { 2 } else { $Wait }
    Start-Sleep -Seconds $pause
}
"## LOG"
& $adb logcat -d -s NovaVoice | Select-String -Pattern $interesting | ForEach-Object { $_.Line.Trim() -replace '^\S+ (\S+)\s+\d+\s+\d+ D NovaVoice: ', '$1 ' }
