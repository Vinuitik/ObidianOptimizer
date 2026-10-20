# Vault (client) Flows

Files: activeVault.js, useVaults.js, ../api/vaults.js, ../components/molecules/VaultSwitcher.jsx, ../pages/VaultsPanel.jsx, ../pwa/db.js, ../pwa/outbox.js, ../pwa/warmMedia.js, ../utils/media.js, ../utils/noteMedia.js, ../../public/sw.js, ../main.jsx
Backend contract: `obsidian_optimizer/.../vaults/FLOWS.md`. Plan: `architecture_plans/VAULTS_PLAN.md` (M7, M4b frontend).

## Boot + header
`main.jsx` → `installVaultFetch()` (wraps `window.fetch`) → `bootVault()` (GET /api/vaults, 3s cap, adopts the `active` row → `setActiveVaultId()`; offline keeps stored id) → id changed ⇒ `location.reload()` once, else render.
Every `/api/**` call (not `/api/vaults**`, which the server exempts) → `vaultFetch` adds `X-Vault-Id: getActiveVaultId()`.
Server answers `409 {error:'vault_mismatch', activeVaultId}` (another tab/device switched) → `setActiveVaultId(activeVaultId)` → reload.
To change: `activeVault.js vaultFetch()` / `bootVault()`.

## Switch
`VaultSwitcher` (desktop `NavBar`, `pwa/TopBar`; hidden with <2 non-archived vaults) or Settings ▸ Vaults "Switch to" → `api/vaults.js switchVault(id)` → PUT /api/vaults/active → `setActiveVaultId(id)` (localStorage + `obsopt-meta` IDB for the SW; also drops `obsOpt_reviewSession`, `obsOpt_flashSession`, `obs.failedCaptureCount`) → `location.reload()`.

## Register (Settings ▸ Vaults)
`VaultsPanel` → `FolderPicker` over `fetchHostChildren` (host-fs) → confirm box (~20s downtime) → `registerVault(name, hostPath)` (202) → `waitUntilReachable()` (5s, then GET /vaults every 2s ≤45×) → reload list. Rename/archive/restore: PATCH / POST `/vaults/{id}[/archive|/restore]`.
Drive sync: `SettingsPage DriveSyncPanel` acts on the ACTIVE vault only (server sync endpoints take no vault id) and its description names it; inactive vaults show "switch to this vault to see or change it".

## Per-vault offline state
- IDB: vault 1 `obsopt-offline`, others `obsopt-offline-v<id>` (`dbNameFor`). `db.js openDB()` re-opens if the id changed. reviewNotes / assignments / outbox / meta (incl. `driveCreds` = the PWA phone link, `lastSync`, `cachedNoteNames`, `inboxItems`) are therefore per vault: linking the phone, offline export and Drive mailbox (`pwa/setup.js`, `drivePull.js`, `mailbox.js`) use the active vault's server identity (`/api/pwa/setup` is per-vault server-side) and never see another vault's data. A freshly switched vault starts unlinked until Link/Sync is run there.
- Outbox: `addToOutbox` stamps `vaultId`; `outbox.flush()` skips entries of another vault. The SW (`sw.js enqueueOutbox`) reads the active id from `obsopt-meta` (`kv/activeVaultId`) and writes into that vault's DB.
- Media cache: `obsopt-media` / `obsopt-media-v<id>` (`mediaCacheNameFor`; `warmMedia` reads it lazily, `sw.js mediaCacheNameFor` mirrors it).
- Media URLs: `mediaPrefix('vault-media'|'workspace')` → `/vault-media/` (vault 1) or `/vault-media/<id>/` (`media.mediaUrl`, `noteMedia.vaultMediaUrl`, `SourceSplicePanel.mediaUrl`, `ResourcePanel.Viewer`). `/pdf-page` and `/media-rendition` resolve the active vault server-side.

## Technology Notes
- The active id is in localStorage (per origin): a second tab on another vault gets a 409 on its next call and reloads. SW and page agree only via `obsopt-meta`; if that write fails the SW falls back to vault 1 (a share-target capture could then land in vault 1's outbox while vault N is active; it replays with `X-Vault-Id: 1` and the server rejects with 409 until vault 1 is active).
- `window.fetch` is patched globally (one chokepoint instead of editing ~45 call sites); code that builds `Request` objects or absolute URLs bypasses it. Only `/api/` prefixed relative URLs get the header.
- Boot blocks first render up to 3s waiting for GET /vaults.
- Old vault-1 IDB/caches are unchanged (no migration); non-primary IDBs and caches are never garbage-collected when a vault is archived.
- Registration downtime polling is best effort: if the app does not answer within ~95s the panel shows an error but the registration may still have succeeded.

## Change Index
| Thing | Where |
|---|---|
| Active id read/write, names, media prefix | `activeVault.js` |
| Header + 409 reload | `activeVault.js vaultFetch()`, `installVaultFetch()` (called in `main.jsx`) |
| Boot sync with server | `activeVault.js bootVault()` |
| Vault REST client | `api/vaults.js` |
| Switcher UI | `components/molecules/VaultSwitcher.jsx` (mounted in `NavBar.jsx`, `pwa/TopBar.jsx`) |
| Settings ▸ Vaults | `pages/VaultsPanel.jsx` (`waitUntilReachable`) |
| Per-vault IDB name | `activeVault.dbNameFor` ← `pwa/db.js`, `public/sw.js` |
| Outbox vault stamp/filter | `pwa/db.js addToOutbox`, `pwa/outbox.js flush` |
| Local keys cleared on switch | `activeVault.js VAULT_SCOPED_LS` |
| Tests | `activeVault.test.js`, `pwa/dbVault.test.js`, `pages/VaultsPanel.test.jsx` |
