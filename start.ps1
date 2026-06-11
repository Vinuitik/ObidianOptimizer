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

try {
    docker compose -f "$PSScriptRoot\docker-compose.yml" up --build
} finally {
    Write-Host "Shutting down containers..."
    docker compose -f "$PSScriptRoot\docker-compose.yml" down
}
