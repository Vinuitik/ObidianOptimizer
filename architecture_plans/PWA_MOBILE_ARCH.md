# PWA Mobile — Architecture & Implementation Plan

> Handoff doc. Goal: turn the existing React frontend into an installable, offline-capable
> PWA on Android, reusing the current codebase maximally so **a fix made once applies to both
> desktop and phone**. Implementation happens in a later fresh session from this plan.
> Status (2026-07-02 audit): **P1–P4 BUILT but DORMANT; backend §8 SHIPPED.**
> All PWA code exists in `frontend/src/pwa/` (see its FLOWS.md — shell, MobileLayout,
> offline review seam, share-target capture, hand-written `public/sw.js`) but is NOT
> activated: `src/main.jsx` still renders desktop `App` directly and the store is not
> wired to `pwa/offlineApi`. Backend (`CaptureController`) provides `POST /api/capture`,
> `GET /api/review/bundle`, `/api/download`. **Next step is the two activation edits**
> (`pwa/FLOWS.md` "Activation"), likely alongside the pending mobile-responsive fixes.
> This plan supersedes the native approach (MOBILE_ARCH.md, deleted — its surviving
> decisions are folded in at §14).
> Requirements update (user, 2026-07-02): (a) **full web parity while the server is up**
> (not view-only), (b) offline must cover **reviews AND the Learn triage queue**, (c) the
> offline set must be **refreshed aggressively enough to be fresh, not stale** — see §15,
> (d) a sync path for when the server is down for days — see §16.

---

## 0. Why PWA, not native (decision locked)

- Phone is **Android** → PWAs are first-class (installable, offline, share-target).
- **Not publishing** → no Play Store, no signing. Install = "Add to Home Screen."
- **Reuse**: a PWA *is* the existing React app + browser superpowers. Native (Kotlin/RN/Flutter)
  = a second codebase that can't share the React components — violates the "fix once" goal.
- The reusable Java (FSRS, bandit, sync) lives on the **server** and is untouched; the PWA talks
  to the **same backend HTTP APIs** the desktop uses.

## 1. Desired end result (the prototype)

A thing you install on your Android home screen that:
1. **Offline review** — caches the due-for-review subset (notes + media) while online; lets you
   review on a commute with **no connectivity**; queues grades; replays them on reconnect.
2. **Capture** — appears in Android's **share sheet**; share a link → it reaches the server →
   ingest pipeline turns it into a note (the "saved messages I never read" replacement).
3. **View** notes / images / PDFs / video from the cached subset offline.
4. Portrait-first mobile UI; desktop UI unchanged.

Out of scope for v1: full-vault offline (only the review/learn subset), iOS (Web Share
Target is Android-only).
**Scope change (2026-07-02):** editing is IN while online — the phone must match the
website feature-for-feature whenever the server is reachable. Since the mobile shell
reuses the same pages/components (§2/§7), online parity is mostly free; the deliberate
cut is only **offline** editing of arbitrary notes (offline writes are limited to the
structured ones in §15: grades, inbox filing, quick-capture text).

## 2. Core principle — ONE codebase, reuse-everything

**Single responsive codebase. Do NOT fork a separate mobile app.** The seam that makes this work:

```
components (atoms/molecules/organisms)   ← SHARED, untouched (this is where fixes land)
        │
   data layer (api/ + store)             ← made offline-aware (the ONE new seam)
        │
   layout shell (templates)              ← desktop SplitLayout  |  new MobileLayout (by viewport)
```

- **Reused as-is**: `useStore.js` (Zustand), `api/notes.js` + `api/stats.js`, every atom/molecule/
  organism (`FlashcardSession`, `SlideshowReview`, `ReviewList`, `NoteViewer`, `SearchBar`,
  `FrontmatterTable`, `MilkdownEditor`*), all `utils/` (diff, frontmatter, markdown, plugins).
  *MilkdownEditor only on desktop in v1 (mobile is view-only via `NoteViewer`).
- **New (small, additive)**: PWA plumbing, an offline data layer, a mobile layout shell + bottom
  nav, the share-target handler. **No leaf component is rewritten** → a bug fix in `FlashcardSession`
  fixes it on both. That is the whole point.

## 3. Stack additions

| Need | Use | Why |
|---|---|---|
| Service worker + manifest | **`vite-plugin-pwa`** (`injectManifest` mode) | Wraps Workbox; reuses the existing Vite build; auto-precache; lets us write a custom SW for share-target |
| IndexedDB access | **`idb`** (tiny promise wrapper) | review queue, grade outbox, note metadata |
| Viewport switch | small `useMediaQuery` hook (no dep) | choose layout shell at runtime |

Everything else is already in the project.

## 4. Storage design (the "file system" answer)

A PWA does NOT roam the phone FS (web security). It uses its own sandboxed storage, which is
exactly right for "cache a subset for offline":

- **Cache Storage** (via service worker) — the **app shell** (HTML/JS/CSS, so it launches offline)
  + **media files** (images/PDF/video fetched from `/api/images/...`). Binary/large content.
- **IndexedDB** (`idb`) — structured data: the **review queue** (due notes + their markdown text),
  note metadata, and the **outbox** of pending grade POSTs.
- Call **`navigator.storage.persist()`** on first install so Android won't evict the offline set
  under storage pressure.

```
offline/db.js          — open IndexedDB, stores: 'reviewNotes', 'outbox', 'meta'
offline/syncOffline.js — "Download for offline": GET review bundle → put notes in IDB,
                         fetch each note's media → cache.addAll() into a named cache
offline/outbox.js      — enqueue(grade); flush() replays queued grades to /api/reviews/grade
offline/connectivity.js— navigator.onLine + 'online'/'offline' events → store flag
```

Caching strategy:
- App shell → **precache** (Workbox, generated by vite-plugin-pwa).
- Media (`/api/images/*`) → **explicit** `cache.addAll()` during "Download for offline" (deliberate
  control over what's stored), plus runtime **cache-first** fallback.
- Data GETs (`/api/review`, `/api/text`) → served from **IndexedDB** when offline; network when online.

## 5. Offline data layer (the seam — keeps components unchanged)

Wrap the existing `api/` calls so consumers don't change:

```
api/notes.js  fetchReview(), fetchNoteContent()  →  read IndexedDB when offline, network when online
              grade()                            →  POST when online; else outbox.enqueue() + optimistic local update
```

- Components (`ReviewPage`, `FlashcardSession`, `SlideshowReview`) call the same functions →
  they work offline with zero component edits. **This is what makes "fix once" true.**
- On reconnect (`connectivity` → online): `outbox.flush()` replays grades to `/api/reviews/grade`.
  If the session expired, prompt login first (LoginModal already exists), then flush.

## 6. Capture — Web Share Target API (be specific)

**Manifest** (generated via vite-plugin-pwa `manifest` option) includes:
```json
"share_target": {
  "action": "/share-target",
  "method": "POST",
  "enctype": "multipart/form-data",
  "params": { "title": "title", "text": "text", "url": "url" }
}
```
- Android puts a shared link in `url` OR `text` (varies by source app) → handle both.
- The **service worker** intercepts `POST /share-target`: read the form data, extract the URL,
  then:
  - online → `POST /api/capture { url }` (new backend endpoint, §8) → 200 → redirect to a
    `/share-target?ok=1` confirmation page,
  - offline → store the URL in the IDB outbox; flush on reconnect.
- Requires the PWA **installed** + **Android Chrome** (both fine for the use case).

End-to-end: share a link → PWA shows in the sheet → tap → `/api/capture` → embedder `/ingest`
(standalone mode) → note created via `find_home`. Reuses the entire existing ingest pipeline.

## 7. Mobile UI (recommendation: responsive, portrait-first, view+review only)

**Recommendation: ONE responsive codebase, mobile gets a different SHELL, not a different app.**
Rationale: the leaf components are screen-agnostic; only the *layout* is wrong on a narrow phone.
So swap the template by viewport, reuse everything inside.

- `useMediaQuery('(max-width: 768px)')` in `App.jsx` → render `MobileLayout` vs the desktop
  `SplitLayout`. Same routes, same pages, same store.
- **MobileLayout** = single column + **bottom tab bar** (thumb-reachable): `Notes · Review ·
  Search · Capture · Settings`. Views **stack/push** instead of sitting side-by-side:
  - Notes tab: `FolderTree` (full width) → tap note → full-screen `NoteViewer` (read-only) → back.
  - Review tab: full-screen `FlashcardSession` / `SlideshowReview` (already self-contained).
  - Search tab: `SearchBar` + results full width.
  - Capture tab: shows recently captured + a manual "paste a link" box (same `/api/capture`).
- **Portrait only** — don't build a separate landscape mobile layout; rotation just reflows.
- **Editing on mobile: online yes, offline no** (scope change 2026-07-02, see §1). Online
  the Notes tab opens the same editor path as desktop (Milkdown is fiddly on touch — if it
  proves unusable, fall back to a plain `<textarea>` + preview toggle for the mobile shell
  only; the API/diff layer is identical either way). Offline, notes render read-only and
  the only writes are the structured outbox ones (§15).

To change the breakpoint: `useMediaQuery` arg. To add a mobile tab: `BottomNav` items + a route.

## 8. Backend additions — ✅ SHIPPED (capture/CaptureController.java)

1. ✅ **`POST /api/capture { url | text }`** — exists, and grew beyond this plan: it also
   creates a `capture` row (CAPTURE_ARCH lifecycle) and stores pasted text as a resource.
2. ✅ **`GET /api/review/bundle?limit=N`** — exists (`CaptureController.bundle()`): due notes
   with content + `/api/images/...` media URLs, one round-trip.
3. ✅ CORS/CSP: unchanged, same-origin via the tunnel.
   Still to add for §15: **`GET /api/learn/bundle`** — the inbox counterpart of the review
   bundle (inbox notes + suggested folders + folder list for the picker), so the Learn
   offline lane is also one round-trip. Reuses `InboxController.list()` + `/children` data.

## 9. Critical gotcha — HTTPS / service workers

Service workers require a **secure context** (real HTTPS or `localhost`). The stack's **self-signed**
cert (local `:8443`) will likely BLOCK service-worker registration in Chrome. But over the
**Cloudflare tunnel** (`obsidianoptimizer.uk`) Cloudflare serves a **real cert** → SW works.

→ **Install + "Download for offline" must be done while online via the tunnel domain** (real HTTPS).
After that, offline use runs from cache with no network. Document this in onboarding: "open
obsidianoptimizer.uk on the phone once on wifi, Add to Home Screen, hit Sync, then it works offline."

## 10. Implementation phases (ordered for a prototype ASAP)

- **P1 — Installable shell**: add `vite-plugin-pwa`, manifest, icons, precache. Result: "Add to
  Home Screen" works, app launches offline (shell only). *Smallest demoable PWA.*
- **P2 — Mobile layout**: `useMediaQuery` switch + `MobileLayout` + `BottomNav`; reuse pages/leaf
  components. Result: usable portrait UI on the phone (still online).
- **P3 — Offline review** (the core value): `offline/db.js` + `syncOffline.js` + outbox; wrap
  `fetchReview`/`fetchNoteContent`/`grade`; `GET /api/review/bundle` (already shipped). Result:
  review the cached subset offline, grades replay on reconnect. **The prototype's headline.**
- **P3b — Offline Learn lane**: `GET /api/learn/bundle` (new) + inbox file/discard outbox
  events + freshness plumbing (§15: focus-refresh, periodicsync, staleness banner).
- **P4 — Capture**: `share_target` manifest + SW POST handler + `POST /api/capture` (endpoint
  already shipped). Result: share a link → note on the server.
- **P5 — Media offline**: cache images/PDF/video for the subset; `NoteViewer` plays from cache.
- **P6 (conditional) — Drive read fallback** (§16 option B) — only if multi-day laptop-off
  staleness proves painful in practice.

Phase state (2026-07-02): P1–P4 code-complete in `frontend/src/pwa/` but dormant
(activation edits pending); P3b, P5, P6 and the §15 freshness plumbing are open.

## 11. Files to add / change

```
ADD  frontend/vite.config.js          → VitePWA({ strategies:'injectManifest', manifest:{…share_target…} })
ADD  frontend/src/sw.js               → custom service worker (Workbox precache + share-target POST + runtime caching)
ADD  frontend/public/icon-192.png, icon-512.png, maskable-512.png
ADD  frontend/src/offline/db.js, syncOffline.js, outbox.js, connectivity.js
ADD  frontend/src/hooks/useMediaQuery.js
ADD  frontend/src/components/templates/MobileLayout.jsx (+ .module.css)
ADD  frontend/src/components/organisms/BottomNav.jsx (+ .module.css)
ADD  frontend/src/pages/CapturePage.jsx
EDIT frontend/src/App.jsx             → viewport-based layout switch; register SW
EDIT frontend/src/api/notes.js        → offline-aware fetchReview/fetchNoteContent/grade (outbox)
EDIT frontend/src/store/useStore.js   → online flag + outbox-aware grade action (minimal)
ADD  backend  CaptureController.java   → POST /api/capture {url} → embedder /ingest
EDIT backend  NoteIndexRepository / a controller → GET /api/review/bundle (optional)
```

## 12. Open decisions (resolve at implementation)

- **Auth offline**: cache last-known auth state; on reconnect re-login (LoginModal) before outbox
  flush. Confirm Spring session cookie survives PWA context (it should — same-origin).
- **Which media to cache**: all subset media, or images+PDF only first (video is big — may blow
  quota). Recommend images+PDF in P3, video in P5 behind a size check.
- **Subset definition**: "due now + due in next N days + last M auto-generated notes." Pick N/M.
- **vite-plugin-pwa strategy**: `injectManifest` (custom SW, needed for share-target) vs
  `generateSW` (simpler, no share-target). Use `injectManifest`.

## 13. Reuse scorecard (what this buys)

- ~0 leaf components rewritten → fixes propagate to both surfaces.
- Backend: +1 small endpoint (+1 optional) — the ingest/review/FSRS engines are reused whole.
- New code is concentrated in: PWA plumbing + offline data layer + mobile shell. All additive,
  all isolated from the existing component tree.

## 14. Decisions inherited from the deleted MOBILE_ARCH.md

- **Web search stays backend-only; offline search runs on the cached subset only.** No
  full-vault client replica (that was the native-app design; the PWA subset model
  replaces it).
- **Conflict artifacts, if ever needed, are `_conflicts/` files** — Obsidian-compatible,
  human-resolvable. v1 avoids conflicts structurally instead (§15: offline writes are
  append-only events, not note edits).
- React Native/Expo/SQLite/FTS5 stack: dead — superseded by this PWA plan.

## 15. Offline lanes + freshness (the "real, not stale data" requirement)

The offline set is two **lanes**, refreshed together, each with its own IDB store +
outbox event type:

| Lane | Cached (read) | Offline writes (outbox events) | Replay endpoint |
|---|---|---|---|
| **Review** | `GET /api/review/bundle` (due now + next N days) + media | `{type:"grade", path, grade, ts}` | existing grade endpoint |
| **Learn** | `GET /api/learn/bundle` (inbox notes, suggested folders, folder list) | `{type:"file", path, targetFolder, content, ts}` / `{type:"discard", path, ts}` | `POST /api/inbox/file` / `DELETE /api/inbox` |
| (bonus) | — | `{type:"capture", text|url, ts}` quick-capture | `POST /api/capture` |

Writes are **append-only events against server-owned state** — the phone never merges
note content, so there is no three-way-merge problem. Replay in `ts` order on reconnect;
a `file` event whose inbox note was already filed elsewhere → server returns conflict →
surface in a small "sync issues" list, don't retry silently.

**Freshness — belt and suspenders, in order of reliability:**
1. **Every app open/focus while online** → refresh both bundles + flush outbox
   (`visibilitychange` + `online` events). This is the workhorse.
2. **Periodic Background Sync API** (`periodicsync`, Chrome Android, installed PWA):
   request a ~6–12 h interval; Chrome fires it opportunistically based on site
   engagement — treat it as best-effort top-up, never as the guarantee.
3. **Staleness banner as the honesty backstop**: store `lastSyncedAt`; the review/learn
   screens show "synced 26 h ago — cards may be stale" past a threshold (default 12 h).
   FSRS tolerates a stale due-list gracefully (reviewing early/late just adjusts the
   next interval), so stale is degraded, not broken — but the user must be able to SEE it.
4. Cache **due-now + next `N` days** (default 3): even a day-old snapshot then still
   contains today's real due cards — over-fetching is the cheap insurance against 1–2
   missing.

## 16. Server down for days — Google Drive as read fallback [phase after P5]

The outbox (§15) already survives arbitrary server downtime for **writes**. The residual
gap is **reads going stale** when the laptop is off for days: the phone's cached bundle
ages and no new inbox notes arrive. Options considered:

- **A. Do nothing extra (ship first, measure).** Bundles + outbox cover the commute
  and the weekend. Staleness banner tells the truth. Zero new moving parts.
- **B. Drive read-fallback in the PWA.** The vault already mirrors to Drive encrypted
  (sync/FLOWS.md). WebCrypto natively does PBKDF2 + AES-256-GCM, and gzip via
  `DecompressionStream` — so the browser CAN pull `.enc` notes from Drive and decrypt
  them with the sync passphrase, entirely client-side. `sr-due` lives in note
  frontmatter, so the phone can even recompute the due list locally. Costs: a Google
  OAuth client (token flow in the PWA), Drive REST from JS, the sync passphrase typed
  into/held by the phone, and a second read path to keep correct. Real but contained.
- **C. Laptop-mediated outbox on Drive** (old native-app design: phone writes
  `Mobile_Outbox/`, laptop merges). Superseded — the §15 outbox does the same job
  without a second write protocol, just with laptop-wake latency.

**Recommendation: A now; B only if lived usage shows multi-day laptop-off periods
actually hurt.** B is the honest escalation path and is compatible with everything
above (it only feeds the same IDB stores the bundles feed). Decide with usage data,
not upfront — the offline lanes must exist first either way.

Note for B's feasibility ledger: notes are small (fine to pull hundreds), but the
review-due computation moves client-side (frontmatter parse — `utils/frontmatter.js`
is already shared code), and grades still queue in the outbox until the server returns
(FSRS scheduling itself stays server-authoritative — replayed grades are timestamped,
so late scheduling is exact).
