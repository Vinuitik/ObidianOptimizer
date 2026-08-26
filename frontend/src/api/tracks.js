import ENV from '../env.js';
import { ApiError } from './notes.js';

const BASE = ENV.API_BASE;

async function req(url, options = {}) {
  const res = await fetch(url, { credentials: 'same-origin', cache: 'no-store', ...options });
  if (!res.ok) throw new ApiError(res.status);
  return res;
}

// ── Tracks ───────────────────────────────────────────────────────────────────

// Returns Track[] (all statuses — Manage tab filters client-side).
export async function fetchTracks() {
  const res = await req(`${BASE}/tracks`);
  return res.json();
}

// Returns the created Track. extra: optional { sourceUrl, sourceType } for subscription tracks.
export async function createTrack(title, type, extra = {}) {
  const res = await req(`${BASE}/tracks`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, type, ...extra }),
  });
  return res.json();
}

// 400 if the track isn't a subscription track. No response body to parse — caller
// re-fetches tracks afterward to pick up the new lastCheckedAt.
export async function pollTrackNow(id) {
  await req(`${BASE}/tracks/${id}/poll-now`, { method: 'POST' });
}

// patch: any of { title, type, status, deadline (YYYY-MM-DD), priority, includeInProgress, clearDeadline }.
export async function updateTrack(id, patch) {
  const res = await req(`${BASE}/tracks/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  return res.json();
}

export async function deleteTrack(id) {
  await req(`${BASE}/tracks/${id}`, { method: 'DELETE' });
}

// ── Items ────────────────────────────────────────────────────────────────────

export async function fetchTrackItems(trackId) {
  const res = await req(`${BASE}/tracks/${trackId}/items`);
  return res.json();
}

export async function addTrackItem(trackId, title, notePath) {
  const res = await req(`${BASE}/tracks/${trackId}/items`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, notePath: notePath ?? null }),
  });
  return res.json();
}

// patch: { title? } and/or { position? } (position = reorder within the track).
export async function updateTrackItem(itemId, patch) {
  const res = await req(`${BASE}/tracks/items/${itemId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  return res.json();
}

export async function deleteTrackItem(itemId) {
  await req(`${BASE}/tracks/items/${itemId}`, { method: 'DELETE' });
}

// Returns the completed item. addToReview seeds the note into the FSRS pool (sr-due=today).
export async function completeTrackItem(itemId, addToReview = false) {
  const res = await req(`${BASE}/tracks/items/${itemId}/complete`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ addToReview }),
  });
  return res.json();
}

// ── Schedule ─────────────────────────────────────────────────────────────────

// Returns { [weekday]: dailyItemBudget } — 0=Mon..6=Sun, only days actually scheduled.
export async function fetchTrackSchedule(trackId) {
  const res = await req(`${BASE}/tracks/${trackId}/schedule`);
  return res.json();
}

// weekdayBudgets: { [weekday]: dailyItemBudget } — full replace.
export async function saveTrackSchedule(trackId, weekdayBudgets) {
  const res = await req(`${BASE}/tracks/${trackId}/schedule`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(weekdayBudgets),
  });
  return res.json();
}

// ── Today (Phase 1c: capacity/deadline/MoSCoW-aware) ──────────────────────────

// Returns { items: [{ itemId, trackId, trackTitle, trackType, title, notePath }, ...],
//   mode: 'normal'|'lockin', overBudget: bool }.
// overBudget = Normal mode's must-priority deadline tracks alone exceeded today's capacity
// and got pro-rata trimmed anyway (not a silent drop) — show the "you're behind" banner.
export async function fetchTodayPlan() {
  const res = await req(`${BASE}/tracks/today`);
  return res.json();
}

// weekday(0=Mon..6=Sun) -> items/day ceiling for that day (Normal mode only).
export async function fetchCapacity() {
  const res = await req(`${BASE}/tracks/capacity`);
  return res.json();
}

// weekdayCapacities: { [weekday]: capacity } — partial upsert, only given days change.
export async function saveCapacity(weekdayCapacities) {
  const res = await req(`${BASE}/tracks/capacity`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(weekdayCapacities),
  });
  return res.json();
}

// mode: 'normal' | 'lockin'.
export async function setTrackMode(mode) {
  const res = await req(`${BASE}/tracks/mode`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode }),
  });
  return res.json();
}

// ── Progress (Phase 1d) ─────────────────────────────────────────────────────

// Returns TrackProgress[] — { id, title, type, itemsDone, itemsTotal, deadline, onTrack },
// excludes archived/paused tracks and anything with includeInProgress=false.
// onTrack is null for tracks with no deadline (nothing to pace against).
export async function fetchTrackProgress() {
  const res = await req(`${BASE}/tracks/progress`);
  return res.json();
}

// ── Mini-course (Phase 2) ────────────────────────────────────────────────────

// Returns the created job dict — { id, status, stage, track_id, course_title, error,
// created_at, plan, results, lesson_failures }.
export async function generateMinicourse(trackId) {
  const res = await req(`${BASE}/tracks/${trackId}/minicourse`, { method: 'POST' });
  return res.json();
}

// Returns the job dict — poll this while status is QUEUED/RUNNING.
export async function fetchMinicourseJob(jobId) {
  const res = await req(`${BASE}/tracks/minicourse/${jobId}`);
  return res.json();
}

// approvedIndexes: number[] of plan.lessons indexes to keep, or null to approve all.
export async function approveMinicourse(jobId, approvedIndexes) {
  const res = await req(`${BASE}/tracks/minicourse/${jobId}/approve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approvedIndexes }),
  });
  return res.json();
}

// ── Import (Phase 3) ─────────────────────────────────────────────────────────

// Returns { tracks: [{ title, type }], items: [{ trackIndex, title, status }] }.
// On 422 (LLM couldn't parse the CSV), throws an Error whose message is the backend's
// detail string — req()'s ApiError only carries the status, not the body, so that path
// alone would surface as an unhelpful "HTTP 422".
export async function importCsv(csvText) {
  const res = await fetch(`${BASE}/tracks/import`, {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ csvText }),
  });
  if (!res.ok) {
    let detail;
    try { detail = (await res.json()).detail; } catch { /* not JSON */ }
    throw new Error(detail || `HTTP ${res.status}`);
  }
  return res.json();
}

// tracks: [{ title, type }], items: [{ trackIndex, title, status }] — the (possibly edited)
// preview state. Returns { tracksCreated, itemsCreated }.
export async function commitImport({ tracks, items }) {
  const res = await req(`${BASE}/tracks/import/commit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tracks, items }),
  });
  return res.json();
}
