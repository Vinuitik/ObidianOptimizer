# Share-to-App + Review Reminders — Architecture & Plan

> Two PWA features layered on the Drive-offline work (DRIVE_OFFLINE_SYNC_ARCH). Decisions
> locked with the user 2026-07-06.
>   A. **Share to the app** — share a link/text/PDF from Android → ingest pipeline → note,
>      backed up to Drive; works with the laptop OFF via the existing `_mailbox/`.
>   B. **Reminders** — best-effort, phone-local, no server (Periodic Background Sync),
>      device-time 07:00–20:00, suppressed once "today is done".

---

## A. Share to the app

### A0. What already exists (reuse, don't rebuild)
- `manifest.webmanifest` `share_target` → `/share-target`; `public/sw.js handleShareTarget()`
  extracts the URL → `POST /api/capture` → `CaptureController` → ingest pipeline → note.
- **Backup is automatic**: the captured item becomes a note + `resources/…`, which the
  existing per-file sync (`sync/`) mirrors to Drive encrypted. Nothing new for "back it up".
- So the ONLINE link/text path is built (browser-unverified).

### A1. Offline share → the SAME mailbox, as a `capture` event (no new mailbox)
Decision: reuse `_mailbox/` with event `kind`. Extends P3 directly.
```
Phone offline share → outbox {kind:'capture', url|text, eventId, ts}
  → pushMailbox() already sweeps the outbox → encrypt → _mailbox/<device>-<ts>-<seq>.enc
Server MailboxConsumeService.consumeFile() → add a `capture` case:
  'capture' → CaptureService.enqueue(url|text)   (extract from CaptureController.capture)
  committed → delete file (same delete-on-success rule)
```
- Refactor: pull the capture-enqueue core out of `CaptureController.capture()` into a small
  `CaptureService` so both the controller and the mailbox consumer call it (no logic dup).
- `pushMailbox` (client): stop filtering to `grade` only — send `capture` too.
- To add the case: `MailboxConsumeService.consumeFile` dispatch + `CaptureService`.

### A2. PDFs (links/text primary, PDF supported, NO video)
Sharing a PDF *file* (not a link) is a distinct path from a shared URL:
```
manifest share_target: enctype multipart/form-data, add
  "files":[{"name":"file","accept":["application/pdf"]}]
SW handleShareTarget(): if formData has a file →
  online  → POST /api/capture/file (multipart) → store under resources/files/ → enqueue ingest
  offline → stash bytes in IDB → mailbox companion file _mailbox/<...>.pdf.enc (encrypted bytes)
            + event {kind:'capture', kind2:'pdf', resourceName, eventId}
Server consume 'capture'+pdf → materialize resources/files/<name> → CaptureService.enqueue(file)
```
- New backend: `POST /api/capture/file` (multipart) → reuse `MediaController.uploadFile`
  storage + `captureRepo.enqueue(..., "pdf", ...)`. The ingest pipeline already handles PDFs.
- Offline PDF bytes ride the mailbox **as a companion `.enc` file** (not inline JSON — keep
  event files small). PDFs are bounded (unlike video, which stays excluded).
- Size guard: cap shared-PDF size for the offline path (e.g. 25 MB) to protect quota.

### A3. Backup summary
- Shared link/text/PDF → note + `resources/…` → **existing vault→Drive sync** mirrors it.
- The mailbox event itself is the durable backup of the raw share until the server ingests it
  (encrypted on Drive, deleted only after successful ingest).

---

## B. Review reminders — phone-local, best-effort (Option 3)

### B0. The platform reality (why this shape)
- A PWA CAN show a notification with no server (Notifications API), once permission is granted.
- It CANNOT reliably *schedule* one while closed: `Notification Triggers` never shipped stable.
- The only closed-app hook is **Periodic Background Sync** — Chrome fires it opportunistically
  (~once/day for a typical installed PWA, NOT a guaranteed 2h cadence). Android-only; iOS none.
- User bar: "fires at least once/day; 2h would be annoying anyway" → this fits. No server.

### B1. Flow
```
Enable (Sync/Settings): Notification.requestPermission()
  → registration.periodicSync.register('review-reminder', { minInterval: 2h })   // a hint
SW 'periodicsync' (review-reminder):
  now = device local time
  if hour < 7 or hour >= 20 → skip                       // quiet window, device tz
  if meta.doneDate === today → skip                      // conditioning met
  if now - meta.lastNotifiedAt < ~2h → skip              // don't double-fire in a wake burst
  else showNotification("Review due", "Do today's cards + inbox") ; meta.lastNotifiedAt = now
SW 'notificationclick' → focus/open the PWA at /review
```
- Also fire an **opportunistic check on app open** (in-window + not-done + not-recently-fired)
  as a cheap extra chance — harmless, and covers days background-sync never wakes.

### B2. "Today is done" — tracked LOCALLY (device knows its own offline activity)
```
meta.reviewCompleted : all pulled due notes graded today (review list emptied)
meta.notesAccepted   : count of inbox notes filed today  (>=1)
done = reviewCompleted && notesAccepted >= 1  → meta.doneDate = today
```
- Set as the user acts in the PWA (grade / file), so it works fully offline. Reset on date
  rollover. Uses device time so it's tailored to the user's day.
- (Once the mailbox is consumed server-side the server also knows, but the phone-local flag is
  what drives suppression here — no server needed.)

### B3. Honest caveats (say them in the UI)
- Best-effort: Chrome decides when `periodicsync` wakes; expect ~daily, sometimes less.
  Guarantee is only "you'll usually get a nudge on days you haven't reviewed."
- Requires the PWA **installed** + some site engagement; **Android/Chrome only** (no iOS).
- Permission can be denied/blocked → feature silently off; surface the state in Settings.

---

## Phasing (proposed)
- **A-1** offline `capture` event + `CaptureService` refactor + consume case (small; reuses P3).
- **A-2** PDF share (manifest files + `/api/capture/file` + offline companion-file path).
- **B-1** notifications: permission + periodicsync + SW handler + local done-tracking + toggle.
Order vs the offline-sync backlog (P4 cards, P5 learn, P6 polish): user's call. A-1 pairs
naturally with P5 (both extend the mailbox to more event kinds).

## Change Index
| Thing | Where |
|---|---|
| Offline share event | client `pwa/outbox.js` + `pwa/mailbox.js`; server `MailboxConsumeService.consumeFile` |
| Capture enqueue reuse | new `capture/CaptureService` (extracted from `CaptureController.capture`) |
| PDF file share (online) | `manifest.webmanifest` files + `public/sw.js` + new `POST /api/capture/file` |
| PDF file share (offline) | IDB byte stash + `_mailbox/*.pdf.enc` companion + consume materialize |
| Shared-PDF size cap | SW handleShareTarget (client) |
| Reminder scheduling | `public/sw.js` `periodicsync` handler + client `periodicSync.register` |
| Quiet window / cadence | SW handler constants (07–20, ~2h throttle), device local time |
| Done-today conditioning | client meta `reviewCompleted`/`notesAccepted`/`doneDate` |
| Notification enable/toggle | SyncPage (permission + register/unregister) |
