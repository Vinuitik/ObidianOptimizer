/* Obsidian Optimizer — service worker (PWA).
 *
 * Hand-written (no Workbox) so it ships without adding a build dependency. If you
 * later add `vite-plugin-pwa` (see src/pwa/vite-pwa.config.js) move precaching
 * there and keep ONLY the share-target handler here.
 *
 * Responsibilities:
 *   1. App shell — cache index.html + hashed assets so the app launches offline.
 *   2. Media     — cache-first for files the "Download for offline" sync stored.
 *   3. Capture   — intercept POST /share-target from Android's share sheet.
 *
 * Scope is "/" because this file is served from the web root (public/ → root).
 * Service workers require a secure context: real HTTPS (Cloudflare tunnel) or
 * localhost. The stack's self-signed :8443 cert will block registration — see
 * architecture_plans/PWA_MOBILE_ARCH.md §9.
 */

const VERSION = 'v1';
const SHELL_CACHE = `obsopt-shell-${VERSION}`;
const MEDIA_CACHE = 'obsopt-media'; // unversioned: managed by the offline sync, not the SW

// Minimal app shell. Hashed JS/CSS are cached at runtime on first online visit.
const SHELL_URLS = ['/', '/index.html', '/manifest.webmanifest'];

// ── IndexedDB outbox (mirrors src/pwa/db.js — kept inline so the SW is standalone) ──
const DB_NAME = 'obsopt-offline';
const DB_VERSION = 2;   // keep in sync with src/pwa/db.js (added 'assignments' store)

function openDB() {
  return new Promise((resolve, reject) => {
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
}

async function enqueueOutbox(entry) {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('outbox', 'readwrite');
    tx.objectStore('outbox').add({ ...entry, ts: Date.now() });
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

// ── Lifecycle ──────────────────────────────────────────────────────────────
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((c) => c.addAll(SHELL_URLS)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.filter((k) => k.startsWith('obsopt-shell-') && k !== SHELL_CACHE).map((k) => caches.delete(k))
      )
    ).then(() => self.clients.claim())
  );
});

// ── Fetch routing ────────────────────────────────────────────────────────────
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Share-target POST from Android's share sheet.
  if (request.method === 'POST' && url.pathname === '/share-target') {
    event.respondWith(handleShareTarget(request));
    return;
  }

  if (request.method !== 'GET') return; // never cache writes

  // Navigations → network-first, fall back to cached shell so the app opens offline.
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request).catch(() => caches.match('/index.html'))
    );
    return;
  }

  // Same-origin media → cache-first (populated by the offline sync).
  if (url.origin === self.location.origin && isMedia(url.pathname)) {
    event.respondWith(cacheFirst(request, MEDIA_CACHE));
    return;
  }

  // Hashed build assets → stale-while-revalidate into the shell cache.
  if (url.origin === self.location.origin && isAsset(url.pathname)) {
    event.respondWith(staleWhileRevalidate(request, SHELL_CACHE));
    return;
  }

  // Everything else (incl. /api data GETs) → straight to network. Offline data
  // reads are served from IndexedDB by src/pwa/offlineApi.js, not the SW.
});

function isMedia(path) {
  return /\.(png|jpe?g|gif|webp|svg|pdf|mp4|mkv|webm|mov|avi|mp3|m4a|wav|ogg|flac)$/i.test(path)
    || path.startsWith('/api/images/') || path.startsWith('/images/');
}

function isAsset(path) {
  return path.startsWith('/assets/') || /\.(js|css|woff2?|ttf)$/i.test(path);
}

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const hit = await cache.match(request);
  if (hit) return hit;
  try {
    const res = await fetch(request);
    if (res.ok) cache.put(request, res.clone());
    return res;
  } catch (e) {
    return hit || Response.error();
  }
}

async function staleWhileRevalidate(request, cacheName) {
  const cache = await caches.open(cacheName);
  const hit = await cache.match(request);
  const fetching = fetch(request).then((res) => {
    if (res.ok) cache.put(request, res.clone());
    return res;
  }).catch(() => hit);
  return hit || fetching;
}

// ── Share target ──────────────────────────────────────────────────────────────
// Android puts a shared link in `url` OR `text` (varies by source app). Extract a
// URL from either, then POST it to the backend's capture endpoint. Offline → queue.
async function handleShareTarget(request) {
  let form = null;
  try {
    form = await request.formData();
  } catch (e) {
    return Response.redirect('/capture?shared=err', 303);
  }

  // A shared FILE (PDF / video / audio) → multipart to /api/capture/file. Checked first:
  // some apps share a file AND a title, and the file is the thing we want to ingest.
  const file = form.get('file');
  if (file && typeof file.name === 'string' && file.size > 0) {
    return handleShareFile(file, form.get('title'));
  }

  // Otherwise a shared LINK — Android puts it in `url` OR `text` (varies by source app).
  const shared = (form.get('url') || extractUrl(form.get('text')) || form.get('title') || '').toString().trim();
  if (!shared) return Response.redirect('/capture?shared=err', 303);

  try {
    const res = await fetch('/api/capture', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ url: shared }),
    });
    if (res.ok) return Response.redirect('/capture?shared=ok', 303);
    if (res.status === 401) {
      await enqueueOutbox({ kind: 'capture', url: shared });
      return Response.redirect('/capture?shared=auth', 303);
    }
    throw new Error('capture failed: ' + res.status);
  } catch (e) {
    await enqueueOutbox({ kind: 'capture', url: shared });
    return Response.redirect('/capture?shared=queued', 303);
  }
}

// Shared file → /api/capture/file (multipart). Offline / 401 → queue the Blob in the outbox
// (kind 'captureFile'); the client replays it on reconnect (src/pwa/outbox.js flush()).
async function handleShareFile(file, title) {
  const fd = new FormData();
  fd.append('file', file, file.name);
  if (title) fd.append('title', title.toString());
  try {
    const res = await fetch('/api/capture/file', {
      method: 'POST', credentials: 'same-origin', body: fd,
    });
    if (res.ok) return Response.redirect('/capture?shared=ok', 303);
    if (res.status === 401) {
      await enqueueOutbox({ kind: 'captureFile', blob: file, filename: file.name });
      return Response.redirect('/capture?shared=auth', 303);
    }
    throw new Error('capture/file failed: ' + res.status);
  } catch (e) {
    await enqueueOutbox({ kind: 'captureFile', blob: file, filename: file.name });
    return Response.redirect('/capture?shared=queued', 303);
  }
}

function extractUrl(text) {
  if (!text) return '';
  const m = String(text).match(/https?:\/\/[^\s]+/);
  return m ? m[0] : '';
}
