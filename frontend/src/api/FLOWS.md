# API Layer Flows

Files: notes.js, stats.js, utils/useSearch.js

---

## ApiError

`ApiError extends Error` — carries `.status` (HTTP status code).  
Store actions check `e.status === 401` → `set({ showLogin: true })`.

All calls prefixed by `ENV.API_BASE` (default `/api`, see `env.js`).  
Write calls use `credentials: 'same-origin'` — session cookie included automatically.

---

## Endpoint Reference

| Function | Method + Path | Auth |
|---|---|---|
| `fetchNames()` | `GET /api/names` | Yes |
| `fetchChildren(folder?)` | `GET /api/children[?folder=]` | Yes |
| `fetchReview(offset, limit)` | `GET /api/review?offset=&limit=` | Yes |
| `fetchNoteContent(path)` | `GET /api/text?noteName=` | Yes |
| `checkAuth()` | `GET /api/me` | — |
| `login(username, password)` | `POST /api/login` (form-encoded) | No |
| `logout()` | `POST /api/logout` | No |
| `createNote(folder, name)` | `POST /api/notes` | Yes |
| `updateNote(path, content)` | `PUT /api/notes` | Yes (unused — full-replace fallback) |
| `patchNote(path, hunks)` | `PATCH /api/notes/content` | Yes |
| `renameNote(oldPath, name)` | `PATCH /api/notes/rename` | Yes |
| `deleteNote(path)` | `DELETE /api/notes` | Yes |
| `moveNote(srcPath, folder)` | `PATCH /api/notes/move` | Yes |
| `fetchSettings()` | `GET /api/settings` | No |
| `saveSettings(patch)` | `PUT /api/settings` | Yes |
| `uploadFile(file, filename)` | `POST /api/upload` (multipart) | Yes |
| `searchNotes(query, {signal?})` | `GET /api/search?q=&limit=10` | Yes |
| `fetchStats()` (stats.js) | `GET /api/stats` | Yes |

### searchNotes — AbortController pattern

```js
const ctrl = new AbortController();
searchNotes(query, { signal: ctrl.signal });
ctrl.abort(); // cancels in-flight fetch; throws AbortError (caught, ignored)
```

`useSearch` hook wraps this: new query → cancel previous controller → start 500ms debounce → fire with fresh controller. Prevents stale results arriving out-of-order.

---

## Change Index

| Thing to change | Where |
|---|---|
| API base URL | `env.js ENV.API_BASE` |
| Add a new API call | `api/notes.js` + corresponding store action in `store/useStore.js` |
| 401 interception | `api/notes.js` fetch wrapper + store `showLogin` check |
| Search debounce delay | `utils/useSearch.js` — `setTimeout(…, 500)` |
| Search result limit | `api/notes.js searchNotes()` — `limit=10` query param |
