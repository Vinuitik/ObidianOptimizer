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

// Returns the created Track.
export async function createTrack(title, type) {
  const res = await req(`${BASE}/tracks`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, type }),
  });
  return res.json();
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
