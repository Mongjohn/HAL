param(
    [ValidateSet('Snapshot', 'ColdActivity', 'RetainedActivity')][string]$Mode = 'Snapshot',
    [string]$Serial = '10.1.0.130:5555',
    [ValidateRange(1, 100)][int]$Iterations = 20,
    [switch]$AllowInterruption
)
$ErrorActionPreference = 'Stop'
$halRoot = Split-Path -Parent $PSScriptRoot
$halAdb = Join-Path $halRoot 'build/hal-toolchain/android-sdk/platform-tools/adb.exe'
function Invoke-HalAdb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $halOutput = & $halAdb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed for $Serial (exit $LASTEXITCODE)." }
    return ($halOutput -join "`n")
}
if ($Mode -ne 'Snapshot' -and -not $AllowInterruption) {
    throw 'This mode interrupts the TV. Arrange a test session and pass -AllowInterruption. Saved HAL data is preserved, but Android can disable HAL Helper after force-stop; verify and restore its original Android Accessibility choice afterward.'
}
$halModel = Invoke-HalAdb @('shell', 'getprop', 'ro.product.model')
$halSdk = Invoke-HalAdb @('shell', 'getprop', 'ro.build.version.sdk')
$halFingerprint = Invoke-HalAdb @('shell', 'getprop', 'ro.build.fingerprint')
$halPackage = Invoke-HalAdb @('shell', 'dumpsys', 'package', 'com.evofactory.hal')
$halMemory = Invoke-HalAdb @('shell', 'dumpsys', 'meminfo', 'com.evofactory.hal')
$halGraphics = Invoke-HalAdb @('shell', 'dumpsys', 'gfxinfo', 'com.evofactory.hal')
$halActivity = Invoke-HalAdb @('shell', 'dumpsys', 'activity', 'activities')
$halResults = @()
if ($Mode -ne 'Snapshot') {
    for ($halIteration = 0; $halIteration -lt $Iterations; $halIteration++) {
        if ($Mode -eq 'ColdActivity') { $null = Invoke-HalAdb @('shell', 'am', 'force-stop', 'com.evofactory.hal') }
        $halLaunch = Invoke-HalAdb @('shell', 'am', 'start', '-W', '-n', 'com.evofactory.hal/.MainActivity', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        $halRow = [ordered]@{ iteration = $halIteration + 1 }
        foreach ($halMetric in @('ThisTime', 'TotalTime', 'WaitTime')) {
            $halMatch = [regex]::Match($halLaunch, "(?m)^${halMetric}:\s*(\d+)")
            $halRow[$halMetric] = if ($halMatch.Success) { [int]$halMatch.Groups[1].Value } else { $null }
        }
        $halResults += [pscustomobject]$halRow
        Start-Sleep -Milliseconds 500
    }
}
$halSummary = [ordered]@{
    capturedUtc = [DateTime]::UtcNow.ToString('o'); mode = $Mode; serial = $Serial
    model = $halModel.Trim(); sdk = $halSdk.Trim(); fingerprint = $halFingerprint.Trim()
    sourceCommit = (& git -C $halRoot rev-parse HEAD)
    package = @($halPackage -split "`n" | Where-Object { $_ -match 'versionCode=|versionName=|lastUpdateTime=|flags=\[' } | ForEach-Object { $_.Trim() })
    foreground = @($halActivity -split "`n" | Where-Object { $_ -match 'mResumedActivity:|topResumedActivity=' } | ForEach-Object { $_.Trim() })
    memory = @($halMemory -split "`n" | Where-Object { $_ -match 'TOTAL PSS:|TOTAL RSS:|TOTAL SWAP PSS:' } | ForEach-Object { $_.Trim() })
    graphics = @($halGraphics -split "`n" | Where-Object { $_ -match '^Total frames rendered:|^Janky frames:|^\d+th percentile:|^Pipeline=' })
    timings = $halResults
    limits = 'Activity intent timing is not physical Home/input latency or a compilation-controlled benchmark. Snapshot counters are historical; record output mode, thermal/cache/compilation state and fixture separately. Source commit does not establish installed APK identity.'
}
$halDirectory = Join-Path $halRoot 'build/performance'
$null = New-Item -ItemType Directory -Force -Path $halDirectory
$halDestination = Join-Path $halDirectory (([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')) + '-' + $Mode + '.json')
$halSummary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $halDestination -Encoding utf8
Write-Output $halDestination
