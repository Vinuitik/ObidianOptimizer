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

# Force plain BuildKit output. The animated TTY progress doesn't render in some
# PowerShell hosts (shows nothing during the build, then clears on Ctrl-C). Set
# via env var, not `--progress` — older Compose rejects that flag on `up`.
$env:BUILDKIT_PROGRESS = "plain"

try {
    # Plain build output appends one persistent line per step so the build is
    # visible; runtime container logs then stream as usual while attached.
    docker compose @composeArgs up --build
} finally {
    Write-Host "Shutting down containers..."
    docker compose @composeArgs down
}
