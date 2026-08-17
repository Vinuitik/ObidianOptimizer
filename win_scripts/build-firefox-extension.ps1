# Generates extension-firefox/ — a Firefox-loadable copy of extension/.
#
# SSOT: extension/manifest.json is the ONE canonical manifest (name, icons,
# permissions, action, etc). extension/manifest.firefox.overlay.json holds ONLY
# the fields that must differ for Firefox — version, background, and
# browser_specific_settings.gecko — because Chrome MV3 requires
# `background.service_worker` while Firefox MV3 wants `background.scripts`, and
# only Firefox understands `browser_specific_settings`. There is no second full
# manifest to drift out of sync: any change to permissions/icons/name/etc lives
# in manifest.json alone and both browsers pick it up automatically.
#
# Merge semantics: every top-level key present in the overlay
# (version/background/browser_specific_settings) fully REPLACES that key from
# the base manifest — no recursion into nested objects. That's required here:
# the overlay's `background` object must wholly replace the base's, not
# field-merge with it. (Mirrors the shallow `jq -s '.[0] + .[1]'` merge in the
# Linux build script.)
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
Remove-Item (Join-Path $out 'manifest.firefox.overlay.json') -Force -ErrorAction SilentlyContinue

$base = Get-Content (Join-Path $src 'manifest.json') -Raw | ConvertFrom-Json -AsHashtable
$overlay = Get-Content (Join-Path $src 'manifest.firefox.overlay.json') -Raw | ConvertFrom-Json -AsHashtable
foreach ($key in $overlay.Keys) { $base[$key] = $overlay[$key] }
$base | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $out 'manifest.json')

Write-Host "Firefox extension ready: $out"
Write-Host "Load it: about:debugging#/runtime/this-firefox -> Load Temporary Add-on -> select extension-firefox/manifest.json"
