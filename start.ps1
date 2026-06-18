$envFile = "$PSScriptRoot\.env"
if (-not (Test-Path $envFile)) {
    Write-Error ".env not found. Copy .env.example to .env and set HOST_VAULT_PATH to your vault directory."
    exit 1
}

# ── Vault location check ──────────────────────────────────────────────────────
# Vaults on the Windows filesystem cross the WSL2 boundary on every Docker file
# access (via the 9P protocol), making startup sync and daily maintenance jobs
# significantly slower. Detect this and offer a one-time migration into WSL2.

function Get-DotEnvValue([string]$Path, [string]$Key) {
    $line = Select-String -Path $Path -Pattern "^\s*$([regex]::Escape($Key))\s*=\s*(.+)" | Select-Object -First 1
    if (-not $line) { return $null }
    return $line.Matches[0].Groups[1].Value.Trim().Trim('"').Trim("'")
}

function Set-DotEnvValue([string]$Path, [string]$Key, [string]$Value) {
    $lines = Get-Content $Path
    $replaced = $false
    $updated = $lines | ForEach-Object {
        if ($_ -match "^\s*$([regex]::Escape($Key))\s*=") { $replaced = $true; "$Key=$Value" }
        else { $_ }
    }
    if (-not $replaced) { $updated += "$Key=$Value" }
    Set-Content -Path $Path -Value $updated
}

$currentVault = Get-DotEnvValue -Path $envFile -Key 'HOST_VAULT_PATH'
$ackFile      = "$PSScriptRoot\.vault-ack"

# --rechoose: wipe the saved preference so the prompt appears again this run.
if ($args -contains '--rechoose' -and (Test-Path $ackFile)) {
    Remove-Item $ackFile -Force
    Write-Host "  Cleared saved vault preference — will re-prompt."
    Write-Host ""
}

# Only prompt for Windows drive paths (C:\..., D:\...). WSL2 UNC paths
# (\\wsl$\... or \\wsl.localhost\...) are already on the fast side.
# Skipped if the user previously chose "keep in place" (.vault-ack exists).
if ($currentVault -and $currentVault -notmatch '^\\\\wsl[\$\.]' -and -not (Test-Path $ackFile)) {
    Write-Host ""
    Write-Host "  [!] Vault is on the Windows filesystem:"
    Write-Host "      $currentVault"
    Write-Host ""
    Write-Host "      Docker accesses it through the WSL2 bridge, which is slow for"
    Write-Host "      large vaults (slow startup sync, slow daily maintenance jobs)."
    Write-Host ""
    Write-Host "  [1] Move vault into WSL2  (faster — one-time setup, recommended)"
    Write-Host "  [2] Keep in place         (slower startup, fine for small vaults)"
    Write-Host ""
    $vaultChoice = (Read-Host "  Choice [1/2]").Trim()

    if ($vaultChoice -eq '1') {
        if (-not (Get-Command wsl -ErrorAction SilentlyContinue)) {
            Write-Warning "WSL not found on PATH. Continuing with current vault path."
        } else {
            # wsl --list --quiet outputs UTF-16LE with null bytes on some builds — strip them.
            $distros = wsl --list --quiet 2>$null |
                ForEach-Object { ($_ -replace "`0", "").Trim() } |
                Where-Object { $_ -ne '' }

            # Skip Docker-internal distros — they don't mount Windows drives at /mnt.
            $distros = $distros | Where-Object { $_ -notmatch '^docker-desktop' }

            if (-not $distros) {
                Write-Warning "No suitable WSL2 distribution found (only docker-desktop found). Continuing with current vault path."
            } else {
                $distro  = $distros | Select-Object -First 1
                $wslHome = (wsl --distribution $distro --exec sh -c 'echo $HOME' 2>$null).Trim() -replace "`0", ""

                # Convert Windows drive path  C:\Foo\Bar  →  /mnt/c/Foo/Bar
                $drive  = $currentVault[0].ToString().ToLower()
                $rest   = $currentVault.Substring(2).Replace('\', '/')
                $srcWsl = "/mnt/$drive$rest"

                # Mirror original structure under WSL home:
                #   C:\Users\ACER\Desktop\NewLife  →  ~/Desktop/NewLife
                # Strip the leading drive+separator and any Users/<name> prefix so the
                # suggested path is readable without repeating the machine account name.
                $pathParts  = $rest.TrimStart('/') -split '/'
                # Drop leading "Users/<username>" if present (common Windows layout)
                if ($pathParts.Count -ge 2 -and $pathParts[0] -ieq 'Users') {
                    $pathParts = $pathParts | Select-Object -Skip 2
                }
                $mirroredSuffix = $pathParts -join '/'
                $suggestedDest  = "$wslHome/$mirroredSuffix"

                Write-Host ""
                Write-Host "  Distro  : $distro"
                Write-Host "  Source  : $srcWsl"
                Write-Host "  Default : $suggestedDest"
                $destRaw   = (Read-Host "  Destination in WSL2 [Enter to accept default]").Trim()
                $destLinux = if ($destRaw) { $destRaw } else { $suggestedDest }

                Write-Host ""
                Write-Host "  Copying vault — this may take a moment for large vaults..."
                wsl --distribution $distro --exec sh -c "mkdir -p '$destLinux' && cp -r '$srcWsl/.' '$destLinux/'"

                if ($LASTEXITCODE -eq 0) {
                    # Windows UNC path that Docker Desktop and Obsidian can both open.
                    $destUNC = "\\wsl`$\$distro" + ($destLinux.Replace('/', '\'))
                    Set-DotEnvValue -Path $envFile -Key 'HOST_VAULT_PATH' -Value $destUNC

                    Write-Host ""
                    Write-Host "  Vault copied to (WSL2 path) : $destLinux"
                    Write-Host "  .env updated to             : $destUNC"
                    Write-Host ""
                    Write-Host "  To repoint Obsidian:"
                    Write-Host "    Manage vaults → Open folder as vault → paste this path:"
                    Write-Host "    $destUNC"
                    Write-Host ""
                    Write-Host "  Your original vault at $currentVault is untouched."
                    Write-Host "  Delete it manually once Obsidian is repointed."
                    Write-Host ""
                    Read-Host "  Press Enter to continue starting the app"
                } else {
                    Write-Warning "Copy failed. Check that WSL2 is running. Continuing with current path."
                }
            }
        }
    } else {
        Set-Content -Path $ackFile -Value "keep-on-windows $(Get-Date -Format 'yyyy-MM-dd')"
        Write-Host ""
        Write-Host "  Keeping vault on Windows filesystem. Startup may be slower."
        Write-Host "  (Run with --rechoose to change this later.)"
        Write-Host ""
    }
}

# TLS is handled by Cloudflare at its edge — no local certs needed. The app is
# served only over the tunnel (frontend:8081, docker-network only).

# Enable the Cloudflare tunnel only when a token is present in .env. The
# cloudflared service is behind the "tunnel" compose profile; starting it
# without a token would crash-loop (restart: unless-stopped), so we detect it.
$composeArgs = @("-f", "$PSScriptRoot\docker-compose.yml")
if (Select-String -Path $envFile -Pattern '^\s*CLOUDFLARE_TUNNEL_TOKEN\s*=\s*\S' -Quiet) {
    Write-Host "Cloudflare tunnel token found in .env — starting with the 'tunnel' profile."
    $composeArgs += @("--profile", "tunnel")
} else {
    Write-Host "No CLOUDFLARE_TUNNEL_TOKEN in .env — starting without the Cloudflare tunnel."
}

# The host-wrapper listens here (host-wrapper/main.py: PORT env, default 5001). A
# crashed previous run can leave a stale wrapper holding this port, which makes the
# new wrapper fail to bind and silently keep serving old code — so we free it.
$wrapperPort = 5001
$portLine = Select-String -Path $envFile -Pattern '^\s*PORT\s*=\s*(\d+)' | Select-Object -First 1
if ($portLine) { $wrapperPort = [int]$portLine.Matches[0].Groups[1].Value }

# Shared cleanup — run at startup (clear a crashed previous run) AND on exit, so
# state is identical every launch: no orphaned containers, nothing on the port.
function Invoke-Cleanup {
    param([string]$Phase)
    Write-Host "[$Phase] Removing old containers..."
    docker compose @composeArgs down --remove-orphans 2>$null

    # Kill whatever still holds the wrapper port (a stale host-wrapper). $PID is the
    # current shell — never kill ourselves. (Avoid the reserved name $pid for the loop.)
    try {
        $conns = Get-NetTCPConnection -LocalPort $wrapperPort -State Listen -ErrorAction SilentlyContinue
        foreach ($procId in ($conns.OwningProcess | Select-Object -Unique)) {
            if ($procId -and $procId -ne 0 -and $procId -ne $PID) {
                Write-Host "[$Phase] Stopping stale process on port $wrapperPort (PID $procId)..."
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            }
        }
    } catch {
        # Get-NetTCPConnection unavailable — skip port cleanup, containers still cleaned.
    }
}

# Cleanup BEFORE we start, in case a previous run was killed without its finally block.
Invoke-Cleanup -Phase "startup"

# Start the host-wrapper (the LLM bridge) on the host — it runs OUTSIDE docker on
# :5001, reachable from the containers via host.docker.internal:5001. It's not a
# compose service because it needs host-side access to local LLM tooling/creds.
# Without it, image captioning + note synthesis silently skip ("host wrapper
# unreachable"). Launches in its own window so its logs are visible; killed below.
$wrapperDir = "$PSScriptRoot\host-wrapper"
$venvDir    = "$wrapperDir\.venv"
$venvPython = "$venvDir\Scripts\python.exe"
$reqFile    = "$wrapperDir\requirements.txt"
$reqStamp   = "$venvDir\.req-hash"   # records which requirements.txt was last installed
$wrapper    = $null

# Create the host-wrapper venv ONCE (gitignored, reused on every later start).
if (-not (Test-Path $venvPython)) {
    Write-Host "Creating host-wrapper venv (one-time) ..."
    & python -m venv $venvDir
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "venv creation failed — is 'python' on PATH? Continuing without host-wrapper (LLM features will skip)."
        $venvPython = $null
    }
}

# Only (re)install deps when requirements.txt has changed since the last install.
if ($venvPython) {
    $reqHash = (Get-FileHash $reqFile -Algorithm SHA256).Hash
    $needInstall = (-not (Test-Path $reqStamp)) -or ((Get-Content $reqStamp -Raw).Trim() -ne $reqHash)
    if ($needInstall) {
        Write-Host "Installing host-wrapper deps (first run or requirements changed) ..."
        & $venvPython -m pip install -q -r $reqFile
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "host-wrapper pip install failed. Continuing without it (LLM features will skip)."
            $venvPython = $null
        } else {
            Set-Content -Path $reqStamp -Value $reqHash
        }
    } else {
        Write-Host "host-wrapper deps already installed — skipping pip."
    }
}

# Start the wrapper from the venv's interpreter.
if ($venvPython) {
    $wrapper = Start-Process -FilePath $venvPython -ArgumentList "main.py" -WorkingDirectory $wrapperDir -PassThru
    Write-Host "host-wrapper started (PID $($wrapper.Id))."
}

try {
    docker compose @composeArgs up --build
} finally {
    # Stop the wrapper we launched first (its own window), then run the shared
    # cleanup to remove containers and free the port — same end state as startup.
    if ($wrapper -and -not $wrapper.HasExited) {
        Write-Host "Stopping host-wrapper (PID $($wrapper.Id))..."
        Stop-Process -Id $wrapper.Id -Force -ErrorAction SilentlyContinue
    }
    Invoke-Cleanup -Phase "shutdown"
}
