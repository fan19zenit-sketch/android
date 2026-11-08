param(
    [Parameter(Mandatory = $true)][string]$BackendRepo,
    [Parameter(Mandatory = $true)][string]$Emulator,
    [switch]$Background,
    [switch]$OfflineBackground,
    [switch]$Camera
)

$ErrorActionPreference = 'Stop'
if (@($Background, $OfflineBackground, $Camera).Where({ $_ }).Count -gt 1) { throw 'Choose only one scenario.' }
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
$testStarted = $false
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
function Invoke-IntegrationTest {
    param([string]$ClassName)
    & (Join-Path $PSScriptRoot '..\gradlew.bat') :app:connectedCleanLocaltestAndroidTest -PlocalIntegration=true `
        "-Pandroid.testInstrumentationRunnerArguments.class=$ClassName" `
        '-Pandroid.testInstrumentationRunnerArguments.isolatedCookBackend=1' --offline --no-daemon --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw 'The isolated Android/backend test failed.' }
}
function Invoke-InstalledIntegrationTest {
    param([string]$ClassName)
    $output = & $adb -s $Emulator shell am instrument -w -e class $ClassName -e isolatedCookBackend 1 `
        'com.example.photovchistotu.localtest.test/androidx.test.runner.AndroidJUnitRunner' 2>&1
    $status = $LASTEXITCODE
    $text = $output | Out-String
    Write-Host $text
    if ($status -ne 0 -or $text -notmatch 'OK \(1 test\)' -or $text -match 'INSTRUMENTATION_STATUS_CODE: -3') {
        throw 'The isolated installed-app test failed or was skipped.'
    }
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
    $testStarted = $true
    if (-not $Background -and -not $OfflineBackground) {
        $class = if ($Camera) { 'com.example.simplephotouploader.CookCameraBackendIntegrationTest' }
                 else { 'com.example.simplephotouploader.CookRealBackendIntegrationTest' }
        Invoke-IntegrationTest $class
    } else {
        & (Join-Path $PSScriptRoot '..\gradlew.bat') :app:assembleCleanLocaltest :app:assembleCleanLocaltestAndroidTest `
            -PlocalIntegration=true --offline --no-daemon --max-workers=2
        if ($LASTEXITCODE -ne 0) { throw 'The isolated test APKs could not be built.' }
        $appApk = Join-Path $PSScriptRoot '..\app\build\outputs\apk\clean\localtest\app-clean-localtest.apk'
        $testApk = Join-Path $PSScriptRoot '..\app\build\outputs\apk\androidTest\clean\localtest\app-clean-localtest-androidTest.apk'
        if (-not (Test-Path -LiteralPath $appApk) -or -not (Test-Path -LiteralPath $testApk)) { throw 'The isolated test APKs are missing.' }
        Invoke-AdbChecked -Arguments @('install', '-r', $appApk)
        Invoke-AdbChecked -Arguments @('install', '-r', $testApk)
        $testClass = if ($OfflineBackground) { 'com.example.simplephotouploader.CookOfflineBackgroundIntegrationTest' }
                     else { 'com.example.simplephotouploader.CookBackgroundIntegrationTest' }
        Invoke-InstalledIntegrationTest "$testClass#seed"
        $seed = Invoke-RestMethod -Uri 'http://127.0.0.1:18433/fixture/background-pid' -TimeoutSec 3
        if (-not $seed.pid -or $seed.pid -lt 1) { throw 'The fixture did not record the test process.' }
        $current = (& $adb -s $Emulator shell pidof com.example.photovchistotu.localtest 2>$null | Out-String).Trim()
        if ($current) {
            Invoke-AdbChecked -Arguments @('shell', 'am', 'kill', 'com.example.photovchistotu.localtest')
            Start-Sleep -Seconds 1
            $current = (& $adb -s $Emulator shell pidof com.example.photovchistotu.localtest 2>$null | Out-String).Trim()
            if ($current) { throw 'The test process did not stop; background recovery was not verified.' }
        }
        Write-Host "Isolated app process $($seed.pid) is stopped; waiting for Android to restart queued work."
        Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18433/fixture/enable-capability' -TimeoutSec 3 | Out-Null
        $probeUrl = if ($OfflineBackground) { 'http://127.0.0.1:18433/fixture/offline-background' }
                    else { 'http://127.0.0.1:18433/fixture/upload/99999999-9999-4999-8999-999999999999' }
        $deadline = [datetime]::UtcNow.AddSeconds(180)
        $received = $false
        while ([datetime]::UtcNow -lt $deadline -and -not $received) {
            try {
                $result = Invoke-RestMethod -Uri $probeUrl -TimeoutSec 3
                $received = if ($OfflineBackground) { $result.employeeMapped -eq $true -and $result.shiftMapped -eq $true }
                            else { $result.status -eq 'uploaded_to_server' -and $result.queued -eq 1 }
            } catch { Start-Sleep -Seconds 2 }
            if (-not $received) { Start-Sleep -Seconds 2 }
        }
        if (-not $received) { throw 'Android did not resume the queued upload within the test window.' }
        Invoke-InstalledIntegrationTest "$testClass#verify"
    }
} finally {
    if ($locationPushed) { Pop-Location }
    if ($testStarted -and -not (Undo-Adb -Arguments @('shell', 'am', 'force-stop', 'com.example.photovchistotu.localtest'))) { $cleanupFailed = $true }
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
