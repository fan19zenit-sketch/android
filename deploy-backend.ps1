$root = $PSScriptRoot
Set-Location $root

if (-not (Test-Path ".\\backend\\.env.local")) {
    throw "Missing backend\\.env.local. Copy backend\\.env.example first and fill in tokens."
}

docker compose up -d --build
