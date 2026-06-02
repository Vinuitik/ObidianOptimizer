const BASE = '/api';

export async function fetchNames() {
  const res = await fetch(`${BASE}/names`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export async function fetchReview() {
  const res = await fetch(`${BASE}/review`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export async function fetchNoteContent(fullPath) {
  const res = await fetch(`${BASE}/text?noteName=${encodeURIComponent(fullPath)}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.text();
}
