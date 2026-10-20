#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$ROOT_DIR/.env"
STOP_FLAG="$ROOT_DIR/.stopped"

# "Am I the obsidian-optimizer.service process?" — checked via cgroup membership, NOT
# $INVOCATION_ID: terminals launched from a systemd user session (VS Code, GNOME
# Terminal) inherit their own INVOCATION_ID, which made a by-hand run look supervised.
UNDER_UNIT=""
grep -q 'obsidian-optimizer\.service' /proc/self/cgroup 2>/dev/null && UNDER_UNIT=1

# "Restart unless stopped": linux_scripts/stop.sh drops this flag. Under systemd a
# flagged start (restart or reboot) exits 42, which the unit's
# RestartPreventExitStatus=42 treats as "stay down". Run by hand, start.sh is an
# explicit "bring it back up", so it clears the flag and carries on.
#
# Run by hand from a shell, the stack must survive that shell closing (a foreground
# start.sh gets reaped with its terminal). So a by-hand run clears the flag and hands
# off to the systemd unit — supervised, restart-on-crash, logs in journalctl — then
# returns. Fallback when passwordless sudo isn't available: re-exec detached (setsid
# + nohup), logging to ~/obsidian_start.log. OBS_DETACHED marks that re-exec so it
# doesn't hand off again.
if [[ -z "$UNDER_UNIT" && -z "${OBS_DETACHED:-}" ]]; then
    rm -f "$STOP_FLAG"
    if sudo -n systemctl restart obsidian-optimizer 2>/dev/null; then
        echo "Handed off to systemd (obsidian-optimizer.service) — safe to close this shell."
        echo "Logs: journalctl -u obsidian-optimizer -f"
    else
        OBS_DETACHED=1 setsid nohup bash "$0" > "$HOME/obsidian_start.log" 2>&1 < /dev/null &
        echo "systemd hand-off unavailable — started detached (PID $!). Logs: ~/obsidian_start.log"
    fi
    exit 0
fi

if [[ -e "$STOP_FLAG" ]]; then
    if [[ -n "$UNDER_UNIT" ]]; then
        echo "Stopped via stop.sh ($STOP_FLAG exists) — staying down. Run linux_scripts/start.sh by hand to resume." >&2
        exit 42
    fi
    rm -f "$STOP_FLAG"
fi

if [[ ! -f "$ENV_FILE" ]]; then
    echo "Error: .env not found. Copy .env.example to .env and set HOST_VAULT_PATH to your vault directory." >&2
    exit 1
fi

get_env_value() {
    grep -E "^\s*${1}\s*=" "$ENV_FILE" | head -1 | sed "s/^\s*${1}\s*=\s*//" | tr -d "\"'"
}

COMPOSE_ARGS=("-f" "$ROOT_DIR/docker-compose.yml")
[[ -f "$ROOT_DIR/docker-compose.vaults.yml" ]] && COMPOSE_ARGS+=("-f" "$ROOT_DIR/docker-compose.vaults.yml")  # extra vaults (host-wrapper POST /vaults)

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

# Derive VAULT_RESOURCES_PATH and WORKSPACE_HOST_PATH from HOST_VAULT_PATH if not set.
# Both are just subfolders of the vault; no reason to force the user to spell them out.
HOST_VAULT_PATH=$(get_env_value HOST_VAULT_PATH)
if [[ -z "$HOST_VAULT_PATH" ]]; then
    echo "Error: HOST_VAULT_PATH is not set in .env." >&2
    exit 1
fi

# Reject a non-absolute / Windows-style path. Without this guard a leftover Windows
# value (e.g. C:/Users/...) has no leading '/', so the mkdir below treats it as
# RELATIVE and silently builds a junk ./C:/Users/... tree under the cwd instead of
# erroring. On Linux the vault path must be a real absolute path like /home/you/vault.
if [[ "$HOST_VAULT_PATH" != /* || "$HOST_VAULT_PATH" == *:* || "$HOST_VAULT_PATH" == *\\* ]]; then
    echo "Error: HOST_VAULT_PATH ('$HOST_VAULT_PATH') is not an absolute Linux path." >&2
    echo "       It looks like a Windows path. Set it to e.g. /home/$USER/Desktop/NewLife in .env." >&2
    exit 1
fi

mkdir -p "$HOST_VAULT_PATH/resources" "$HOST_VAULT_PATH/_workspace"

cleanup() {
    local phase="$1"
    echo "[$phase] Removing old containers..."
    docker compose "${COMPOSE_ARGS[@]}" down --remove-orphans 2>/dev/null || true

    # `down` can report success while a container from a just-crashed prior
    # instance is still lingering in the daemon (seen 2026-07-25: a stale
    # postgres-1 survived `down` and made the next `up` fail with "Conflict:
    # name already in use", crashing the new instance too). Force-remove
    # anything still sitting under this project before continuing.
    local leftover
    leftover=$(docker compose "${COMPOSE_ARGS[@]}" ps -aq 2>/dev/null || true)
    if [[ -n "$leftover" ]]; then
        echo "[$phase] Force-removing leftover container(s) 'down' didn't clear..."
        # shellcheck disable=SC2086
        docker rm -f $leftover 2>/dev/null || true
    fi

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

# Start the wrapper under a respawn loop so a crash auto-restarts it. systemd
# only watches the whole service, and start.sh then blocks on `docker compose up`
# below — so without this loop a wrapper crash would stay dead until a full
# service restart. setsid puts the loop in its own process group, so the EXIT
# trap can signal the loop AND its python child together (negative PID = group).
if [[ -n "$VENV_PYTHON" ]]; then
    setsid bash -c '
        cd "$1" || exit 1
        while true; do
            "$2" main.py
            echo "[host-wrapper] exited (code $?) — restarting in 3s ..." >&2
            sleep 3
        done
    ' _ "$WRAPPER_DIR" "$VENV_PYTHON" &
    WRAPPER_PID=$!
    echo "host-wrapper started under respawn loop (PID/PGID $WRAPPER_PID)."
fi

trap '
    if [[ -n "$WRAPPER_PID" ]]; then
        echo "Stopping host-wrapper respawn loop (PGID $WRAPPER_PID)..."
        # Kill the whole process group so the loop cannot respawn a new python
        # after we stop it; fall back to a plain kill, then cleanup reaps by port.
        kill -TERM -"$WRAPPER_PID" 2>/dev/null || kill "$WRAPPER_PID" 2>/dev/null || true
    fi
    cleanup "shutdown"
' EXIT

# Build separately from up: a broken build (e.g. a compile error left in the
# working tree at reboot) must not keep the whole site down. On build failure
# we warn loudly and start the last successfully-built images instead — the
# error stays visible in `journalctl -u obsidian-optimizer`.
if ! docker compose "${COMPOSE_ARGS[@]}" build; then
    echo "WARNING: image build FAILED — starting last-good images instead." >&2
    echo "         Fix the build, then: systemctl restart obsidian-optimizer" >&2
fi

# Extensions: redeploy only when extension/ changed since the last successful deploy
# (content hash in .ext-deployed-hash). Gated because deploy-extension.sh bumps the
# version + re-signs via AMO every run — unconditional would publish a new Firefox
# version on every restart. Backgrounded + non-fatal: signing takes minutes and a
# failure (AMO down, bad creds) must not keep the site down. The hash is taken AFTER
# the deploy so it includes the bumped manifest version. Chrome zip rides along.
EXT_STAMP="$ROOT_DIR/.ext-deployed-hash"
ext_hash() {
    find "$ROOT_DIR/extension" -type f -not -path '*/node_modules/*' -print0 \
        | sort -z | xargs -0 sha256sum | sed "s#$ROOT_DIR/##" | sha256sum | awk '{print $1}'
}
if [[ "$(ext_hash)" != "$(cat "$EXT_STAMP" 2>/dev/null)" ]]; then
    (
        echo "extension/ changed since last deploy — publishing ..."
        if bash "$SCRIPT_DIR/deploy-extension.sh" && bash "$SCRIPT_DIR/build-chrome-extension-zip.sh"; then
            ext_hash > "$EXT_STAMP"
            echo "extension deploy done."
        else
            echo "WARNING: extension deploy FAILED — will retry on next start." >&2
        fi
    ) 2>&1 | sed -u 's/^/[extension] /' &
else
    echo "extension/ unchanged since last deploy — skipping."
fi

docker compose "${COMPOSE_ARGS[@]}" up
