# Generates extension-firefox/ — a Firefox-loadable copy of extension/.
#
# Why a copy: Chrome MV3 requires `background.service_worker`; Firefox MV3 wants
# `background.scripts`. They can't share one manifest.json, and Firefox's
# "Load Temporary Add-on" always reads the directory's manifest.json. So we copy
# the source and drop the Firefox manifest in as manifest.json. The JS is shared
# verbatim (it uses the `browser ?? chrome` shim, so it runs in both engines).
#
# Run after any edit to extension/, then in Firefox:
#   about:debugging#/runtime/this-firefox -> Load Temporary Add-on -> pick
#   extension-firefox/manifest.json
#
# Chrome/Edge/Brave keep loading the original extension/ folder unchanged.

$ErrorActionPreference = 'Stop'
$rootDir = Split-Path $PSScriptRoot -Parent
$src = Join-Path $rootDir 'extension'
$out = Join-Path $rootDir 'extension-firefox'

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null

Copy-Item "$src/*" $out -Recurse -Force
Remove-Item (Join-Path $out 'manifest.firefox.json') -Force -ErrorAction SilentlyContinue
Copy-Item "$src/manifest.firefox.json" (Join-Path $out 'manifest.json') -Force

Write-Host "Firefox extension ready: $out"
Write-Host "Load it: about:debugging#/runtime/this-firefox -> Load Temporary Add-on -> select extension-firefox/manifest.json"
