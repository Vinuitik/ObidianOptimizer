// Endpoint config — persisted in chrome.storage.local. Shared by background.js,
// popup.js and onboarding.js.
//
// obsidianApi: the user's own backend API base (`<server>/api`), set by onboarding:
//   - a self-host name ("alice") resolved via the registry → https://alice.obsidianoptimizer.uk
//   - or a full custom URL (owner / dev / localhost).
// There is NO built-in default server: an unconfigured install shows setup, it never
// talks to someone else's host. (Installs that predate this are migrated — see
// background.js onInstalled.)

// Cross-browser WebExtension API handle. Firefox exposes the promise-based
// `browser.*` namespace; Chrome/Edge/Brave expose `chrome.*` (also promise-based
// under MV3). Picking `browser ?? chrome` means every `await api.*` call below
// returns a real promise in BOTH engines — no polyfill needed.
export const api = globalThis.browser ?? globalThis.chrome;

export const REGISTRY = 'https://registry.obsidianoptimizer.uk';
export const USER_DOMAIN = 'obsidianoptimizer.uk';
export const LEGACY_DEFAULT_API = 'https://obsidianoptimizer.uk/api';
export const NAME_RE = /^[a-z][a-z0-9-]{2,30}$/;

// ── Pure URL helpers (unit-tested in config.test.mjs) ───────────────────────────
const LOCAL_HOSTS = ['localhost', '127.0.0.1', '[::1]'];

// Any user input → canonical server base (origin only, no trailing slash, no /api).
// Bare hosts get https:// (http:// for localhost/127.0.0.1/::1). Returns null if unusable.
export function normalizeServerUrl(input) {
  let t = String(input ?? '').trim();
  if (!t) return null;
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(t)) {
    const local = /^(localhost|127\.0\.0\.1|\[::1\])(:|\/|$)/i.test(t);
    t = `${local ? 'http' : 'https'}://${t}`;
  }
  let u;
  try { u = new URL(t); } catch { return null; }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
  if (!u.hostname || u.username || u.password) return null;
  if (u.protocol === 'http:' && !LOCAL_HOSTS.includes(u.hostname)) return null; // http only for loopback
  return u.origin;
}
export const apiOf = (base) => `${base}/api`;
export const baseOfApi = (apiUrl) => String(apiUrl || '').replace(/\/api\/?$/, '').replace(/\/+$/, '');
export const hostnameForName = (name) => `${name}.${USER_DOMAIN}`;
// chrome.permissions match pattern (ports are not allowed in host match patterns)
export const originPattern = (base) => { const u = new URL(base); return `${u.protocol}//${u.hostname}/*`; };
// Already covered by manifest.json's static host_permissions?
export function isStaticallyCovered(base) {
  const u = new URL(base);
  if (u.protocol === 'http:') return LOCAL_HOSTS.includes(u.hostname);
  return u.hostname.endsWith(`.${USER_DOMAIN}`);
}
// CF Access (email OTP) answers an unauthenticated request with a redirect to
// <team>.cloudflareaccess.com. fetch follows it, so the tell is the FINAL url's host.
export function isAccessRedirect(res) {
  try { return !!res && new URL(res.url).hostname.endsWith('.cloudflareaccess.com'); }
  catch { return false; }
}

// name → base URL via the registry. Throws Error with .code 'bad-name'|'not-found'|'registry'.
export async function resolveName(name, fetchFn = globalThis.fetch) {
  const n = String(name ?? '').trim().toLowerCase();
  const fail = (code, msg) => Object.assign(new Error(msg), { code });
  if (!NAME_RE.test(n)) throw fail('bad-name', 'Names are 3-31 characters: letters, digits, dashes, starting with a letter.');
  let res;
  try { res = await fetchFn(`${REGISTRY}/v1/resolve?name=${encodeURIComponent(n)}`); }
  catch { throw fail('registry', 'Could not reach the registry. Check your connection.'); }
  if (res.status === 404) throw fail('not-found', `No site named “${n}” is registered.`);
  if (!res.ok) throw fail('registry', `Registry error (${res.status}).`);
  let hostname;
  try { ({ hostname } = await res.json()); } catch { /* fallthrough */ }
  const base = normalizeServerUrl(`https://${hostname}`);
  if (!base || !new URL(base).hostname.endsWith(`.${USER_DOMAIN}`)) throw fail('registry', 'Registry returned an unexpected host.');
  return base;
}

// ── Storage ─────────────────────────────────────────────────────────────────────
export async function getConfig() {
  return await api.storage.local.get(['obsidianApi', 'agentWsToken']);   // obsidianApi undefined until set up
}
export const isConfigured = (cfg) => !!cfg?.obsidianApi;

export async function setConfig(patch) {
  await api.storage.local.set(patch);
}

// Ask for runtime access to a custom server origin (no-op if statically covered).
// MUST be called from a user gesture (popup/onboarding click).
export async function ensureHostAccess(base) {
  if (isStaticallyCovered(base)) return true;
  const origins = [originPattern(base)];
  try {
    if (await api.permissions.contains({ origins })) return true;
    return await api.permissions.request({ origins });
  } catch { return false; }
}

// Single write path for the server setting: permission → store.
export async function saveServer(base) {
  if (!(await ensureHostAccess(base))) return { ok: false, error: 'Permission to reach that server was not granted.' };
  await setConfig({ obsidianApi: apiOf(base) });
  return { ok: true, base };
}

// ── Shared by onboarding + settings ──────────────────────────────────────────────
// One field, two shapes: a bare name ("alice", no dots) → registry; anything else → URL.
// Returns { ok, base } | { ok:false, error }. Does NOT store — call saveServer(base).
export async function resolveServerInput(input, fetchFn) {
  const t = String(input ?? '').trim();
  if (!t) return { ok: false, error: 'Enter your site name or a full URL.' };
  if (!/[.:/]/.test(t)) {
    try { return { ok: true, base: await resolveName(t, fetchFn) }; }
    catch (e) { return { ok: false, error: e.message }; }
  }
  const base = normalizeServerUrl(t);
  return base ? { ok: true, base } : { ok: false, error: 'That is not a valid URL (https required, http only for localhost).' };
}

// ── Remembered credentials (opt-in) ──────────────────────────────────────────────
// The backend session is a short-lived in-memory Spring session that dies on every
// server restart, so the cookie keeps going stale and the user re-logs in constantly.
// With "Remember me" we stash the credentials in chrome.storage.local and background.js
// silently re-logs in on any 401 — so a capture never fails just because the session
// lapsed. Trade-off: the password sits in extension storage in plain text. Acceptable for
// a single-user personal tool; clear it by unchecking "Remember me" (clearCreds).
export async function getCreds() {
  return await api.storage.local.get(['authUser', 'authPass']);
}
export async function setCreds(authUser, authPass) {
  await api.storage.local.set({ authUser, authPass });
}
export async function clearCreds() {
  await api.storage.local.remove(['authUser', 'authPass']);
}
