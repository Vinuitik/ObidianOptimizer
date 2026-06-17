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
| `bankruptcyLimit` | `200` | hardcoded |
| `chronoLastRunDate` | `""` | internal |
| `ollamaEmbedModel` | `mixedbread-ai/mxbai-embed-large-v1` | hardcoded (display-only — real model is `EMBED_MODEL` env) |
| `flashcardsEnabled` | `"true"` | hardcoded — review UI mode: tests (on) vs self-rated slideshow (off) |

To force re-seed: `DELETE FROM app_settings;` → restart

---

## GET /settings

`SettingsController.getSettings()` → `SettingsRepository.get*()`  
Returns `{ vaultPath, resourcePath, reviewPageSize, startupSyncMode, maxDailyReviews, bankruptcyLimit, embedModel, flashcardsEnabled }`  
Requires session auth (was public until the security-hardening pass — it leaked the vault path).

---

## PUT /settings

`SettingsController.updateSettings(UpdateSettingsRequest)` — partial update, all fields optional.

Field validation:
- `startupSyncMode` — must be `"blocking"` or `"async"`
- `reviewPageSize` — `[1, 500]`
- `maxDailyReviews`, `bankruptcyLimit` — `>= 1`, no upper bound

`vaultPath` change → `FileRepository.updateVaultPath()` → validates dir exists → saves to DB → sets `ROOT_FILE` → `NoteIndexRepository.forceResync()` (TRUNCATE notes + note_links → full delta sync)

> **Note (settings UI):** the settings page no longer edits `vaultPath` via this endpoint.
> The in-container `vaultPath` stays `/vault`; the user-facing "vault folder" is the host
> path in `.env` (`HOST_VAULT_PATH`), switched through the host picker which recreates the
> containers. `resourcePath` is still a normal PUT /settings field, now with a browse button.
> See [../hostfs/FLOWS.md](../hostfs/FLOWS.md). This PUT path still exists for the agent/internal API.

---

## Technology Notes

- **Sessions**: Spring Security in-memory session — settings survive app restart (persisted in `app_settings`), but session cookies do not.
- **Settings are per-instance**: no multi-node sharing. If running behind a load balancer, each node has its own `app_settings`.
- **`vaultPath` change cost**: triggers full re-index — TRUNCATE + BFS + parse all notes. Slow on large vaults.

---

## Change Index

| Thing to change | Where |
|---|---|
| Add a new setting | `SettingsRepository` typed getter + `SettingsController.SettingsResponse` + `UpdateSettingsRequest` + `updateSettings()` |
| Default value for any key | `SettingsRepository` seed block |
| `vaultPath` change handling | `FileRepository.updateVaultPath()` |
| Validate a new field | `SettingsController.updateSettings()` validation block |
