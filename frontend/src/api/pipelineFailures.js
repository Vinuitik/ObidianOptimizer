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

// The shared pipeline_failures ledger (QUEUE_UNIFICATION_PLAN.md) — every backend
// pipeline (ingest stages, capture dead-letters, the extension's own client-side
// dead-ends) writes here on a failure that would otherwise vanish silently. Returns
// [{id, occurredAt, source, stage, inputPayload, errorType, errorMessage, bundleRef,
// resolvedAt}, ...], newest first.
export function fetchPipelineFailures({ onlyOpen = true, source, stage, limit = 200 } = {}) {
  const params = new URLSearchParams({ onlyOpen: String(onlyOpen), limit: String(limit) });
  if (source) params.set('source', source);
  if (stage) params.set('stage', stage);
  return req(`/pipeline-failures?${params}`);
}

// Debugging ledger, not a retry queue — no generic replay (payload shapes differ per
// source/stage). This just marks a row looked-at so it drops out of the open view.
export function resolvePipelineFailure(id) {
  return req(`/pipeline-failures/${id}/resolve`, { method: 'POST' });
}
