#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$ROOT_DIR/.env"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "Error: .env not found. Copy .env.example to .env and set HOST_VAULT_PATH to your vault directory." >&2
    exit 1
fi

get_env_value() {
    grep -E "^\s*${1}\s*=" "$ENV_FILE" | head -1 | sed "s/^\s*${1}\s*=\s*//" | tr -d "\"'"
}

COMPOSE_ARGS=("-f" "$ROOT_DIR/docker-compose.yml")

if grep -qE "^\s*CLOUDFLARE_TUNNEL_TOKEN\s*=\s*\S" "$ENV_FILE"; then
    echo "Cloudflare tunnel token found in .env — starting with the 'tunnel' profile."
    COMPOSE_ARGS+=("--profile" "tunnel")
else
    echo "No CLOUDFLARE_TUNNEL_TOKEN in .env — starting without the Cloudflare tunnel."
fi

WRAPPER_PORT=5001
PORT_VAL=$(get_env_value PORT)
if [[ -n "$PORT_VAL" ]]; then
    WRAPPER_PORT="$PORT_VAL"
fi

cleanup() {
    local phase="$1"
    echo "[$phase] Removing old containers..."
    docker compose "${COMPOSE_ARGS[@]}" down --remove-orphans 2>/dev/null || true

    # Kill whatever holds the wrapper port (a stale host-wrapper).
    local pids
    pids=$(lsof -ti :"$WRAPPER_PORT" 2>/dev/null || true)
    if [[ -n "$pids" ]]; then
        while IFS= read -r pid; do
            if [[ -n "$pid" && "$pid" != "$$" ]]; then
                echo "[$phase] Stopping stale process on port $WRAPPER_PORT (PID $pid)..."
                kill -9 "$pid" 2>/dev/null || true
            fi
        done <<< "$pids"
    fi
}

cleanup "startup"

WRAPPER_DIR="$ROOT_DIR/host-wrapper"
VENV_DIR="$WRAPPER_DIR/.venv"
VENV_PYTHON="$VENV_DIR/bin/python"
REQ_FILE="$WRAPPER_DIR/requirements.txt"
REQ_STAMP="$VENV_DIR/.req-hash"
WRAPPER_PID=""

# Create the host-wrapper venv ONCE (gitignored, reused on every later start).
if [[ ! -f "$VENV_PYTHON" ]]; then
    echo "Creating host-wrapper venv (one-time) ..."
    if ! python3 -m venv "$VENV_DIR"; then
        echo "Warning: venv creation failed — is 'python3' on PATH? Continuing without host-wrapper (LLM features will skip)." >&2
        VENV_PYTHON=""
    fi
fi

# Only (re)install deps when requirements.txt has changed since the last install.
if [[ -n "$VENV_PYTHON" ]]; then
    REQ_HASH=$(sha256sum "$REQ_FILE" | awk '{print $1}')
    NEED_INSTALL=true
    if [[ -f "$REQ_STAMP" && "$(cat "$REQ_STAMP")" == "$REQ_HASH" ]]; then
        NEED_INSTALL=false
    fi

    if [[ "$NEED_INSTALL" == true ]]; then
        echo "Installing host-wrapper deps (first run or requirements changed) ..."
        if ! "$VENV_PYTHON" -m pip install -q -r "$REQ_FILE"; then
            echo "Warning: host-wrapper pip install failed. Continuing without it (LLM features will skip)." >&2
            VENV_PYTHON=""
        else
            echo "$REQ_HASH" > "$REQ_STAMP"
        fi
    else
        echo "host-wrapper deps already installed — skipping pip."
    fi
fi

# Start the wrapper from the venv's interpreter.
if [[ -n "$VENV_PYTHON" ]]; then
    (cd "$WRAPPER_DIR" && "$VENV_PYTHON" main.py) &
    WRAPPER_PID=$!
    echo "host-wrapper started (PID $WRAPPER_PID)."
fi

trap '
    if [[ -n "$WRAPPER_PID" ]]; then
        echo "Stopping host-wrapper (PID $WRAPPER_PID)..."
        kill "$WRAPPER_PID" 2>/dev/null || true
    fi
    cleanup "shutdown"
' EXIT

docker compose "${COMPOSE_ARGS[@]}" up --build
