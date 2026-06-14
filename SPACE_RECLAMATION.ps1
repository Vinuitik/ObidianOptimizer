<#
.SYNOPSIS
  Reclaim disk eaten by Docker/WSL — prune build cache + dead images, then
  COMPACT the WSL virtual disk so the freed space actually returns to Windows.

.DESCRIPTION
  Why this exists: WSL2 stores everything in an ext4.vhdx that grows during
  builds and NEVER shrinks by itself. `docker prune` frees space *inside* that
  file; only compaction returns it to the C: drive. This does both, safely.

  SAFE BY DESIGN:
    - NEVER prunes volumes (your Postgres data, embedder model cache, mongo,
      etc. live in volumes — they are left completely untouched).
    - Compaction does NOT delete data; it only reclaims already-free blocks.
    - Default image prune removes ONLY dangling (untagged) layers. Use
      -DeepImages to also drop tagged images not currently used by a container
      (those get rebuilt next `compose up` — more space, slower next start).

  Requires admin (diskpart compaction needs it) — self-elevates if needed.

.PARAMETER DeepImages
  Also remove ALL images not used by a running container (docker image prune -a).
  Frees the most space; forces a rebuild of any stopped stack on next `up`.

.PARAMETER SkipCompact
  Prune only; do not shut down WSL or compact the vhdx. Use when you can't
  afford to stop Docker right now.

.EXAMPLE
  .\SPACE_RECLAMATION.ps1
  .\SPACE_RECLAMATION.ps1 -DeepImages
#>
[CmdletBinding()]
param(
    [switch]$DeepImages,
    [switch]$SkipCompact
)

# ── Self-elevate (diskpart compaction needs admin) ─────────────────────────
$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "Elevating to administrator (needed for vhdx compaction)..." -ForegroundColor Yellow
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
    if ($DeepImages)  { $argList += "-DeepImages" }
    if ($SkipCompact) { $argList += "-SkipCompact" }
    Start-Process -FilePath "pwsh.exe" -Verb RunAs -ArgumentList $argList
    return
}

function Get-FreeGB { [math]::Round((Get-PSDrive C).Free / 1GB, 1) }

$startFree = Get-FreeGB
Write-Host "`n=== SPACE RECLAMATION ===" -ForegroundColor Cyan
Write-Host ("C: free before : {0} GB" -f $startFree)

# ── 1. Docker pruning (NEVER volumes) ──────────────────────────────────────
$dockerUp = $null -ne (Get-Process "com.docker.backend" -ErrorAction SilentlyContinue) `
            -or ($null -ne (& docker info 2>$null))

if ($dockerUp) {
    Write-Host "`n[1/3] Docker cleanup (volumes are NOT touched)..." -ForegroundColor Green

    Write-Host "  - usage before:"
    docker system df 2>$null

    Write-Host "  - stopped containers..."
    docker container prune -f | Out-Null

    Write-Host "  - build cache..."
    docker builder prune -f | Out-Null

    if ($DeepImages) {
        Write-Host "  - ALL unused images (-DeepImages)..."
        docker image prune -a -f | Out-Null
    } else {
        Write-Host "  - dangling images..."
        docker image prune -f | Out-Null
    }

    Write-Host "  - unused networks..."
    docker network prune -f | Out-Null

    Write-Host "  - usage after:"
    docker system df 2>$null
} else {
    Write-Host "`n[1/3] Docker not running — skipping prune, going straight to compaction." -ForegroundColor Yellow
}

if ($SkipCompact) {
    Write-Host "`n-SkipCompact set — done (no vhdx compaction)." -ForegroundColor Yellow
    Write-Host ("C: free now    : {0} GB  (reclaimed {1} GB)" -f (Get-FreeGB), ((Get-FreeGB) - $startFree))
    return
}

# ── 2. Shut down WSL so the vhdx files are unlocked ─────────────────────────
Write-Host "`n[2/3] Shutting down WSL (this also stops Docker Desktop's VM)..." -ForegroundColor Green
wsl --shutdown
Start-Sleep -Seconds 4   # let the vhdx handles release

# ── 3. Locate + compact every Docker/WSL vhdx ───────────────────────────────
Write-Host "`n[3/3] Compacting virtual disks..." -ForegroundColor Green

# Known Docker Desktop locations vary by version; search the usual roots.
$searchRoots = @(
    "$env:LOCALAPPDATA\Docker\wsl",
    "$env:LOCALAPPDATA\wsl"
)
$vhdxFiles = foreach ($root in $searchRoots) {
    if (Test-Path $root) {
        Get-ChildItem -Path $root -Recurse -Filter *.vhdx -ErrorAction SilentlyContinue
    }
}
$vhdxFiles = $vhdxFiles | Sort-Object FullName -Unique

if (-not $vhdxFiles) {
    Write-Warning "No Docker vhdx found under the usual paths. If Docker stores its disk elsewhere, point this script at it. Skipping compaction."
} else {
    foreach ($vhdx in $vhdxFiles) {
        $sizeBefore = [math]::Round($vhdx.Length / 1GB, 2)
        Write-Host ("  - {0}  ({1} GB)" -f $vhdx.FullName, $sizeBefore)

        # diskpart compaction recipe (works on Windows Home — no Hyper-V needed):
        #   select → attach read-only → compact → detach
        $script = @"
select vdisk file="$($vhdx.FullName)"
attach vdisk readonly
compact vdisk
detach vdisk
exit
"@
        $tmp = [System.IO.Path]::GetTempFileName()
        Set-Content -Path $tmp -Value $script -Encoding ASCII
        try {
            diskpart /s $tmp | Out-Null
        } catch {
            Write-Warning "    compaction failed for $($vhdx.Name): $_"
        } finally {
            Remove-Item $tmp -ErrorAction SilentlyContinue
        }

        $vhdx.Refresh()
        $sizeAfter = [math]::Round($vhdx.Length / 1GB, 2)
        Write-Host ("    -> {0} GB  (shrank {1} GB)" -f $sizeAfter, ([math]::Round($sizeBefore - $sizeAfter, 2))) -ForegroundColor Cyan
    }
}

# ── GPU report (informational — see note below) ─────────────────────────────
$nvsmi = Get-Command nvidia-smi -ErrorAction SilentlyContinue
if ($nvsmi) {
    Write-Host "`nGPU memory right now:" -ForegroundColor Green
    nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader
    Write-Host "(VRAM is freed by stopping the process that holds it — i.e. restart" -ForegroundColor DarkGray
    Write-Host " the embedder container. There is no safe global 'flush VRAM' on a" -ForegroundColor DarkGray
    Write-Host " consumer GPU under WDDM, so this script only reports it.)" -ForegroundColor DarkGray
}

# ── Summary ─────────────────────────────────────────────────────────────────
$endFree = Get-FreeGB
Write-Host "`n=== DONE ===" -ForegroundColor Cyan
Write-Host ("C: free before : {0} GB" -f $startFree)
Write-Host ("C: free after  : {0} GB" -f $endFree)
Write-Host ("Reclaimed      : {0} GB" -f ([math]::Round($endFree - $startFree, 1))) -ForegroundColor Green
Write-Host "`nDocker Desktop will restart its VM on next launch (or relaunch it now)."
