import { useState, useEffect } from 'react';
import useStore from '../store/useStore';
import useOffline from './useOffline';
import { syncForOffline } from './syncOffline';
import { flushOutbox } from './offlineApi';
import { getMeta } from './db';
import { linkDevice, hasCreds, unlinkDevice, proofReadNote } from './setup';
import { refreshAndPull, pullReviewFromDrive } from './drivePull';
import { pushMailbox } from './mailbox';
import { notificationsSupported, notificationPermission, requestNotificationPermission } from './quitNotify';
import { fetchFailedCaptures, retryCapture, dismissCapture } from '../api/capture';
import styles from './MobilePages.module.css';

// Baseline for "did the failed-capture count grow since last time we checked", persisted
// across visits/reloads — a plain localStorage number is enough for a per-device counter.
const FAILED_COUNT_KEY = 'obs.failedCaptureCount';

// The PWA's Sync tab. "Download for offline" seeds the review subset (notes + text
// + media) into IndexedDB / Cache Storage so review keeps working with no network;
// it also replays anything captured while offline. Must run online, over the real-
// cert tunnel domain (the service worker is inactive on the self-signed :8443).
function ago(ts) {
  if (!ts) return 'never';
  const mins = Math.round((Date.now() - ts) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs} h ago`;
  return `${Math.round(hrs / 24)} d ago`;
}

// User-facing names for each download phase (stage key → label).
const STAGE_LABELS = {
  notes: 'notes', cards: 'flashcards', inbox: 'inbox',
  images: 'images', media: 'video & audio', pdf: 'PDF pages',
};
function stageText(s) {
  if (!s) return 'Downloading…';
  const label = STAGE_LABELS[s.stage] || s.stage;
  return `Downloading ${label}${s.total ? ` ${s.done}/${s.total}` : ''}…`;
}
// "12 images · 3 video/audio · 8 PDF pages" from a warm's byPhase counts (skip zeros).
function mediaSummary(byPhase) {
  if (!byPhase) return '';
  const parts = [];
  if (byPhase.images) parts.push(`${byPhase.images} image${byPhase.images > 1 ? 's' : ''}`);
  if (byPhase.media)  parts.push(`${byPhase.media} video/audio`);
  if (byPhase.pdf)    parts.push(`${byPhase.pdf} PDF page${byPhase.pdf > 1 ? 's' : ''}`);
  return parts.join(' · ');
}

export default function SyncPage() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const setShowLogin    = useStore(s => s.setShowLogin);
  const logout          = useStore(s => s.logout);
  const showToast       = useStore(s => s.showToast);
  const online          = useOffline();

  const [lastSync, setLastSync] = useState(null);
  const [busy, setBusy]         = useState(false);
  const [stage, setStage]       = useState(null); // { stage, done, total }
  const [status, setStatus]     = useState(null);  // { text, tone }
  const [linked, setLinked]     = useState(false);
  const [driveMsg, setDriveMsg] = useState(null);  // { text, tone }
  const [notifPerm, setNotifPerm] = useState(() => notificationPermission());
  const [failed, setFailed]     = useState([]);     // failed captures
  const [failedBusyId, setFailedBusyId] = useState(null);

  useEffect(() => {
    getMeta('lastSync').then(v => setLastSync(v || null)).catch(() => {});
    hasCreds().then(setLinked).catch(() => {});
  }, []);

  // Failed captures don't self-report — pull the list, and toast if it grew since the
  // last time this page checked (so a background failure isn't invisible unless the
  // user happens to come look).
  async function loadFailed() {
    try {
      const list = await fetchFailedCaptures();
      setFailed(list);
      const prevCount = Number(localStorage.getItem(FAILED_COUNT_KEY) ?? list.length);
      if (list.length > prevCount) {
        showToast(`${list.length} capture${list.length === 1 ? '' : 's'} failed — see below.`);
      }
      localStorage.setItem(FAILED_COUNT_KEY, String(list.length));
    } catch { /* not signed in yet, or offline — silently skip */ }
  }

  useEffect(() => {
    if (isAuthenticated) loadFailed();
  }, [isAuthenticated]);

  async function handleRetry(id) {
    setFailedBusyId(id);
    try {
      await retryCapture(id);
      showToast('Retry queued.');
      setFailed(f => f.filter(c => c.id !== id));
      localStorage.setItem(FAILED_COUNT_KEY, String(failed.length - 1));
    } catch (e) {
      showToast(`Retry failed: ${e.message ?? e}`);
    } finally {
      setFailedBusyId(null);
    }
  }

  async function handleDismiss(id) {
    setFailedBusyId(id);
    try {
      await dismissCapture(id);
      setFailed(f => f.filter(c => c.id !== id));
      localStorage.setItem(FAILED_COUNT_KEY, String(failed.length - 1));
    } catch (e) {
      showToast(`Dismiss failed: ${e.message ?? e}`);
    } finally {
      setFailedBusyId(null);
    }
  }

  async function enableNotifications() {
    setNotifPerm(await requestNotificationPermission());
  }

  async function link() {
    setBusy(true); setDriveMsg(null);
    try {
      await linkDevice();
      setLinked(true);
      setDriveMsg({ text: 'Linked. This device can now read Drive without the server.', tone: 'ok' });
    } catch (e) {
      setDriveMsg({ text: `${e.message ?? e}`, tone: 'err' });
    } finally { setBusy(false); }
  }

  async function unlink() {
    await unlinkDevice();
    setLinked(false);
    setDriveMsg({ text: 'Unlinked — credentials wiped from this device.', tone: 'warn' });
  }

  async function testDrive() {
    setBusy(true); setDriveMsg({ text: 'Reading a note from Drive…', tone: null });
    try {
      const r = await proofReadNote();
      setDriveMsg({ text: `✓ Decrypted “${r.path}” from Drive: ${r.preview}`, tone: 'ok' });
    } catch (e) {
      setDriveMsg({ text: `Drive read failed: ${e.message ?? e}`, tone: 'err' });
    } finally { setBusy(false); }
  }

  async function download() {
    setBusy(true); setStatus(null); setStage(null);
    try {
      if (linked) {
        // Push my grades to the Drive mailbox first (server drains them on its next boot),
        // then pull the freshest bundle. Works even with the laptop off.
        const up = await pushMailbox().catch(() => ({ pushed: 0 }));
        const res = online
          ? await refreshAndPull({ onStage: setStage })
          : await pullReviewFromDrive({ onStage: setStage });
        setLastSync(Date.now());
        const summary = mediaSummary(res.media?.byPhase);
        setStatus({
          text: `${up.pushed ? `Synced ${up.pushed} grade(s) · ` : ''}Pulled ${res.notes} notes`
            + `${summary ? ` · ${summary}` : ''} from Drive.`,
          tone: 'ok',
        });
      } else {
        await flushOutbox().catch(() => {});
        const res = await syncForOffline({ onStage: setStage });
        setLastSync(Date.now());
        const summary = mediaSummary(res.byPhase);
        setStatus({ text: `Downloaded ${res.notes} notes${summary ? ` · ${summary}` : ''} for offline.`, tone: 'ok' });
      }
    } catch (e) {
      setStatus({ text: `Sync failed: ${e.message ?? e}`, tone: 'err' });
    } finally {
      setBusy(false); setStage(null);
    }
  }

  const stale = lastSync && Date.now() - lastSync > 12 * 60 * 60 * 1000;

  return (
    <div className={styles.page}>
      <h1 className={styles.pageTitle}>Sync</h1>

      {/* ── Account ─────────────────────────────────────────────── */}
      {/* Always-reachable sign in / sign out. Without this, dismissing the auto-login
          modal left no way back in (and no way to sign out) from the installed PWA. */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
        marginBottom: 18, paddingBottom: 16, borderBottom: '1px solid var(--color-border, #262a35)',
      }}>
        <span className={styles.hint} style={{ margin: 0 }}>
          {isAuthenticated ? '✓ Signed in' : 'Not signed in'}
        </span>
        {isAuthenticated ? (
          <button className={styles.captureBtn} onClick={logout}
                  style={{ padding: '8px 16px', background: 'transparent', border: '1px solid var(--color-border, #262a35)', color: 'var(--color-muted, #8a90a0)' }}>
            Sign out
          </button>
        ) : (
          <button className={styles.captureBtn} onClick={() => setShowLogin(true)}
                  style={{ padding: '8px 16px' }}>
            Sign in
          </button>
        )}
      </div>

      <p className={styles.hint}>
        {online ? 'Online.' : 'Offline — working from your downloaded set.'}{' '}
        Last downloaded: {ago(lastSync)}.
      </p>
      {stale && (
        <p className={`${styles.hint} ${styles.warn}`}>
          Your offline set is over 12 h old — download again for fresh cards.
        </p>
      )}

      {isAuthenticated ? (
        <button className={styles.captureBtn} onClick={download} disabled={busy || !online}
                style={{ marginTop: 14, width: '100%', padding: '12px 16px' }}>
          {busy ? stageText(stage) : 'Download for offline'}
        </button>
      ) : (
        <button className={styles.captureBtn} onClick={() => setShowLogin(true)}
                style={{ marginTop: 14, width: '100%', padding: '12px 16px' }}>
          Sign in to sync
        </button>
      )}

      {status && <p className={`${styles.hint} ${styles[status.tone] || ''}`}>{status.text}</p>}

      <p className={styles.hint}>
        Tip: install this app and open it over the tunnel domain once on wifi, then hit
        Download — after that Review works with no connection.
      </p>

      {/* ── Quit-guard notifications ─────────────────────────────────────────── */}
      {notificationsSupported() && (
        <>
          <h2 className={styles.pageTitle} style={{ fontSize: 17, marginTop: 26 }}>Notifications</h2>
          <p className={styles.hint}>
            Get a nudge the moment you switch away with today's duty unfinished. On iOS this
            only works if the app is installed to your home screen (iOS 16.4+) — there's no
            fallback otherwise.
          </p>
          {notifPerm === 'granted' ? (
            <p className={styles.hint}>✓ Enabled</p>
          ) : notifPerm === 'denied' ? (
            <p className={`${styles.hint} ${styles.warn}`}>
              Blocked — re-enable in your browser/OS notification settings for this app.
            </p>
          ) : (
            <button className={styles.captureBtn} onClick={enableNotifications}
                    style={{ marginTop: 4, width: '100%', padding: '12px 16px' }}>
              Enable "are you a quitter?" nudges
            </button>
          )}
        </>
      )}

      {/* ── Failed captures ───────────────────────────────────────────────── */}
      {isAuthenticated && failed.length > 0 && (
        <>
          <h2 className={styles.pageTitle} style={{ fontSize: 17, marginTop: 26 }}>
            Failed captures ({failed.length})
          </h2>
          <p className={styles.hint}>
            These keep auto-retrying every 6h on their own — retry now or give up on one below.
          </p>
          {failed.map(c => (
            <div key={c.id} style={{
              marginTop: 10, padding: '10px 12px',
              border: '1px solid var(--color-border, #262a35)', borderRadius: 8,
            }}>
              <div style={{ fontWeight: 600, wordBreak: 'break-word' }}>
                {c.title || c.sourceRef}
              </div>
              <p className={`${styles.hint} ${styles.err}`} style={{ margin: '4px 0' }}>
                {c.lastError || 'Unknown error'}
              </p>
              <p className={styles.hint} style={{ margin: '0 0 8px' }}>
                {c.sourceType} · {c.retryCount} attempt{c.retryCount === 1 ? '' : 's'}
              </p>
              <div style={{ display: 'flex', gap: 8 }}>
                <button className={styles.captureBtn} onClick={() => handleRetry(c.id)}
                        disabled={failedBusyId === c.id || !online}
                        style={{ flex: 1, padding: '8px 14px' }}>
                  Retry
                </button>
                <button className={styles.captureBtn} onClick={() => handleDismiss(c.id)}
                        disabled={failedBusyId === c.id}
                        style={{ padding: '8px 14px', background: 'transparent', border: '1px solid var(--color-border, #262a35)', color: 'var(--color-muted, #8a90a0)' }}>
                  Dismiss
                </button>
              </div>
            </div>
          ))}
        </>
      )}

      {/* ── Drive link (server-independent offline) ─────────────────────────── */}
      <h2 className={styles.pageTitle} style={{ fontSize: 17, marginTop: 26 }}>Drive link</h2>
      <p className={styles.hint}>
        Link once to read your vault straight from Google Drive — then the phone works
        even when the laptop is off.
      </p>

      {!linked ? (
        <button className={styles.captureBtn} onClick={link} disabled={busy || !online || !isAuthenticated}
                style={{ marginTop: 12, width: '100%', padding: '12px 16px' }}>
          {isAuthenticated ? 'Link this device' : 'Sign in to link'}
        </button>
      ) : (
        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <button className={styles.captureBtn} onClick={testDrive} disabled={busy}
                  style={{ flex: 1, padding: '12px 16px' }}>
            Test: read a note from Drive
          </button>
          <button className={styles.captureBtn} onClick={unlink} disabled={busy}
                  style={{ padding: '12px 14px', background: 'transparent', border: '1px solid var(--color-border, #262a35)', color: 'var(--color-muted, #8a90a0)' }}>
            Unlink
          </button>
        </div>
      )}

      {driveMsg && <p className={`${styles.hint} ${styles[driveMsg.tone] || ''}`}>{driveMsg.text}</p>}
    </div>
  );
}
