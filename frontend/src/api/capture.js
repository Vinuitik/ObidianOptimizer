import ENV from '../env.js';

const BASE = ENV.API_BASE;

async function req(url, options = {}) {
  const res = await fetch(`${BASE}${url}`, {
    credentials: 'same-origin',
    cache: 'no-store',
    ...options,
  });
  if (!res.ok) {
    let msg = String(res.status);
    try { msg = (await res.json()).error ?? msg; } catch { /* not json */ }
    throw new Error(msg);
  }
  return res.json();
}

// Captures that hard-failed (yt-dlp rejected the URL, embedder 4xx, ...). Auto-retried
// forever on a standing cadence server-side; this just surfaces them + lets a human
// force a retry now or give up. Returns [{id, sourceType, sourceRef, title, lastError,
// retryCount, createdAt}, ...].
export function fetchFailedCaptures() {
  return req('/capture/failed');
}

// Retry a failed capture right now instead of waiting for the next scheduled pass.
export function retryCapture(id) {
  return req(`/capture/${id}/retry`, { method: 'POST' });
}

// Give up on a failed capture — stop showing/retrying it.
export function dismissCapture(id) {
  return req(`/capture/${id}/dismiss`, { method: 'POST' });
}

// Submit one resource into the durable capture pipeline, tagged to an existing track
// (TracksPage.jsx's batch "Add resources" form). Doesn't use req() — a 409 here means
// "already in the pipeline", not an error, so the caller needs it distinguished rather
// than thrown.
export async function captureResource(url, trackId) {
  const res = await fetch(`${BASE}/capture`, {
    credentials: 'same-origin',
    cache: 'no-store',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url, trackId }),
  });
  if (res.status === 409) return { ok: true, duplicate: true };
  if (!res.ok) {
    let msg = String(res.status);
    try { msg = (await res.json()).error ?? msg; } catch { /* not json */ }
    return { ok: false, error: msg };
  }
  return { ok: true, duplicate: false };
}

// Upload a file (PDF/audio/video/...) into the capture pipeline, tagged to a track.
// Multipart: no Content-Type header (the browser sets the boundary).
export async function captureFile(file, trackId) {
  const form = new FormData();
  form.append('file', file);
  if (trackId != null) form.append('trackId', String(trackId));
  const res = await fetch(`${BASE}/capture/file`, {
    credentials: 'same-origin',
    cache: 'no-store',
    method: 'POST',
    body: form,
  });
  if (res.status === 409) return { ok: true, duplicate: true };
  if (!res.ok) {
    let msg = String(res.status);
    try { msg = (await res.json()).error ?? msg; } catch { /* not json */ }
    return { ok: false, error: msg };
  }
  return { ok: true, duplicate: false };
}
