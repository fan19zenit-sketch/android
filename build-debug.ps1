$jbrCandidates = @(
    "$env:USERPROFILE\\.jdks\\jbr-17.0.14",
    "$env:USERPROFILE\\.jdks\\jbr-17",
    "$env:USERPROFILE\\.jdks\\jbr"
)

$javaHome = $jbrCandidates | Where-Object { Test-Path (Join-Path $_ 'bin\\java.exe') } | Select-Object -First 1
if (-not $javaHome) {
    throw "Java not found. Install Android Studio JBR or set JAVA_HOME manually."
}

$env:JAVA_HOME = $javaHome
$env:Path = "$env:JAVA_HOME\\bin;$env:Path"

Set-Location $PSScriptRoot
.\gradlew.bat assembleLegacyDebug assembleCleanDebug
