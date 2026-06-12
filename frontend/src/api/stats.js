import ENV from '../env.js';

const BASE = ENV.API_BASE;

// Aggregated processing counters for the info dashboard.
// Shape: { embedding, images, flashcards, resources, wrapper } — see StatsController.java
export async function fetchStats() {
  const res = await fetch(`${BASE}/stats`, { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}
