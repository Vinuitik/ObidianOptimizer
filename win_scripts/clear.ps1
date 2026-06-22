<#
.SYNOPSIS
  Clean-slate reset for ObsidianOptimizer. Wipes this project's database volume so
  the next start rebuilds EVERYTHING from the vault — full re-index, re-embed,
  re-generate cards — letting you watch the system form from the ground up.

.DESCRIPTION
  Removes ONLY this project's volumes:
    <project>_postgres_data    notes index, note_chunks, cards, reviews, sync_queue,
                               app_settings, pending_image_jobs — all app state.

  KEEPS <project>_embedder_models by default (the ~3-4GB ONNX/CLIP/whisper cache)
  so the next boot is fast. Pass -IncludeModels to wipe that too and force a full
  re-download (a true from-absolute-zero boot).

  NEVER touches:
    - your VAULT — it's a host folder (bind mount), not a volume, and it's the
      source of truth the fresh DB rebuilds FROM. Your notes are safe.
    - any OTHER docker project's volumes (communicator, habittracker, …).

  Note: a clean DB re-marks every file PENDING in sync_queue, so the next sync
  re-uploads the vault to Drive. Expected for a clean slate.

.PARAMETER IncludeModels
  Also remove the embedder model cache (forces ~3-4GB re-download on next start).

.PARAMETER Force
  Skip the confirmation prompt.

.EXAMPLE
  .\clear.ps1                 # wipe DB, keep model cache (fast reboot)
  .\clear.ps1 -IncludeModels  # wipe DB + models (absolute zero)
  .\clear.ps1 -Force          # no prompt
#>
param(
    [switch]$IncludeModels,
    [switch]$Force
)

$rootDir = Split-Path $PSScriptRoot -Parent
$compose = Join-Path $rootDir "docker-compose.yml"
if (-not (Test-Path $compose)) { Write-Error "docker-compose.yml not found next to this script."; exit 1 }

# Project name docker compose derives from the directory (lowercased, sanitized).
$proj = (Split-Path $rootDir -Leaf).ToLower() -replace '[^a-z0-9_-]', ''

Write-Host "`n=== CLEAN SLATE: $proj ===" -ForegroundColor Cyan

$targets = @("${proj}_postgres_data")
if ($IncludeModels) { $targets += "${proj}_embedder_models" }

Write-Host "Will remove:"
$targets | ForEach-Object { Write-Host "  - $_" }
if (-not $IncludeModels) {
    Write-Host "Keeping ${proj}_embedder_models (model cache) — pass -IncludeModels to wipe it too." -ForegroundColor DarkGray
}
Write-Host "Your vault is a host folder and is NOT touched." -ForegroundColor DarkGray

if (-not $Force) {
    $ans = Read-Host "`nThis ERASES the database (notes index, cards, reviews, sync queue, settings). Type 'yes' to proceed"
    if ($ans -ne 'yes') { Write-Host "Aborted — nothing removed." -ForegroundColor Yellow; return }
}

# Stop + remove containers/networks (volumes survive a plain `down`). The tunnel
# profile is included so cloudflared is torn down too if it was running.
Write-Host "`nStopping containers..." -ForegroundColor Green
docker compose -f $compose --profile tunnel down

# Remove the targeted volumes by exact name.
foreach ($v in $targets) {
    $exists = docker volume ls -q -f "name=^$v$"
    if ($exists) {
        docker volume rm $v | ForEach-Object { Write-Host "  removed $_" -ForegroundColor Green }
    } else {
        Write-Host "  (not found: $v — already clean)" -ForegroundColor Yellow
    }
}

Write-Host "`n=== DONE ===" -ForegroundColor Cyan
Write-Host "Next launch boots from a clean slate:"
Write-Host "  .\win_scripts\start.ps1   (or  docker compose up --build)"
Write-Host "  -> fresh DB -> full re-index from vault -> re-embed -> card generation"
if (-not $IncludeModels) { Write-Host "  -> models reused from cache (fast boot)" -ForegroundColor DarkGray }
