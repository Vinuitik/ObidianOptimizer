import { useEffect, useState } from 'react';
import useStore from '../store/useStore';
import useOffline from './useOffline';
import { getMeta } from './db';
import { fetchFailedCaptures } from '../api/capture';
import { OUTBOX_RETRY_MS } from './offlineApi';

// Short relative time for the chip: "just now", "5m ago", "2h ago", "3d ago".
export function agoShort(ts, now = Date.now()) {
  if (!ts) return null;
  const mins = Math.max(0, Math.round((now - ts) / 60000));
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.round(hrs / 24)}d ago`;
}

// Pure: the TopBar chip's state. Priority: offline > syncing > failed > synced/never.
// tone maps to Badge tones. To change wording: this function only.
export function deriveSyncStatus({ online, stage, failedCount = 0, lastSync = null, now = Date.now() }) {
  if (!online) return { state: 'offline', label: 'Offline', tone: 'ember' };
  if (stage) return { state: 'syncing', label: 'Syncing…', tone: 'accent' };
  if (failedCount > 0) return { state: 'failed', label: `${failedCount} failed`, tone: 'danger' };
  const ago = agoShort(lastSync, now);
  if (!ago) return { state: 'never', label: 'Not synced', tone: 'neutral' };
  return { state: 'synced', label: `Synced ${ago}`, tone: 'success' };
}

// Live status for the chip: online (useOffline), syncStage (store), lastSync (IndexedDB meta,
// re-read when a sync finishes + every minute so "2h ago" ticks), failed captures (polled).
export default function useSyncStatus() {
  const online = useOffline();
  const stage = useStore(s => s.syncStage);
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const [lastSync, setLastSync] = useState(null);
  const [failedCount, setFailedCount] = useState(0);
  const [, tick] = useState(0);

  useEffect(() => {
    getMeta('lastSync').then(v => setLastSync(v || null)).catch(() => {});
  }, [stage]);

  useEffect(() => {
    const id = setInterval(() => tick(n => n + 1), 60000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    if (!isAuthenticated || !online) return undefined;
    let alive = true;
    const load = () => fetchFailedCaptures()
      .then(l => { if (alive) setFailedCount(Array.isArray(l) ? l.length : 0); })
      .catch(() => {});
    load();
    const id = setInterval(load, OUTBOX_RETRY_MS);
    return () => { alive = false; clearInterval(id); };
  }, [isAuthenticated, online]);

  return deriveSyncStatus({ online, stage, failedCount, lastSync });
}
