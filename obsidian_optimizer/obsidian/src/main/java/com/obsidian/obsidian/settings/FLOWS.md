# Settings Domain Flows

Files: SettingsController.java, SettingsRepository.java

---

## app_settings Table

```sql
app_settings(key TEXT PRIMARY KEY, value TEXT)
```

Seeded on first boot from env vars via `ON CONFLICT DO NOTHING`.

| Key | Default | Source |
|---|---|---|
| `vaultPath` | `$VAULT_PATH` | env var |
| `resourcePath` | `$IMAGE_PATH` or `$VAULT_PATH/resources/images` | env var (legacy — multi-dir serving now uses `vaultPath/resources/`) |
| `reviewPageSize` | `20` | hardcoded |
| `startupSyncMode` | `"blocking"` | hardcoded |
| `maxDailyReviews` | `30` | hardcoded |
| `bankruptcyLimit` | `200` | hardcoded — mass-lapse gate (BankruptcyService pass 2) |
| `chronicNeglectDays` | `7` | hardcoded — per-note lapse threshold (BankruptcyService pass 1) |
| `chronoLastRunDate` | `""` | internal |
| `ollamaEmbedModel` | `mixedbread-ai/mxbai-embed-large-v1` | hardcoded (display-only — real model is `EMBED_MODEL` env) |
| `flashcardsEnabled` | `"true"` | hardcoded — review UI mode: tests (on) vs self-rated slideshow (off) |
| `syncEnabled` | `"false"` | hardcoded — gates the scheduled Drive upload cron only |
| `subscriptionsPollIntervalMs` | `21600000` (6h; was 1h — seed-once, existing rows keep their value) | hardcoded — not in GET/PUT /settings; see [../tracks/FLOWS.md](../tracks/FLOWS.md) Subscriptions |
| `syncClientId` / `syncClientSecret` | `$GOOGLE_OAUTH_CLIENT_ID` / `_SECRET` | `seedIfBlank` env seed (fills while blank, UI wins after); Settings UI |
| `sync.oauth.redirect_uri` | `$GOOGLE_OAUTH_REDIRECT_URI` | `seedIfBlank`; blank = derive from request origin |
| `syncPassphrase` | `$SYNC_PASSPHRASE` | `seedIfBlank` env seed; Settings UI afterwards (save → key re-derived) |
| `sync.drive.folder_id` | `$GOOGLE_DRIVE_FOLDER_ID` | env seed; auto-filled in OAuth mode (see [../sync/FLOWS.md](../sync/FLOWS.md)) |
| `sync.refresh_token` / `sync.account_email` | `""` | internal — written by the OAuth connect flow |

To force re-seed: `DELETE FROM app_settings;` → restart

---

## GET /settings

`SettingsController.getSettings()` → `SettingsRepository.get*()`  
Returns `{ vaultPath, resourcePath, reviewPageSize, startupSyncMode, maxDailyReviews, bankruptcyLimit, chronicNeglectDays, embedModel, flashcardsEnabled, syncEnabled, syncClientId, syncClientSecretSet, syncPassphraseSet }`  
Sync secrets are **write-only**: the response carries `…Set` booleans, never the values.  
Requires session auth (was public until the security-hardening pass — it leaked the vault path).

---

## PUT /settings

`SettingsController.updateSettings(UpdateSettingsRequest)` — partial update, all fields optional.

Field validation:
- `startupSyncMode` — must be `"blocking"` or `"async"`
- `reviewPageSize` — `[1, 500]`
- `maxDailyReviews`, `bankruptcyLimit`, `chronicNeglectDays` — `>= 1`, no upper bound
- `syncClientSecret` / `syncPassphrase` — **blank = leave as is** (the form shows a
  saved-marker placeholder, not the value). Passphrase save → `VaultEncryptionService.reload()`;
  client id/secret save → `DriveService.reset()` (next Drive call rebuilds the client).

`vaultPath` in PUT body → 400 "use /api/vaults" (`SettingsController.updateSettings()`). Vault switching REMOVED pending multi-vault (VAULTS_PLAN.md M0); `FileRepository.updateVaultPath` / `NoteIndexRepository.forceResync` deleted.

> **Note (settings UI):** the settings page no longer edits `vaultPath` via this endpoint.
> The in-container `vaultPath` stays `/vault`; the user-facing "vault folder" is the host
> path in `.env` (`HOST_VAULT_PATH`), changed by hand (switching removed pending multi-vault). `resourcePath` is still a normal PUT /settings field, now with a browse button.
> See [../hostfs/FLOWS.md](../hostfs/FLOWS.md). This PUT path still exists for the agent/internal API.

---

## Technology Notes

- **Sessions**: Spring Security in-memory session — settings survive app restart (persisted in `app_settings`), but session cookies do not.
- **Settings are per-instance**: no multi-node sharing. If running behind a load balancer, each node has its own `app_settings`.

---

## Change Index

| Thing to change | Where |
|---|---|
| Add a new setting | `SettingsRepository` typed getter + `SettingsController.SettingsResponse` + `UpdateSettingsRequest` + `updateSettings()` |
| Default value for any key | `SettingsRepository` seed block |
| `vaultPath` rejection (400) | `SettingsController.updateSettings()` |
| Validate a new field | `SettingsController.updateSettings()` validation block |
