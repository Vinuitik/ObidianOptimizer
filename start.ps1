$projectDir = "$PSScriptRoot\obsidian_optimizer\obsidian"

if (-not (Test-Path $projectDir)) {
    Write-Error "Project directory not found: $projectDir"
    exit 1
}

Write-Host "Starting ObsidianOptimizer on http://localhost:8082 ..."
Set-Location $projectDir
& ".\mvnw.cmd" spring-boot:run
