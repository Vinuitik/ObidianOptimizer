$rootDir    = $PSScriptRoot
$backendDir = "$rootDir\obsidian_optimizer\obsidian"

if (-not (Test-Path $backendDir)) {
    Write-Error "Backend directory not found: $backendDir"
    exit 1
}

Write-Host "Starting Java backend in a new window..."
Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$backendDir'; .\mvnw.cmd spring-boot:run"

Write-Host "Starting Nginx frontend via Docker Compose (http://localhost)..."
docker compose -f "$rootDir\docker-compose.yml" up --build
