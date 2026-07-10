// Cron-like auto-sync for the installed PWA: keep the offline review set fresh without the
// user tapping "Download for offline". A PWA can't run a true OS-level cron across browsers
// — Periodic Background Sync is Chrome/Android-only and browser-throttled to ~12h — so this
// is FOREGROUND-driven: MobileLayout calls it on launch, on reconnect, on tab-focus, and on
// a light interval while the app is open (see MobileLayout.jsx). The FRESH_MS gate makes the
// frequent triggers cheap: they no-op unless the set is actually stale.
import { getMeta } from './db';
import { hasCreds, refreshCreds } from './setup';
import { refreshAndPull } from './drivePull';
import { pushMailbox } from './mailbox';

const FRESH_MS = 6 * 60 * 60 * 1000;   // set is "fresh" for 6h; older → re-pull

let inflight = null;

// Pull the freshest Drive bundle if linked, online, and stale (or force=true). Coalesces
// concurrent callers and NEVER throws — auto-sync is best-effort background hygiene, so a
// failure just leaves the last-good offline set in place. Returns the pull result or null
// when it skipped/failed.
export async function maybeAutoSync({ force = false } = {}) {
  if (inflight) return inflight;
  inflight = (async () => {
    try {
      if (!navigator.onLine) return null;
      if (!(await hasCreds().catch(() => false))) return null;   // not Drive-linked → skip
      const last = await getMeta('lastSync').catch(() => null);
      if (!force && last && Date.now() - last < FRESH_MS) return null;
      await refreshCreds().catch(() => {});   // re-read server creds (folder id / rotated token)
      await pushMailbox().catch(() => {});    // send my grades up before pulling the new set
      return await refreshAndPull();          // rebuild-on-server (if up) + pull → IndexedDB
    } catch {
      return null;                            // best-effort: keep the existing set
    } finally {
      inflight = null;
    }
  })();
  return inflight;
}
