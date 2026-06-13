$envFile = "$PSScriptRoot\.env"
if (-not (Test-Path $envFile)) {
    Write-Error ".env not found. Copy .env.example to .env and set HOST_VAULT_PATH to your vault directory."
    exit 1
}

# Generate a self-signed TLS cert for the nginx edge on first run (gitignored).
# Uses dockerized openssl so nothing needs to be installed on the host.
$certDir = "$PSScriptRoot\certs"
if (-not (Test-Path "$certDir\selfsigned.crt")) {
    Write-Host "Generating self-signed TLS certificate in .\certs ..."
    New-Item -ItemType Directory -Force $certDir | Out-Null
    docker run --rm -v "${certDir}:/certs" alpine/openssl req -x509 -nodes -newkey rsa:2048 `
        -keyout /certs/selfsigned.key -out /certs/selfsigned.crt -days 825 `
        -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Certificate generation failed — is Docker running?"
        exit 1
    }
}

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

# Start the host-wrapper (the LLM bridge) on the host — it runs OUTSIDE docker on
# :5001, reachable from the containers via host.docker.internal:5001. It's not a
# compose service because it needs host-side access to local LLM tooling/creds.
# Without it, image captioning + note synthesis silently skip ("host wrapper
# unreachable"). Launches in its own window so its logs are visible; killed below.
$wrapperDir = "$PSScriptRoot\host-wrapper"
$wrapper = $null
Write-Host "Installing host-wrapper deps and starting it on :5001 ..."
& python -m pip install -q -r "$wrapperDir\requirements.txt"
if ($LASTEXITCODE -ne 0) {
    Write-Warning "host-wrapper pip install failed — is 'python' on PATH? Continuing without it (LLM features will skip)."
} else {
    $wrapper = Start-Process -FilePath python -ArgumentList "main.py" -WorkingDirectory $wrapperDir -PassThru
    Write-Host "host-wrapper started (PID $($wrapper.Id))."
}

try {
    docker compose @composeArgs up --build
} finally {
    Write-Host "Shutting down containers..."
    docker compose @composeArgs down
    if ($wrapper -and -not $wrapper.HasExited) {
        Write-Host "Stopping host-wrapper (PID $($wrapper.Id))..."
        Stop-Process -Id $wrapper.Id -Force -ErrorAction SilentlyContinue
    }
}
