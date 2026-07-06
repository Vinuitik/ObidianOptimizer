# PWA backend — FLOWS
Files: PwaController.java, OfflineExportService.java, MailboxConsumeService.java, ConsumedEventRepository.java

> Server side of the Drive-mediated offline PWA. Plan of record:
> `architecture_plans/DRIVE_OFFLINE_SYNC_ARCH.md`. The phone reads/writes **Google Drive**
> directly; this package (a) hands the phone credentials once, (b) EXPORTS the offline set to
> Drive, (c) CONSUMES the phone's write-back mailbox. Reuses `sync/` (DriveService,
> VaultEncryptionService, DeviceIdentityService) and `cards/ReviewService` wholesale.

## Install handshake — `PwaController` (`/api/pwa/**`, session-authed)
`GET /pwa/setup` → `{clientId, clientSecret, refreshToken, driveFolderId, passphrase, deviceId}`
from `SettingsRepository` — the phone stores these and goes server-independent (same OAuth
client → same `drive.file` namespace). 409 if Drive not connected / passphrase unset.
`POST /pwa/export` → `OfflineExportService.exportReviewBundle(200)` ("prep offline set now").
- SECURITY: returns the client secret + refresh token + vault passphrase in clear — same
  trust as the plaintext vault; that's why it's session-gated. To change what's shared:
  `PwaController.PwaSetup`.

## Export (server → phone) — `OfflineExportService`
`exportReviewBundle(limit)` → `repository.getReviewNotesPaged(0,limit)` (due notes, same as
`/api/review/bundle`) + `getText` → JSON → `VaultEncryptionService.encrypt` →
`DriveService.uploadOffline("review-bundle.json.enc")` (singleton in `_offline/`, overwritten).
Triggers (laptop is often OFF, so belt-and-suspenders): `@EventListener(ApplicationReadyEvent)`
(on boot), `@Scheduled(${offline.export.cron:0 30 3 * * *})`, and `POST /pwa/export`.
- To change the exported set: `exportReviewBundle` (add cards in P4, inbox in P5).
- To change cadence: `offline.export.cron` env / property.

## Consume (phone → server) — `MailboxConsumeService`
`consumeAll()` → `DriveService.listMailbox()` (`_mailbox/*.enc`, ts-sorted) → per file:
`downloadFile` → `VaultEncryptionService.decrypt` → JSON `{deviceId, events[]}` →
per event dispatch (P3: `grade` → `ReviewService.grade(path, Band)`), idempotent via
`ConsumedEventRepository` (eventId) → **delete the file ONLY if every event committed**
(`allCommitted`), else leave it for retry. After any apply → re-export the bundle.
Triggers: on boot + `@Scheduled(${mailbox.consume.cron:0 */15 * * * *})`. `synchronized` =
single-flight.
- Unsupported event kind → file left un-deleted (no data loss) until a newer server handles it.
- To add event kinds (file/discard/assignment): extend `consumeFile` dispatch + phone
  `pwa/mailbox.js`.

## Technology Notes
- **Delete-after-success is the correctness hinge**: a mailbox file is removed only when all
  its events committed; `eventId` dedupe (`consumed_events`) makes a reprocessed file a no-op.
- **`_offline/` + `_mailbox/` are NOT vault paths** — excluded from `DriveService.listRecursive`
  (like `_db/`), so the vault janitor/downloadAll never touch them.
- **Mailbox files are hard-deleted** (`DriveService.deleteFile`), not trashed — they're
  transient events, not vault data (unlike vault tombstones which use `trashFile`).
- **Scheduled triggers assume the laptop is up** — the on-boot listeners are the reliable
  ones for a frequently-off server; the crons are top-ups.
- **A permanently-failing grade event loops** (file never deletes). Single-user tolerable;
  add a retry cap if it bites (see arch §11).

## Change Index
| Thing | Where |
|---|---|
| What the phone gets at install | `PwaController.PwaSetup` |
| Manual "prep offline set" | `POST /api/pwa/export` → `OfflineExportService.exportReviewBundle` |
| Exported review set / limit | `OfflineExportService.exportReviewBundle` |
| Export cadence | `offline.export.cron` (default 3:30am) + on-boot listener |
| Mailbox drain + dispatch | `MailboxConsumeService.consumeFile` |
| Consume cadence | `mailbox.consume.cron` (default every 15m) + on-boot listener |
| Idempotency ledger | `ConsumedEventRepository` (`consumed_events`) |
| Drive `_offline`/`_mailbox` ops | `sync/DriveService` (`uploadOffline`, `listMailbox`, `deleteFile`) |
