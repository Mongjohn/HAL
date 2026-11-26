param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9-]+$')][string]$Label,
    [ValidateRange(1, 30)][int]$Iterations = 10,
    [string]$Serial = '10.1.0.130:5555',
    [switch]$AllowInterruption
)
$ErrorActionPreference = 'Stop'
if (-not $AllowInterruption) { throw 'This installs an APK and interrupts the TV. Force-stop/package replacement can disable HAL Helper in Android Accessibility. Arrange restoration of its original choice, then pass -AllowInterruption.' }
$halRoot = Split-Path -Parent $PSScriptRoot
$halAdb = Join-Path $halRoot 'build/hal-toolchain/android-sdk/platform-tools/adb.exe'
$halApk = (Resolve-Path -LiteralPath $Apk).Path
$halAapt = Join-Path $halRoot 'build/hal-toolchain/android-sdk/build-tools/36.0.0/aapt2.exe'
$badging = & $halAapt dump badging $halApk 2>&1
if ($LASTEXITCODE -ne 0 -or ($badging -join "`n") -notmatch "package: name='com\.evofactory\.hal'") { throw 'Expected a HAL APK; no installation attempted.' }
if (($badging -join "`n") -match 'application-debuggable') { throw 'Expected a non-debuggable optimized APK; no installation attempted.' }
$halOutput = Join-Path $halRoot ('build/performance/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + $Label)
$null = New-Item -ItemType Directory -Force -Path $halOutput
function Invoke-HalDevice {
    # A simple function preserves native switches such as am's -W instead of binding -WarningAction.
    $deviceArguments = @($args | ForEach-Object { $_ })
    $result = & $halAdb -s $Serial @deviceArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed for $Label / $Serial ($($deviceArguments -join ' ')): $($result -join ' ')" }
    return ($result -join "`n")
}
function Read-HalLaunch {
    $result = Invoke-HalDevice shell am start -W -n com.evofactory.hal/.MainActivity -a android.intent.action.MAIN -c android.intent.category.HOME
    if ($result -notmatch '(?m)^Status:\s*ok') { throw "HAL launch did not succeed in $Label." }
    $row = [ordered]@{}
    foreach ($metric in @('TotalTime', 'WaitTime')) {
        $match = [regex]::Match($result, "(?m)^${metric}:\s*(\d+)")
        $row[$metric] = if ($match.Success) { [int]$match.Groups[1].Value } else { $null }
    }
    return [pscustomobject]$row
}
function Read-HalMemory {
    $result = Invoke-HalDevice shell dumpsys meminfo com.evofactory.hal
    return @($result -split "`n" | Where-Object { $_ -match 'TOTAL PSS:|TOTAL RSS:|TOTAL SWAP PSS:|^\s*(Java Heap|Native Heap|Graphics|Code|Stack|Private Other|System):' } | ForEach-Object { $_.Trim() })
}
function Cover-Hal {
    $null = Invoke-HalDevice shell am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p com.google.android.youtube.tv
    Start-Sleep -Seconds 2
}
$model = (Invoke-HalDevice shell getprop ro.product.model).Trim()
$device = (Invoke-HalDevice shell getprop ro.product.device).Trim()
if ($Serial -eq '10.1.0.130:5555' -and $device -ne 'darcy') { throw 'Office identity mismatch.' }
$thermalBefore = Invoke-HalDevice shell dumpsys thermalservice
$helperBefore = (Invoke-HalDevice shell settings get secure enabled_accessibility_services).Contains('com.evofactory.hal/')
$apkHash = (Get-FileHash -LiteralPath $halApk -Algorithm SHA256).Hash
$null = Invoke-HalDevice install -r $halApk
$packagePath = (Invoke-HalDevice shell pm path com.evofactory.hal).Trim().Split("`n")[0].Substring(8)
$installedHash = (Invoke-HalDevice shell sha256sum $packagePath).Trim().Split(' ')[0]
if ($installedHash -ne $apkHash) { throw 'Installed APK hash mismatch.' }
$package = Invoke-HalDevice shell dumpsys package com.evofactory.hal
if ($package -match '(?m)^\s*flags=\[[^\r\n]*DEBUGGABLE') { throw 'This journey requires a non-debuggable optimized APK.' }
# Full AOT uses the same filter for both APKs without relying on profile training.
# SHIELD denies shell profile clearing (-c); no root/permission bypass is attempted.
$compile = Invoke-HalDevice shell cmd package compile -m speed -f com.evofactory.hal
if ($compile -notmatch 'Success') { throw 'Compilation control failed.' }
$dexopt = Invoke-HalDevice shell dumpsys package com.evofactory.hal
if ($dexopt -notmatch 'status=speed\]') { throw 'Expected speed compilation filter was not reported.' }
$null = Invoke-HalDevice shell input keyevent KEYCODE_WAKEUP
$null = Invoke-HalDevice shell am force-stop com.evofactory.hal
$null = Read-HalLaunch
Start-Sleep -Seconds 3
$cold = @()
for ($i = 0; $i -lt $Iterations; $i++) {
    $null = Invoke-HalDevice shell am force-stop com.evofactory.hal
    $cold += Read-HalLaunch
    Start-Sleep -Seconds 2
}
$retained = @()
for ($i = 0; $i -lt $Iterations; $i++) {
    Cover-Hal
    $retained += Read-HalLaunch
    Start-Sleep -Seconds 2
}
$visibleMemory = Read-HalMemory
$null = Invoke-HalDevice shell dumpsys gfxinfo com.evofactory.hal reset
$null = Invoke-HalDevice shell input keyevent KEYCODE_HOME
$keys = @(1..12 | ForEach-Object { 'KEYCODE_DPAD_RIGHT'; 'KEYCODE_DPAD_LEFT' })
$null = Invoke-HalDevice (@('shell', 'input', 'keyevent') + $keys)
Start-Sleep -Seconds 2
$graphics = Invoke-HalDevice shell dumpsys gfxinfo com.evofactory.hal
Cover-Hal
$hiddenMemory = Read-HalMemory
$null = Read-HalLaunch
# Dedicated trace: cold launch/first tile focus, rapid D-pad events and one hidden return.
$configPath = Join-Path $halOutput 'journey.pbtxt'
$traceConfig = @'
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
duration_ms: 20000
data_sources { config { name: "linux.ftrace" ftrace_config {
 ftrace_events: "sched/sched_switch"
 ftrace_events: "sched/sched_waking"
 ftrace_events: "power/cpu_frequency"
 atrace_categories: "gfx"
 atrace_categories: "input"
 atrace_categories: "view"
 atrace_categories: "wm"
 atrace_categories: "am"
 atrace_categories: "dalvik"
 atrace_categories: "binder_driver"
 atrace_apps: "com.evofactory.hal"
} } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 } } }
'@
[IO.File]::WriteAllText($configPath, $traceConfig, [Text.UTF8Encoding]::new($false))
# Perfetto's SELinux domain cannot read shell-owned /data/local/tmp files on this SHIELD.
# Its supported stdin configuration path avoids changing file permissions or policy.
$traceStart = $traceConfig | & $halAdb -s $Serial shell perfetto --background --txt -c - -o /data/misc/perfetto-traces/hal-journey.pftrace 2>&1
if ($LASTEXITCODE -ne 0) { throw "Perfetto could not start: $($traceStart -join ' ')" }
$traceClock = [Diagnostics.Stopwatch]::StartNew()
$null = Invoke-HalDevice shell am force-stop com.evofactory.hal
# Use HAL Helper's existing action for the focus trace. Ordinary Activity MAIN entry intentionally
# uses header focus and cannot establish the separate handled-Home app-focus readiness metric.
$null = Invoke-HalDevice shell am start -W -n com.evofactory.hal/.MainActivity -a com.evofactory.hal.FOCUS_HOME
Start-Sleep -Seconds 2
$null = Invoke-HalDevice (@('shell', 'input', 'keyevent') + $keys)
Cover-Hal
$null = Read-HalLaunch
$remaining = 22 - $traceClock.Elapsed.TotalSeconds
if ($remaining -gt 0) { Start-Sleep -Milliseconds ([int]($remaining * 1000)) }
$null = Invoke-HalDevice pull /data/misc/perfetto-traces/hal-journey.pftrace (Join-Path $halOutput 'journey.pftrace')
$halPid = (Invoke-HalDevice shell pidof com.evofactory.hal).Trim()
$crash = Invoke-HalDevice logcat -b crash -d "--pid=$halPid" -v brief
$thermalAfter = Invoke-HalDevice shell dumpsys thermalservice
$summary = [ordered]@{
    label = $Label; capturedUtc = [DateTime]::UtcNow.ToString('o'); serial = $Serial
    model = $model; device = $device; apkSha256 = $apkHash; compilation = 'speed -f (profiles preserved; shell cannot clear them)'
    dexopt = @($dexopt -split "`n" | Where-Object { $_ -match 'status=speed\]' } | ForEach-Object { $_.Trim() })
    package = @($package -split "`n" | Where-Object { $_ -match 'versionCode=|versionName=|lastUpdateTime=|^\s*flags=\[' } | ForEach-Object { $_.Trim() })
    thermalBefore = @($thermalBefore -split "`n" | Where-Object { $_ -match 'Thermal Status:|mName=CPU0|mName=GPU' } | ForEach-Object { $_.Trim() })
    thermalAfter = @($thermalAfter -split "`n" | Where-Object { $_ -match 'Thermal Status:|mName=CPU0|mName=GPU' } | ForEach-Object { $_.Trim() })
    coldActivity = $cold; retainedActivityIntent = $retained
    visibleMemory = $visibleMemory; hiddenMemory = $hiddenMemory
    traceEntryAction = 'com.evofactory.hal.FOCUS_HOME'
    rapidKeyGraphics = @($graphics -split "`n" | Where-Object { $_ -match '^Total frames rendered:|^Janky frames:|^\d+th percentile:' })
    crashBufferLines = @($crash -split "`n" | Where-Object { $_.Trim() -and $_ -notmatch '^---' }).Count
    helperEnabledBefore = $helperBefore
    helperEnabledAfter = (Invoke-HalDevice shell settings get secure enabled_accessibility_services).Contains('com.evofactory.hal/')
    limits = 'Live saved fixture; no data/OS page-cache clearing. Cold is process-cold after one unmeasured warm-up. Activity intent timings are not physical Home latency; retained return uses explicit intent after YouTube. Graphics are a short mixed rendering sample. Trace firstFocus marks a focused real app tile, not artwork completion. Restore the everyday debug APK in the campaign orchestrator even after failure.'
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $halOutput 'summary.json') -Encoding utf8
if ($helperBefore -and -not $summary.helperEnabledAfter) { Write-Warning 'Android disabled the existing HAL Helper. After restoring the everyday APK, restore its original choice through Android Settings > Device Preferences > Accessibility, then verify a real Home event. Do not write secure settings directly.' }
Write-Output $halOutput
