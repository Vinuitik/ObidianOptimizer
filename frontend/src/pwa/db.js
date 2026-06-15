// IndexedDB wrapper (no dependency — raw IDB wrapped in promises). Mirrors the
// store schema inlined in public/sw.js; keep the two in sync if you change it.
//
// Stores:
//   reviewNotes  keyPath 'path'  — { path, shortName, content, media:[urls], due }
//   outbox       autoIncrement   — { id, kind:'grade'|'capture', ...payload, ts }
//   meta         keyPath 'key'   — { key, value }  (e.g. lastSync timestamp)

const DB_NAME = 'obsopt-offline';
const DB_VERSION = 1;

let dbPromise = null;

export function openDB() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains('reviewNotes')) db.createObjectStore('reviewNotes', { keyPath: 'path' });
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

export const getMeta = (key) =>
  tx('meta', 'readonly', (s) => reqValue(s.get(key), {}));
