# Host Filesystem / Vault Picker Flows

Files: HostFsController.java, host-wrapper/main.py (fs_list, get_vault_path, put_vault_path, _write_env_vault, _trigger_recreate), frontend SettingsPage.jsx, FolderPicker.jsx, api/notes.js

The settings UI lets the user **browse their real machine and switch which folder is
mounted as the vault** — without hand-typing a path. The catch: the backend runs in
Docker and only sees `/vault` (a bind mount from `${HOST_VAULT_PATH}` in `.env`, fixed
at container start). The only process that can see the host's real drives is the
**host-wrapper** (runs on the host, outside Docker, started by `start.ps1`). So every
host-filesystem action is proxied to it.

---

## Browse the host filesystem

`FolderPicker` (mode=host) → `notes.js fetchHostChildren(path)` → `GET /api/host/fs?path=`
→ nginx strips `/api/` → `HostFsController.listDir()` → `GET {wrapper.url}/fs/list` →
`main.py fs_list()` → returns `{ path, parent, dirs:[{path,name}] }`.

- Empty path → drive roots on Windows (`_list_drives()`), `/` elsewhere.
- `parent == null` → nowhere further up (drive list / posix root).
- Paths normalised to forward slashes (`_norm`).
- To change what's listed (e.g. hide dotfiles): `main.py fs_list()`.

## Browse inside the mounted vault (resources folder)

`FolderPicker` (mode=vault) → `notes.js fetchChildren(path)` → existing `/children`
endpoint (`NotesController` → `FileRepository.getDirectChildren`). Capped at the vault
root: `SettingsPage.loadVaultDir()` sets `parent=null` once `current === vaultRoot`
(root learned via `fetchChildren(null)` on mount). No restart — resourcePath is a normal
saveable setting (PUT /settings). See [../settings/FLOWS.md](../settings/FLOWS.md).

## Switch the vault (the expensive path)

`FolderPicker` confirm → `SettingsPage.switchVault(path)` → `saveVaultHostPath(path)` →
`PUT /api/host/vault` → `HostFsController.setVault()` → `PUT {wrapper.url}/vault-path` →
`main.py put_vault_path()`:
1. validate `path` is a directory (on the host)
2. `_write_env_vault()` — rewrite the `HOST_VAULT_PATH=` line in repo-root `.env`, preserving every other line
3. `_trigger_recreate()` — detached `docker compose -f <repo>/docker-compose.yml up -d`

`up -d` (NOT `restart`) is required: a bind-mount change only takes effect on container
**recreation**. The wrapper survives (it's not a container); the obsidian backend +
embedder are recreated.

Because the recreate kills the very backend proxying the PUT, the response may never
arrive — by design. The UI fires it and forgets: `SettingsPage` shows `<RecreatingOverlay>`,
which polls `notes.js pingHealth()` (GET /settings; 2xx **or 401/403** = backend answered;
502/503/504/network-error = still down) and `window.location.reload()`s once it sees
down→up, or after a ~30s fallback (15 ticks).

To change the recreate command: `main.py _trigger_recreate()`.
To change which services recreate: they're whatever interpolates `${HOST_VAULT_PATH}` in
`docker-compose.yml` (backend + embedder).

---

## Technology Notes

- **The wrapper must be running and have `docker` on PATH** (Docker Desktop up). If it's
  unreachable, `HostFsController` returns 503 `{"error":"host helper unreachable"}` and
  the UI disables the vault "Browse…" button (`hostError`). The wrapper is a host process,
  not a compose service — `start.ps1` launches it; if you run `docker compose up` directly,
  the picker's host endpoints are dead.
- **Auth**: `SecurityConfig` is `anyRequest().authenticated()`, so `/host/**` (including the
  destructive PUT) requires a session. The wrapper itself is **unauthenticated** on
  `host.docker.internal:5001` — it trusts that only the backend container reaches it. Anything
  on the host that can hit `:5001` can rewrite `.env` and recreate containers. Acceptable for a
  single-user desktop-as-server; would not be on a shared host.
- **Sessions are in-memory** (see settings FLOWS): recreating the backend wipes the session, so
  after a vault switch the reloaded page lands on the login modal. Expected, not a bug.
- **No path translation for the vault**: the host path is stored verbatim in `.env`; the
  in-container `vaultPath` stays `/vault`. The picker shows the host path purely for display.
- **`HOST_VAULT_PATH` with spaces**: written unquoted; Docker Desktop handles
  `C:/Path With Spaces/Vault:/vault`. Windows drive-letter colons are fine.
- **start.ps1 races**: `start.ps1` runs `docker compose up --build` in the foreground; a
  separate `up -d` from the wrapper reconciles the same compose project, but may detach the
  foreground log stream. Functional, cosmetic only.

---

## Change Index

| Thing to change | Where |
|---|---|
| Host directory listing (filters, drives) | `host-wrapper/main.py fs_list()`, `_list_drives()` |
| Vault `.env` rewrite logic | `host-wrapper/main.py _write_env_vault()` |
| Recreate command / which services | `host-wrapper/main.py _trigger_recreate()` |
| Proxy timeouts / endpoints | `HostFsController` |
| Picker UI (rows, breadcrumb, up) | `frontend/.../organisms/FolderPicker.jsx` + `.module.css` |
| Reconnect/reload behaviour after switch | `SettingsPage.RecreatingOverlay`, `notes.js pingHealth()` |
| Vault-root cap for in-vault picker | `SettingsPage.loadVaultDir()` |
| Wrapper base URL | `wrapper.url` (application.properties / `WRAPPER_URL` env) |
