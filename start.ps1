$envFile = "$PSScriptRoot\.env"
if (-not (Test-Path $envFile)) {
    Write-Error ".env not found. Copy .env.example to .env and set HOST_VAULT_PATH to your vault directory."
    exit 1
}

docker compose -f "$PSScriptRoot\docker-compose.yml" up --build
