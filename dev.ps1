<#
  dev.ps1 - local dev boot (Windows / Docker Desktop)

  Runs ONLY the testable components: postgres + backend + frontend.
  NOT started: cloudflared (no tunnel), embedder (GPU - this machine has none),
  host-wrapper. Background processing (embedding/images/cards/ingest/chrono) is
  already disabled via QUIET MODE in .env.

  Usage:
    ./dev.ps1          build + start the dev stack
    ./dev.ps1 -Down    stop the dev stack
    ./dev.ps1 -Logs    follow backend logs
#>
param(
  [switch]$Down,
  [switch]$Logs
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$compose = @("-f", "docker-compose.yml", "-f", "docker-compose.dev.yml")

if ($Down) {
  docker compose @compose down --remove-orphans
  return
}
if ($Logs) {
  docker compose @compose logs -f backend
  return
}

Write-Host "==> Starting postgres ..." -ForegroundColor Cyan
docker compose @compose up -d postgres

Write-Host "==> Waiting for postgres health ..." -ForegroundColor Cyan
$healthy = $false
for ($i = 0; $i -lt 30; $i++) {
  try { $state = docker inspect -f '{{.State.Health.Status}}' obidianoptimizer-postgres-1 2>$null } catch { $state = "" }
  if ($state -eq "healthy") { $healthy = $true; break }
  Start-Sleep -Seconds 2
}
if (-not $healthy) { Write-Warning "postgres not reported healthy; continuing anyway" }

Write-Host "==> Building + starting backend + frontend (no embedder, no cloudflared, no host-wrapper) ..." -ForegroundColor Cyan
docker compose @compose up -d --no-deps --build backend frontend

Write-Host "==> Status:" -ForegroundColor Green
docker compose @compose ps
Write-Host ""
Write-Host "UI:      http://localhost:8081" -ForegroundColor Green
Write-Host "Backend: http://localhost:8084" -ForegroundColor Green
Write-Host "Logs: ./dev.ps1 -Logs   |   Stop: ./dev.ps1 -Down" -ForegroundColor DarkGray
