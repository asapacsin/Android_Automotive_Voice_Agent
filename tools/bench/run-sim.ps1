# Level A simulation benchmark with Gradle time and simulation time reported separately.
#   tools\bench\run-sim.ps1 -Suite RELEASE            # one pass
#   tools\bench\run-sim.ps1 -Suite STRESS -Repeat 60  # explicit repetitions only
param(
    [ValidateSet("SMOKE", "REGRESSION", "RELEASE", "CHAOS", "NAVIGATION", "VEHICLE", "MEDIA", "VISION", "LONG_SESSION", "STRESS")]
    [string]$Suite = "SMOKE",
    [int]$Repeat = 1,
    [long]$Seed = 184729,
    [string]$Scenario = "",
    [switch]$UpdateBaseline
)
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$bench = "C:\Users\Administrator\tools\nova-drive-build\app\bench"
$args = @(":app:testDebugUnitTest", "--tests", "*SimulationBenchmarkTest*", "--console=plain", "-q",
    "-PbenchSuite=$Suite", "-PbenchRepeat=$Repeat", "-PbenchSeed=$Seed")
if ($Scenario) { $args += "-PbenchScenario=$Scenario" }
if ($UpdateBaseline) { $args += "-PbenchUpdateBaseline=true" }
$before = Get-Date
$sw = [Diagnostics.Stopwatch]::StartNew()
& "$root\gradlew.bat" @args 2>&1 | Where-Object { $_ -notmatch "SDK XML|SDK processing" } | Out-Host
$exit = $LASTEXITCODE
$sw.Stop()
$run = Get-ChildItem $bench -Directory | Where-Object { $_.LastWriteTime -ge $before } | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $run) { Write-Host "no benchmark output (build or test failed before the run); exit $exit"; exit $exit }
$summary = Get-Content -Raw -Encoding utf8 "$($run.FullName)\summary.json" | ConvertFrom-Json
$simMs = [long]$summary.executionMs
$totalMs = [long]$sw.ElapsedMilliseconds
Get-Content -Encoding utf8 "$($run.FullName)\report.txt" | Out-Host
Write-Host ""
Write-Host ("TIME  total command {0:N1} s  =  simulation {1:N1} s  +  Gradle/build/JVM {2:N1} s" -f ($totalMs / 1000), ($simMs / 1000), (($totalMs - $simMs) / 1000))
Write-Host "Output: $($run.FullName)"
exit $exit
