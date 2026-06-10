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
│   ├── notes (path, content, modified_at)
│   └── folder_tree (path, type, parent)
│
├── Local search (SQLite FTS5)
│
└── Google Drive Sync Engine (Asynchronous Intermediary)
    ├── Pulls state from Google Drive (provided by Laptop)
    ├── Pushes mobile edits to Google Drive outbox
    └── Offline resilient
```

---

## Sync Engine — The Asynchronous Google Drive Approach

Because the Spring Boot server runs on a laptop that may be asleep or entirely offline, the mobile app **cannot** rely on direct HTTP API calls to the laptop. We must use an always-available intermediary: **Google Drive**.

### The Flow:
Both devices operate on a shared Google Drive application folder.

1.  **Laptop (The Source of Truth)**
    *   **Export:** Periodically (or on changes), a background job zips the vault (or exports an SQLite dump/JSON state) and uploads it to Google Drive as the "Master State".
    *   **Import:** Periodically checks a `Mobile_Outbox/` folder in Google Drive. If it finds files (edits made by mobile), it downloads them.
    *   **Accounting/Merge:** If a mobile edit conflicts with a recent laptop edit (both modified while disconnected), the laptop performs a Git-style merge or generates a `.sync-conflict` file for manual review. The laptop then applies the changes to the physical Obsidian vault.

2.  **Mobile (The Remote Client)**
    *   **Local Storage (Persistent):** The app uses SQLite as a robust, persistent local database. It is not just a temporary viewer; it is a fully functional local replica of the vault.
    *   **Import / Master Sync:** When network is available, it downloads the latest "Master State" zip and merges it into SQLite, ensuring the phone reflects the laptop's reality.
    *   **Offline Edits & Creations:**
        *   *Updates:* Modifications to existing notes are immediately saved to the local SQLite, ensuring they persist across app restarts and outlast sudden battery deaths.
        *   *Creations:* Entirely new notes created on the phone are saved into the SQLite store and become immediately available for local offline search.
    *   **Export (Outbox Queue):** Every creation or update is logged in a local sync queue. When online, the phone uploads these as distinct files (e.g., full markdown files prefixed with `CREATE_` or `UPDATE_`) to the `Mobile_Outbox/` on Google Drive.

### Why this works:
*   The phone never needs the laptop to be awake to browse or search.
*   The phone never needs the laptop to be awake to save a note.
*   The laptop does all the heavy lifting of conflict resolution when it wakes up, keeping the mobile app logic thin.

---

## UI/UX Redesign for Mobile

The 3-panel desktop interface (Sidebar / Editor / Preview) is incompatible with small screens. We need a streamlined, capture-first design.

### 1. Bottom Navigation Bar
*   **Home/Recent:** Quick access to recently edited/reviewed notes.
*   **Search**: Full-screen search interface with fast local SQLite FTS matches.
*   **Vault/Tree**: The traditional folder tree viewer for manual navigation.

### 2. Frictionless Quick Capture
*   A persistent, centralized **"+" FAB (Floating Action Button)**.
*   Tapping it opens a modal overlay immediately focused on a text input, allowing the user to dump thoughts and hit "Save". The app handles deciding where to put it (e.g., an "Inbox" folder) or asks later.

### 3. Editor View
*   Single-pane experience. You are either reading the rendered markdown, or you tap to enter edit mode (which drops the formatting or provides a mobile-optimized WYSIWYG).
*   AI features are minimized or offloaded to the laptop's sync process (e.g., text processing happens when the note lands back on the laptop).

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
