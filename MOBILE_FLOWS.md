# Mobile App — Architecture & Decisions

> **Status:** Not yet built. This file captures decisions made during the web build so we don't re-litigate them when the app starts.

---

## Core Decision: Web vs App responsibilities

| Concern | Web app | Mobile app |
|---|---|---|
| File tree | Lazy-loaded from server | Fully cached locally |
| Note content | Fetched on demand | Cached locally (IndexedDB / SQLite) |
| Search | **Backend only** (`grep`-style endpoint) | **Local** (query the local cache) |
| Offline read | ✗ (server required) | ✓ |
| Offline write | ✗ | ✓ (queued, synced on reconnect) |
| Review / spaced rep | Server-authoritative | Cached, synced |

**Why no hybrid search on web:** loading all note content into the browser to enable offline full-text search adds complexity with no real gain — the server is always present for the web user. Hybrid would mean maintaining two search code paths. Rejected.

**Why the mobile app is different:** the target scenario is commuting with the laptop off. The phone must be self-sufficient. This requires a local replica of the vault.

---

## Mobile Architecture Vision

```
Phone (React Native / Expo)
│
├── Local SQLite store
│   ├── notes (path, content, modified_at, synced_at)
│   ├── folder_tree (path, type, parent)
│   └── review_queue (path, reviewed_date, rating)
│
├── Local search (SQLite FTS5 or JS in-memory)
│
└── Sync engine
    ├── On foreground resume → diff + sync
    ├── Background fetch (Expo background-fetch) → periodic silent sync
    └── Conflict strategy: last-write-wins (MVP); CRDT if needed later
```

---

## Sync Engine — Key Challenges

**The hard problem is not reads, it's conflicts.** If the phone edits a note while the laptop is off, and then the laptop edits the same note before the phone reconnects, one version will be lost under last-write-wins. CRDT (Conflict-free Replicated Data Types) can solve this but is significantly more complex.

**MVP strategy: last-write-wins**
- Each note carries a `modified_at` timestamp (or use the filesystem mtime)
- On sync: compare timestamps — most-recent version wins
- Log conflicts to a `_conflicts/` folder for manual review (Obsidian-style)

**Sync flow:**
1. Phone app opens / reconnects to network
2. `GET /api/sync/status` → server returns list of `{ path, modified_at }` for all notes
3. Phone computes diff against its local `synced_at` timestamps
4. Push phone-side changes: `PUT /api/notes` for each locally-modified note
5. Pull server-side changes: fetch content of server-modified notes, overwrite local
6. Update `synced_at` for all synced notes

**Background sync:** Expo `expo-background-fetch` triggers a short sync task every N minutes when the app is backgrounded. Low battery cost since it only fetches the status diff, not all content.

---

## Technology Notes (when we build the app)

- **Framework:** React Native + Expo (natural choice — existing React component patterns carry over; Chip, Icon, ObsidianMark atoms can be reused)
- **Local storage:** `expo-sqlite` with FTS5 extension for full-text search (faster than JS-side Lunr/Fuse for large vaults)
- **Background sync:** `expo-background-fetch` + `expo-task-manager`
- **Network detection:** `@react-native-community/netinfo` — triggers sync on reconnect event
- **Server changes needed:** `GET /api/sync/status` endpoint (list of `{ path, modified_at }`) — cheap scan since it only reads file mtimes, not content

**What carries over from the web app without changes:**
- Spring Boot backend endpoints (all of them)
- The existing `GET /children`, `GET /review`, `PUT/POST/DELETE /notes` endpoints
- React component atoms (ObsidianMark, Icon, Chip, Ring) — reusable in RN with minor style adaptation
- Zustand store shape — same state model, swap the API layer

**What needs to be built fresh:**
- SQLite schema + migration layer
- Sync engine (diff, push, pull, conflict log)
- Offline queue (writes made while server unreachable, drained on reconnect)
- `GET /api/sync/status` backend endpoint (file mtime scan)

---

## Search Architecture

**Web:** backend search endpoint (not yet implemented). `GET /api/search?q=term` → backend greps note content and returns `{ path, snippet }[]`. Keeps web app stateless.

**App:** SQLite FTS5 full-text search on the local note cache. Query is instant and offline-capable. No backend round-trip.

**Planned backend search endpoint:**
```
GET /api/search?q={term}&limit=20
→ [{ path, title, snippet, score }]
```
Backend: scan note content via BufferedReader, return first `limit` matches with a 150-char snippet around the match. Can use the existing note names cache to skip the directory walk. Add to `FileRepository.searchNotes(query, limit)`.

---

## Change Index

| Decision | Where documented | Rationale |
|---|---|---|
| Web search = backend only | This file | No offline need for web; hybrid adds complexity |
| App search = local SQLite | This file | Must work offline; content is already cached |
| Sync strategy = last-write-wins (MVP) | This file | CRDT complexity deferred |
| Conflict handling = `_conflicts/` folder | This file | Obsidian-compatible; user can resolve manually |
| App framework = React Native + Expo | This file | Code reuse from existing React atoms |
| Backend sync endpoint = `/api/sync/status` | This file | Cheap mtime scan, not content transfer |
