# Drive-Mediated Offline Sync — Architecture & Implementation Plan

> Consolidates the 2026-07-06 design discussion. Goal: the installed PWA does **Review,
> Learn, and Capture on a train with no server**, reading from **Google Drive** and
> writing back **through Drive** — so the laptop/server need never be online while you
> use the phone. Server absorbs the phone's work whenever it next boots.
>
> Supersedes the "direct-HTTP outbox" decision in `PWA_MOBILE_ARCH.md` §15/§16: that
> assumed the server is reachable at flush time. User requirement (2026-07-06): **Drive
> is the transport** (99.5% availability; zero "turn the laptop on in the morning"
> friction). Revives §16 **option C (Drive mailbox)** for write-back and **option B
> (Drive read)** for download — both now first-class, not fallbacks.

---

## 0. Decisions locked (2026-07-06)

- **Reads from Drive**, not from the server. The phone talks to Google, not the laptop.
- **Writes to a Drive mailbox**; a server job consumes + deletes on next boot.
- **Flashcards double-exported**: still in the nightly `pg_dump` (`_db/`, migration
  snapshot) AND additionally as small per-item **`.enc` card files** on Drive so the
  phone can read them without touching the dump. (Parsing `pg_dump -Fc` on the phone is
  rejected — no browser-grade `-Fc` parser exists, and it's a 147 MB full snapshot.)
- **Full offline flashcard TESTS** (not just self-rated review) are the v1 target:
  answer on the train → **server grades on consume** (deferred grading).
- **Two-track model stays**: installed PWA (`display-mode: standalone`) = this Drive-backed
  offline app; a browser tab = the full server-backed site (unchanged).

---

## 1. End result

Install the PWA, open it once on wifi, hit **Sync** → it pulls today's set from Drive into
the phone. On the train (airplane mode): review notes (self-rated *and* card tests), triage
the Learn inbox, capture links. Everything you do is recorded locally. Back on wifi (server
still off) → the phone **encrypts your events to a Drive mailbox**. Whenever the laptop next
boots, a job **consumes the mailbox**, applies grades/edits/filings/answers to the DB + vault,
re-exports a fresh set to Drive, and **deletes only the processed mailbox files**.

Non-goals v1: iOS (Web Share Target is Android-only); real-time two-way while both online
(the browser-tab full site already covers "online").

---

## 2. Data on Drive — inventory

| Data | On Drive today? | For the phone we… |
|---|---|---|
| Notes (`.md`) | ✅ per-file `path.enc` (gzip+AES-GCM) | read + WebCrypto-decrypt directly |
| Resources/images (`resources/…`) | ✅ per-file `.enc` | read + decrypt (cache in Cache Storage) |
| Review due date (`sr-due`) | ✅ **in note frontmatter** | compute the due list **on the phone** — no DB needed |
| Learn inbox (`_inbox/*.md`) | ✅ (part of vault) | read like any note |
| Flashcards (`cards` table) | ⚠️ only inside the `_db/` pg_dump snapshot | **NEW**: also export due cards as `_offline/cards/*.enc` |
| DB snapshot (`_db/…pgdump.enc`) | ✅ | **ignored by the phone** (migration only) |
| Phone's write-back events | ❌ | **NEW**: phone writes `_mailbox/<device>-<ts>-<n>.enc` |

New Drive folders (created by the SAME OAuth client, so both devices see them under
`drive.file`): `_offline/` (server→phone card exports + a small due manifest) and
`_mailbox/` (phone→server events).

---

## 3. The three flows

### 3a. Download (Drive → phone), while online, no server required
```
Sync tab → DrivePull:
  Drive files.list(_offline/ + vault) with OAuth token (same client)
  for each needed .enc: files.get?alt=media → bytes
    → WebCrypto decrypt (§4) → gunzip → text/bytes
  notes+inbox     → IndexedDB (note text) ; images → Cache Storage
  _offline/cards/ → IndexedDB (assignments store)
  due list = parse sr-due from decrypted note frontmatter (utils/frontmatter, shared code)
  store meta.lastPull = now
```
To change what's pulled: `_offline/` manifest (server side, §8) + `DrivePull` selection.

### 3b. Offline use (train), writes recorded locally as append-only events
```
Review (self-rated) → band → event {kind:'grade', path, band, ts, eventId}
Review (card test)  → per-card answers → event {kind:'assignment', assignmentId, answers, ts, eventId}
Learn file/discard  → event {kind:'file'|'discard', path, targetFolder?, content?, ts, eventId}
Capture link        → event {kind:'capture', url, ts, eventId}
Note edit (inbox)   → event {kind:'note-edit', path, content, ts, eventId}
```
Events go to the IndexedDB outbox (existing `pwa/outbox.js`, extended with kinds).
`eventId` = uuid → idempotent replay (server dedupes).

### 3c. Write-back + consume (phone → Drive → server)
```
Phone (on wifi, server maybe off):
  for each queued event → encrypt (§4, same passphrase) → Drive files.create
    into _mailbox/<deviceId>-<ts>-<seq>.enc  → delete from local outbox on 200

Server (NEW MailboxConsumeWorker, on boot + periodic while up):
  Drive list _mailbox/*.enc → for each file (ascending ts):
    download → decrypt → parse events
    apply each event (dedupe by eventId):
      grade      → ReviewService.grade(path, band)                       (FSRS)
      assignment → replay per-card submitAttempt(assignmentId, …) + completeAssignment  (deferred grading)
      file       → InboxService.file(path, targetFolder, content)
      discard    → InboxService.discard(path)
      capture    → CaptureController ingest pipeline
      note-edit  → FileRepository.updateNote(path, content)             (→ sync_queue → re-upload to Drive)
    ALL events in the file applied OK → DriveService.deleteFile(id)     (delete ONLY this file)
    any failure → leave the file, retry next pass (events idempotent)
  then re-export _offline/ (§8) so the phone's next pull is fresh
```
**Delete-after-success is the correctness hinge** (your rule): a file is removed only when
every event in it committed; idempotent `eventId`s make a reprocessed file harmless.

---

## 4. Crypto (phone side) — reproduce `VaultEncryptionService` in WebCrypto

Wire format is fixed: `[12B IV][AES-256-GCM ciphertext+tag]`, plaintext is gzip'd.
```
key   = PBKDF2-SHA256(passphrase, salt="ObsidianSyncSalt", 310_000, 256-bit)   // crypto.subtle
dec   = AES-GCM decrypt(iv = bytes[0:12], data = bytes[12:])
plain = DecompressionStream('gzip')(dec)
enc (write-back) = gzip → 12B random IV → AES-GCM → [IV][ct+tag]                // exact inverse
```
All native in Chrome Android. Passphrase is entered once in the PWA and held in
IndexedDB (`meta.passphrase`) — same trust level as the plaintext vault on the laptop.
To change KDF/params: keep in lockstep with `VaultEncryptionService` (fixed salt +
310k iters) — a mismatch makes every file undecryptable.

---

## 5. OAuth — the phone must be the SAME client

`drive.file` scope = an app sees only files **it** created. For the phone to see the
server's `.enc` files (and vice-versa for `_mailbox/`), the PWA must act as the **same OAuth
client** as the server — same `client_id`, same created-files namespace.

**DECISION (2026-07-06): share the credentials at install.** Instead of a separate consent
on the phone, the one-time PWA setup **fetches the server's credentials over the tunnel**
(server is up when you install at home), then the phone is server-independent forever after:
```
Install PWA → SyncPage "Link this device" → GET /api/pwa/setup (session-authed)  [NEW, B?]
  → { clientId, clientSecret, refreshToken, driveFolderId, passphrase, deviceId }
  → store in IndexedDB (meta)
Thereafter (no server needed):
  access token: POST oauth2.googleapis.com/token (refresh_token grant) directly from the PWA
  Drive + decrypt: use that token + passphrase
```
- Security: the phone now holds the OAuth client secret + refresh token + vault passphrase —
  same trust level as the laptop. Acceptable for this single-user tool; document it. A
  lost/unlocked phone can read the vault. Add a "Unlink device" (server revoke + phone wipe).
- Risk: "Testing"-status OAuth clients expire refresh tokens ~7 days — set the client to
  **In production** (already noted in sync/FLOWS Technology Notes).
- Token refresh from a browser hits Google's token endpoint; if CORS blocks the refresh
  grant, fall back to a thin server-proxied refresh (needs server) OR a PWA PKCE public
  client. Verify during P1.

---

## 6. Flashcards — double-export + deferred grading

**Export (server, while up):** a chrono job builds a **persisted assignment** per due note
(reusing the existing `buildAssignment` — freezes exercise variants, picks cards to the
point budget) and writes it to `_offline/cards/<noteId>.enc` as JSON {assignmentId, cards,
variants}. Persisting the real assignment means grading reuses the existing engine verbatim.

**Offline (phone):** present the assignment from IndexedDB. MCQ can self-check locally
(correct index is in the payload) for instant feedback; open/exercise just record the
answer ("recorded") — semantic grading is server-only.

**Consume (server):** for an `assignment` event, replay `submitAttempt(assignmentId,
cardId, answer)` for each answer + `completeAssignment(assignmentId)` → real grading +
FSRS + bandit, exactly as an online session. Deferred, not reimplemented.

Lifecycle caveat: pre-built assignments linger until consumed or expired — add a TTL sweep
so un-reviewed exports don't accumulate. To change selection/budget: `buildAssignment`.

---

## 7. Conflict & idempotency rules

- Events are **append-only against server-owned state** — the phone never merges note
  bodies except its own `_inbox` edits (which it authored offline), so no 3-way merge.
- **`eventId` dedupe**: server keeps a small `consumed_events(event_id)` table; re-applying
  a replayed file is a no-op.
- **Note-edit vs Drive**: reuse the existing sync rule — a phone `note-edit` becomes a
  local write → `sync_queue` PENDING → re-upload; **PENDING local edits win** over Drive
  on the next download (sync/FLOWS "Local-wins"). A genuine two-sided clash writes a
  `_conflicts/` file (PWA_MOBILE_ARCH §14), human-resolvable.
- **Order**: process mailbox files in ascending `ts`; within a file, events in order.

---

## 8. Backend work (new)

| # | Component | Does |
|---|---|---|
| B1 | `OfflineExportWorker` [NEW] | while up (nightly + after consume): build due-note assignments → `_offline/cards/*.enc`; write `_offline/manifest.enc` (due paths + card index). Reuses `buildAssignment`, `VaultEncryptionService`, `DriveService`. |
| B2 | `MailboxConsumeWorker` [NEW] | on boot + periodic: list/download/decrypt `_mailbox/*.enc`, apply events, delete-on-success. Runs on the existing **sync `WorkerLane`** (single-flight with uploads). |
| B3 | `consumed_events` table + repo [NEW] | idempotent replay dedupe (`event_id` PK). |
| B4 | Deferred-grade path | reuse `submitAttempt`/`completeAssignment`/`ReviewService.grade`/`InboxService` — thin adapter from event → existing service call. |
| B5 | `DriveService.deleteFile(id)` [NEW] | hard-delete a consumed mailbox file (today only `trashFile` exists). Mailbox files are transient → delete, not trash. |
| B6 | `_offline/` + `_mailbox/` folder handling | exclude from `listRecursive`/janitor (like `_db/`), so the vault sync + janitor ignore them. |
| B7 | Settings | "Prep offline set now" button → trigger B1; expose export/consume status in `/sync/status`. |

No change to the FSRS/bandit/ingest engines — all reused.

## 9. Frontend / PWA work

| # | Component | Does |
|---|---|---|
| F1 | `pwa/drive.js` [NEW] | Drive REST (list/get/create) with the same-client OAuth token. |
| F2 | `pwa/crypto.js` [NEW] | WebCrypto decrypt/encrypt matching §4. |
| F3 | `pwa/drivePull.js` [NEW] | orchestrate §3a → IndexedDB + Cache Storage; due list from `utils/frontmatter`. |
| F4 | `pwa/db.js` [EXTEND] | add `assignments` store; keep `reviewNotes`/`outbox`/`meta`. |
| F5 | `pwa/outbox.js` [EXTEND] | new event kinds; flush target = Drive `_mailbox/` (not `/api`). |
| F6 | `pwa/offlineApi.js` [RE-POINT] | installed PWA reads from Drive/IDB, not the server bundle. |
| F7 | Offline **card test** in `FlashcardSession` | load assignment from IDB when offline; local MCQ self-check; record answers → outbox. Keep the online path as-is. |
| F8 | `SyncPage` [EXTEND] | passphrase entry, Google connect (same client), Pull from Drive, Push mailbox, staleness, pending-event count. |
| F9 | OAuth in-PWA token flow (§5) | Google Identity Services; store token in IDB. |

**Reuse (unchanged):** `FlashcardSession` UI, `ReviewPage`, `LearnPage`/`InboxReview`
(incl. the mobile single-view), `NoteRenderer`, `RsvpReader`, `utils/frontmatter`, the
whole component tree. The seam is the data layer (F1–F6), exactly as the two-track model
intended.

> **Note on this session's uncommitted work** (`App.jsx`, `ReviewPage.jsx`,
> `ReviewRating.jsx`, `useStore.js`): that wired the *direct-HTTP* offline seam
> (outbox → `/api/reviews/grade`). It compiles and is a valid online-fallback, but under
> this plan the installed-PWA write-back **re-points to Drive** (F5/F6). Keep the seam
> abstraction + the queued-state UI; swap the transport. Decide whether to commit it as
> "offline seam v1 (direct HTTP)" or fold straight into F5/F6.

## 10. Phases (ordered, each shippable)

> **Status 2026-07-06: P1–P3 BUILT** (commits c5ab4f1, 3c39b5a, 7efa137; compile-verified,
> browser/Drive-UNVERIFIED — no host node, needs a real phone + connected Drive). Backend:
> `pwa/` package (PwaController, OfflineExportService, MailboxConsumeService,
> ConsumedEventRepository) + `sync/DriveService` `_offline`/`_mailbox` ops. Frontend:
> `pwa/{crypto,drive,setup,drivePull,mailbox}.js` + `offlineApi` driveMode + SyncPage.
> P4–P6 pending. NOTE: capture/file/discard/assignment events are NOT yet consumed
> (grade only) — the mailbox leaves unknown-kind files intact until P4/P5.

- **P1 — Crypto + Drive read proof** (F1,F2): in the PWA, connect Google (same client),
  list + decrypt one real note from Drive. *Smallest proof the whole model works.*
- **P2 — Offline self-rated review from Drive** (F3,F4,F6 + due-from-frontmatter): pull
  notes, review by band offline, events to outbox. **No backend change** — headline value,
  lowest risk.
- **P3 — Write-back mailbox** (F5,F9 + B2,B3,B5,B6): phone pushes `_mailbox/`; server
  consumes on boot, deletes on success. Round-trips a grade with the server off at write time.
- **P4 — Card export + offline card tests** (B1,B4,B7,F7): the double-export; full offline
  flashcard tests with deferred grading. The "brag" feature.
- **P5 — Learn offline lane**: inbox notes + resources in the pull; file/discard events.
- **P6 — Polish**: staleness banners, Periodic Background Sync top-up, `_conflicts/` UI,
  assignment TTL sweep.

## 11. Open decisions / risks

- **Token in the PWA** (§5): RESOLVED — share credentials at install (`/api/pwa/setup`),
  phone refreshes access tokens against Google directly. Verify browser CORS on the refresh
  grant in P1 (fallback: server-proxied refresh).
- **Pull scope**: due-now + next N days (over-fetch as staleness insurance). Pick N.
- **Media quota**: images/PDF in the pull; video excluded by default (Cache Storage is
  evictable — `navigator.storage.persist()` best-effort). Big vaults may need a cap.
- **Passphrase on the phone**: unavoidable for client-side decrypt; document the trust
  implication. Lost/stolen phone = vault-readable if unlocked.
- **Assignment lifecycle**: pre-built assignments accumulate — needs a TTL/GC.
- **Clock skew**: events ordered by phone `ts`; a wrong phone clock mis-orders. Minor for
  single-user; could stamp server-receive order at consume.

## 12. Relationship to existing docs
- `PWA_MOBILE_ARCH.md`: this plan REPLACES its §15/§16 transport decision (Drive, not
  direct-HTTP) and makes §16 B+C first-class. The two-track seam (§7 there, revised) stands.
- `sync/FLOWS.md`: reuse `DriveService`/`VaultEncryptionService`/`DeviceIdentityService`
  wholesale; add `_offline`/`_mailbox` handling + `deleteFile`.
- `cards/FLOWS.md`: `buildAssignment`/`submitAttempt`/`completeAssignment` reused for the
  export + deferred grading.

## 13. Change Index
| Thing to change | Where |
|---|---|
| What the phone pulls | `_offline/manifest.enc` (server `OfflineExportWorker`) + `pwa/drivePull.js` |
| Card export set/budget | `OfflineExportWorker` → `buildAssignment` args |
| Mailbox consume + delete-on-success | `MailboxConsumeWorker` |
| Event kinds | `pwa/outbox.js` (client) + consume adapter B4 (server) |
| Idempotency | `consumed_events` table (B3) + `eventId` (client) |
| Crypto params | `pwa/crypto.js` ⇄ `VaultEncryptionService` (must match) |
| OAuth client / redirect | `GOOGLE_OAUTH_CLIENT_ID` + PWA origin redirect URI |
| Drive folders ignored by vault sync/janitor | `DriveService.listRecursive` skip (`_offline`,`_mailbox`) |
| Offline card test load | `FlashcardSession` (IDB assignment when offline) |
| Pull scope (N days) / media policy | `drivePull.js` args |
