<#
  publish.ps1 - ship local code to the server and restart everything there.

  Flow:  (optional commit) -> push to GitHub -> server git pull -> redeploy.
  The repo is public, so the server pulls anonymously - no creds needed there.
  .env files are per-environment and are NEVER synced (secrets stay on each host).

  Usage:
    ./publish.ps1 -m "commit message"   commit all changes, then publish
    ./publish.ps1                        publish already-committed work
#>
param(
  [Alias("m")][string]$Message = ""
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$server     = "victor@192.168.1.10"
$remoteRepo = "~/Desktop/ObidianOptimizer"

if ($Message -ne "") {
  Write-Host "==> Committing local changes ..." -ForegroundColor Cyan
  git add -A
  git commit -m $Message
}

Write-Host "==> Pushing to GitHub ..." -ForegroundColor Cyan
git push origin master

Write-Host "==> Server: git pull + redeploy ..." -ForegroundColor Cyan
ssh $server "cd $remoteRepo && git pull --ff-only origin master && bash linux_scripts/redeploy.sh"

Write-Host ""
Write-Host "==> Published. Follow server boot logs with:" -ForegroundColor Green
Write-Host "    ssh $server 'tail -f ~/obsidian_start.log'" -ForegroundColor DarkGray
