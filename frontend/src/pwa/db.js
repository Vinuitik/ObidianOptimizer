// IndexedDB wrapper (no dependency — raw IDB wrapped in promises). Mirrors the
// store schema inlined in public/sw.js; keep the two in sync if you change it.
//
// Stores:
//   reviewNotes  keyPath 'path'     — { path, shortName, content, media:[urls], srDue }
//   assignments  keyPath 'notePath' — { notePath, assignmentId, cards, variants }  (offline flashcards)
//   outbox       autoIncrement      — { id, kind:'grade'|'capture'|'assignment', ...payload, eventId, ts }
//   meta         keyPath 'key'      — { key, value }  (lastSync, driveCreds, doneDate, …)

const DB_NAME = 'obsopt-offline';
const DB_VERSION = 2;   // bumped for the 'assignments' store — keep public/sw.js openDB in sync

let dbPromise = null;

export function openDB() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains('reviewNotes')) db.createObjectStore('reviewNotes', { keyPath: 'path' });
      if (!db.objectStoreNames.contains('assignments')) db.createObjectStore('assignments', { keyPath: 'notePath' });
      if (!db.objectStoreNames.contains('outbox'))      db.createObjectStore('outbox', { keyPath: 'id', autoIncrement: true });
      if (!db.objectStoreNames.contains('meta'))        db.createObjectStore('meta', { keyPath: 'key' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

function tx(store, mode, fn) {
  return openDB().then(db => new Promise((resolve, reject) => {
    const t = db.transaction(store, mode);
    const result = fn(t.objectStore(store));
    t.oncomplete = () => resolve(result?._value ?? result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  }));
}

// Wrap an IDBRequest so its result survives until tx.oncomplete.
function reqValue(request, holder) {
  request.onsuccess = () => { holder._value = request.result; };
  return holder;
}

// ── reviewNotes ──────────────────────────────────────────────────────────────
export const putReviewNotes = (notes) =>
  tx('reviewNotes', 'readwrite', (s) => { s.clear(); notes.forEach(n => s.put(n)); });

export const getAllReviewNotes = () =>
  tx('reviewNotes', 'readonly', (s) => reqValue(s.getAll(), {}));

export const getReviewNote = (path) =>
  tx('reviewNotes', 'readonly', (s) => reqValue(s.get(path), {}));

// ── assignments (offline flashcards) ──────────────────────────────────────────
export const putAssignments = (list) =>
  tx('assignments', 'readwrite', (s) => { s.clear(); list.forEach(a => s.put(a)); });

export const getAssignmentByNote = (notePath) =>
  tx('assignments', 'readonly', (s) => reqValue(s.get(notePath), {}));

// All prebuilt assignments — used to tag which offline due notes have flashcards
// (hasCards) for the hybrid-review split.
export const getAllAssignments = () =>
  tx('assignments', 'readonly', (s) => reqValue(s.getAll(), {}));

// ── outbox ───────────────────────────────────────────────────────────────────
export const addToOutbox = (entry) =>
  tx('outbox', 'readwrite', (s) => { s.add({ ...entry, ts: Date.now() }); });

export const getOutbox = () =>
  tx('outbox', 'readonly', (s) => reqValue(s.getAll(), {}));

export const deleteFromOutbox = (id) =>
  tx('outbox', 'readwrite', (s) => { s.delete(id); });

// ── meta ─────────────────────────────────────────────────────────────────────
export const setMeta = (key, value) =>
  tx('meta', 'readwrite', (s) => { s.put({ key, value }); });

// meta rows are stored as { key, value }; callers want the VALUE, not the record. Unwrap it
// here (returning null for a missing row) — otherwise every reader gets `{key,value}` and
// e.g. `getMeta('inboxItems')` looks like an object, not the array (crashed Learn), and
// `getCreds().driveFolderId` is undefined (broke the Drive test read).
export const getMeta = async (key) => {
  const rec = await tx('meta', 'readonly', (s) => reqValue(s.get(key), {}));
  return rec && 'value' in rec ? rec.value : null;
};
