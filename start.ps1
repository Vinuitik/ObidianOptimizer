$envFile = "$PSScriptRoot\.env"
if (-not (Test-Path $envFile)) {
    Write-Error ".env not found. Copy .env.example to .env and set HOST_VAULT_PATH to your vault directory."
    exit 1
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
