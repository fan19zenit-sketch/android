param(
    [Parameter(Mandatory = $true)][string]$BackendRepo,
    [Parameter(Mandatory = $true)][string]$Emulator
)

$ErrorActionPreference = 'Stop'
if ($Emulator -notmatch '^emulator-[0-9]+$') { throw 'Only an explicitly named Android emulator is permitted.' }
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to the Android SDK.' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$backend = (Resolve-Path -LiteralPath $BackendRepo).Path
$harness = Join-Path $backend 'operations\photo_platform\tests\cook_android_local_harness.py'
if (-not (Test-Path -LiteralPath $adb) -or -not (Test-Path -LiteralPath $harness)) { throw 'ADB or the backend harness was not found.' }
$state = (& $adb -s $Emulator get-state 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') { throw 'The specified emulator is not available.' }
$qemu = (& $adb -s $Emulator shell getprop ro.kernel.qemu | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $qemu -ne '1') { throw 'The specified device is not an Android emulator.' }
$uid = (& $adb -s $Emulator shell id -u | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $uid -ne '0') { throw 'An already-rooted disposable emulator is required to block external traffic.' }
$existingReverse = & $adb -s $Emulator reverse --list
if ($LASTEXITCODE -ne 0 -or ($existingReverse | Select-String 'tcp:18433')) { throw 'The test reverse port is already in use.' }

$fixtureId = [guid]::NewGuid().ToString()
$server = $null
$serverVerified = $false
$reverse = $false
$v4Reject = $false
$v4Local = $false
$v6Reject = $false
$v6Local = $false
$originalSerial = $env:ANDROID_SERIAL
$locationPushed = $false
$cleanupFailed = $false
function Invoke-AdbChecked {
    param([string[]]$Arguments)
    & $adb -s $Emulator @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
}
function Undo-Adb {
    param([string[]]$Arguments)
    & $adb -s $Emulator @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Could not restore emulator state: $($Arguments -join ' ')"
        return $false
    }
    return $true
}

try {
    $python = (Get-Command python -ErrorAction Stop).Source
    $server = Start-Process -FilePath $python -WorkingDirectory $backend -WindowStyle Hidden -PassThru `
        -ArgumentList @('-m', 'operations.photo_platform.tests.cook_android_local_harness', '--fixture-id', $fixtureId)
    $deadline = [datetime]::UtcNow.AddSeconds(20)
    while ([datetime]::UtcNow -lt $deadline -and -not $serverVerified) {
        if ($server.HasExited) { throw 'The isolated backend exited before it was ready.' }
        try {
            $ready = Invoke-RestMethod -Uri 'http://127.0.0.1:18433/fixture/ready' -TimeoutSec 2
            $serverVerified = $ready.fixture -eq 'cook-android-local' -and $ready.id -eq $fixtureId
            if (-not $serverVerified) { throw 'The local port belongs to another service.' }
        } catch {
            if ($_.Exception.Message -eq 'The local port belongs to another service.') { throw }
            Start-Sleep -Milliseconds 300
        }
    }
    if (-not $serverVerified) { throw 'The isolated backend did not become ready.' }
    Invoke-AdbChecked -Arguments @('shell', 'iptables', '-I', 'OUTPUT', '1', '-j', 'REJECT'); $v4Reject = $true
    Invoke-AdbChecked -Arguments @('shell', 'iptables', '-I', 'OUTPUT', '1', '-o', 'lo', '-j', 'ACCEPT'); $v4Local = $true
    Invoke-AdbChecked -Arguments @('shell', 'ip6tables', '-I', 'OUTPUT', '1', '-j', 'REJECT'); $v6Reject = $true
    Invoke-AdbChecked -Arguments @('shell', 'ip6tables', '-I', 'OUTPUT', '1', '-o', 'lo', '-j', 'ACCEPT'); $v6Local = $true
    Invoke-AdbChecked -Arguments @('reverse', 'tcp:18433', 'tcp:18433'); $reverse = $true
    $env:ANDROID_SERIAL = $Emulator
    Push-Location -LiteralPath (Join-Path $PSScriptRoot '..')
    $locationPushed = $true
    & (Join-Path $PSScriptRoot '..\gradlew.bat') :app:connectedCleanDebugAndroidTest `
        '-Pandroid.testInstrumentationRunnerArguments.class=com.example.simplephotouploader.CookRealBackendIntegrationTest' `
        '-Pandroid.testInstrumentationRunnerArguments.isolatedCookBackend=1' --offline --no-daemon --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw 'The isolated Android/backend test failed.' }
} finally {
    if ($locationPushed) { Pop-Location }
    if ($reverse -and -not (Undo-Adb -Arguments @('reverse', '--remove', 'tcp:18433'))) { $cleanupFailed = $true }
    if ($v6Local -and -not (Undo-Adb -Arguments @('shell', 'ip6tables', '-D', 'OUTPUT', '-o', 'lo', '-j', 'ACCEPT'))) { $cleanupFailed = $true }
    if ($v6Reject -and -not (Undo-Adb -Arguments @('shell', 'ip6tables', '-D', 'OUTPUT', '-j', 'REJECT'))) { $cleanupFailed = $true }
    if ($v4Local -and -not (Undo-Adb -Arguments @('shell', 'iptables', '-D', 'OUTPUT', '-o', 'lo', '-j', 'ACCEPT'))) { $cleanupFailed = $true }
    if ($v4Reject -and -not (Undo-Adb -Arguments @('shell', 'iptables', '-D', 'OUTPUT', '-j', 'REJECT'))) { $cleanupFailed = $true }
    if ($null -eq $originalSerial) { Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue }
    else { $env:ANDROID_SERIAL = $originalSerial }
    if ($serverVerified -and $server -and -not $server.HasExited) {
        try { Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18433/fixture/shutdown' -TimeoutSec 3 | Out-Null }
        catch { Write-Warning 'Could not request clean shutdown of the isolated backend.' }
    }
    if ($server -and -not $server.WaitForExit(5000)) {
        Stop-Process -Id $server.Id -Force
        Write-Warning 'Isolated backend was stopped forcibly; check the temporary directory.'
        $cleanupFailed = $true
    }
    if ($cleanupFailed) { throw 'The isolated test cleanup was incomplete; inspect emulator state and temporary files.' }
}
