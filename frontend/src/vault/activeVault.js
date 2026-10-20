// Active-vault identity on the client (VAULTS_PLAN M7). The SERVER owns which vault is active
// (vaults.active); the client mirrors it in localStorage `activeVaultId` so synchronous code
// (IDB names, media URLs, request headers) can read it without awaiting. Refreshed from
// GET /api/vaults on boot (bootVault) and on any 409 vault_mismatch.
//
// Vault 1 keeps the legacy names/URLs (IDB `obsopt-offline`, cache `obsopt-media`, bare
// `/vault-media/`); every other vault gets a `-v<id>` / `/<id>/` suffix.
// public/sw.js duplicates dbNameFor/mediaCacheNameFor/the `obsopt-meta` read — keep in sync.

export const PRIMARY_VAULT_ID = 1;
const LS_KEY = 'activeVaultId';
export const META_DB = 'obsopt-meta';   // tiny IDB the service worker can read (it has no localStorage)

export function getActiveVaultId() {
  try {
    const n = Number(localStorage.getItem(LS_KEY));
    return Number.isInteger(n) && n >= 1 ? n : PRIMARY_VAULT_ID;
  } catch { return PRIMARY_VAULT_ID; }
}

export const dbNameFor = (id = getActiveVaultId()) =>
  id === PRIMARY_VAULT_ID ? 'obsopt-offline' : `obsopt-offline-v${id}`;
export const mediaCacheNameFor = (id = getActiveVaultId()) =>
  id === PRIMARY_VAULT_ID ? 'obsopt-media' : `obsopt-media-v${id}`;
// '/vault-media/' (vault 1) or '/vault-media/<id>/'; same for '/workspace/'.
export const mediaPrefix = (kind /* 'vault-media'|'workspace' */, id = getActiveVaultId()) =>
  id === PRIMARY_VAULT_ID ? `/${kind}/` : `/${kind}/${id}/`;

function writeMetaDb(id) {
  if (typeof indexedDB === 'undefined') return Promise.resolve();
  return new Promise((resolve) => {
    try {
      const req = indexedDB.open(META_DB, 1);
      req.onupgradeneeded = () => req.result.createObjectStore('kv', { keyPath: 'key' });
      req.onsuccess = () => {
        const t = req.result.transaction('kv', 'readwrite');
        t.objectStore('kv').put({ key: 'activeVaultId', value: id });
        t.oncomplete = () => { req.result.close(); resolve(); };
        t.onerror = t.onabort = () => resolve();
      };
      req.onerror = () => resolve();
    } catch { resolve(); }
  });
}

// Persist the active id for sync readers (localStorage) and the SW (obsopt-meta).
// localStorage keys that hold vault-specific state (review/flashcard session, failed-capture
// counter). Dropped when the active id CHANGES so a session from vault A never resumes in B.
// (Drive link, lastSync, outbox etc. live in the per-vault IDB, so need no clearing.)
const VAULT_SCOPED_LS = ['obsOpt_reviewSession', 'obsOpt_flashSession', 'obs.failedCaptureCount'];

export async function setActiveVaultId(id) {
  try {
    if (getActiveVaultId() !== id) {
      VAULT_SCOPED_LS.forEach(k => localStorage.removeItem(k));
    }
    localStorage.setItem(LS_KEY, String(id));
  } catch { /* private mode */ }
  await writeMetaDb(id);
}

// GET /api/vaults -> adopt the server's active vault. Offline/unreachable keeps the stored id.
// Returns { changed, list }.
export async function bootVault(fetchImpl = (...a) => fetch(...a)) {
  try {
    // 3s cap: a hanging server must not hold up first render (offline boot keeps the stored id).
    const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timer = setTimeout(() => ctl?.abort(), 3000);
    let res;
    try { res = await fetchImpl('/api/vaults', { credentials: 'same-origin', cache: 'no-store', signal: ctl?.signal }); }
    finally { clearTimeout(timer); }
    if (!res.ok) return { changed: false, list: null };
    const list = await res.json();
    const active = Array.isArray(list) ? list.find(v => v.active) : null;
    if (!active) return { changed: false, list };
    const changed = active.id !== getActiveVaultId();
    await setActiveVaultId(active.id);
    return { changed, list };
  } catch { return { changed: false, list: null }; }
}

// Wraps fetch: same-origin /api/** requests (except /api/vaults**, which the server exempts
// so a stale tab can still switch) carry X-Vault-Id. A 409 {error:'vault_mismatch'} means
// another tab/device switched vaults: adopt the server's active id and reload.
export function vaultFetch(baseFetch, { reload = () => location.reload() } = {}) {
  return async function (input, init) {
    const url = typeof input === 'string' ? input : (input?.url ?? '');
    const isApi = /^\/api\//.test(url) && !/^\/api\/vaults(\/|\?|$)/.test(url);
    let res;
    if (isApi) {
      const headers = new Headers(init?.headers || (typeof input !== 'string' ? input.headers : undefined));
      headers.set('X-Vault-Id', String(getActiveVaultId()));
      res = await baseFetch(input, { ...init, headers });
    } else {
      res = await baseFetch(input, init);
    }
    if (isApi && res.status === 409) {
      try {
        const body = await res.clone().json();
        if (body?.error === 'vault_mismatch') {
          await setActiveVaultId(body.activeVaultId);
          reload();
        }
      } catch { /* not the vault 409 */ }
    }
    return res;
  };
}

export function installVaultFetch() {
  if (window.__vaultFetchInstalled) return;
  window.__vaultFetchInstalled = true;
  window.fetch = vaultFetch(window.fetch.bind(window));
}
