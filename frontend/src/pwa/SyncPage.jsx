import { useState, useEffect, useRef } from 'react';
import useStore from '../store/useStore';
import useOffline from './useOffline';
import { syncForOffline } from './syncOffline';
import { flushOutbox, OUTBOX_RETRY_MS } from './offlineApi';
import { getMeta, setMeta, getAllReviewNotes } from './db';
import { linkDevice, hasCreds, unlinkDevice, proofReadNote } from './setup';
import { refreshAndPull, pullReviewFromDrive } from './drivePull';
import { pushMailbox } from './mailbox';
import { notificationsSupported, notificationPermission, requestNotificationPermission, showLocalNotification } from './quitNotify';
import { fetchFailedCaptures, retryCapture, dismissCapture } from '../api/capture';
import { stageText } from './syncStageLabel';
import Button from '../components/atoms/Button';
import Select from '../components/atoms/Select';
import Checkbox from '../components/atoms/Checkbox';
import Card from '../components/molecules/Card';
import Disclosure from '../components/molecules/Disclosure';
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
  const settings        = useStore(s => s.settings);
  const loadSettings    = useStore(s => s.loadSettings);
  const applySettings   = useStore(s => s.applySettings);
  const online          = useOffline();
  // Store-backed (not local state): survives navigating away mid-download instead of
  // vanishing the moment this page unmounts — see useStore.js's syncStage.
  const stage           = useStore(s => s.syncStage);
  const setStage        = useStore(s => s.setSyncStage);

  const [lastSync, setLastSync] = useState(null);
  const [offlineCount, setOfflineCount] = useState(null); // cards in the downloaded set
  const [busy, setBusy]         = useState(false);
  const [status, setStatus]     = useState(null);  // { text, tone }
  const [linked, setLinked]     = useState(false);
  const [driveMsg, setDriveMsg] = useState(null);  // { text, tone }
  const [notifPerm, setNotifPerm] = useState(() => notificationPermission());
  // "Capture sent" confirmations, mutable independent of the quit-nag — same OS permission
  // gates both, but this is its own opt-out (outbox.js checks the same stored key).
  const [notifyCaptureSent, setNotifyCaptureSent] = useState(true);
  const [failed, setFailed]     = useState([]);     // failed captures
  const [failedBusyId, setFailedBusyId] = useState(null);
  // Capture ids whose retry POST was accepted but whose actual ingest outcome isn't known
  // yet (the worker runs async — see CaptureIngestWorker). Map id → title, so the eventual
  // confirmation notification can name it. Checked against the failed list on every
  // loadFailed() poll below: gone from the list next time round = it actually landed.
  const pendingRetryRef = useRef(new Map());

  useEffect(() => {
    getMeta('lastSync').then(v => setLastSync(v || null)).catch(() => {});
    hasCreds().then(setLinked).catch(() => {});
    getMeta('notifyCaptureSent').then(v => setNotifyCaptureSent(v !== false)).catch(() => {});
  }, []);

  // Count of review notes sitting in IndexedDB; re-read whenever a sync lands.
  useEffect(() => {
    getAllReviewNotes().then(l => setOfflineCount(Array.isArray(l) ? l.length : 0)).catch(() => {});
  }, [lastSync]);

  async function toggleCaptureSentNotify(on) {
    setNotifyCaptureSent(on); // optimistic — this is a local preference, not a server call
    await setMeta('notifyCaptureSent', on).catch(() => {});
  }

  // The desktop-only Settings page used to be the only place this lived — pull it in here
  // too so the toggle (and its current value) is reachable without a laptop.
  useEffect(() => {
    if (isAuthenticated) loadSettings();
  }, [isAuthenticated, loadSettings]);

  const [backupBusy, setBackupBusy] = useState(false);
  async function toggleAutoBackup(e) {
    setBackupBusy(true);
    try {
      await applySettings({ syncEnabled: e.target.value === 'true' });
    } catch (err) {
      showToast(`Couldn't save: ${err.message ?? err}`);
    } finally {
      setBackupBusy(false);
    }
  }

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
        // Same local-notification mechanism as the quit-nag (quitNotify.js), gated on the
        // same permission toggle — silently no-ops if not granted. Fires here because this
        // is the only place the page learns about a failure: there's no backend push (Web
        // Push was explicitly declined), so this only reaches someone who has the app open.
        showLocalNotification(
          list.length === 1 ? 'A capture failed' : `${list.length} captures failed`,
          {
            body: list.length === 1
              ? 'Open Sync to retry or dismiss it.'
              : `${list.length} captures need attention — open Sync to retry or dismiss.`,
            tag: 'obsopt-capture-failed',   // replaces any previous one instead of stacking
          },
        );
      }
      localStorage.setItem(FAILED_COUNT_KEY, String(list.length));

      // Resolve any retries in flight: a pending id that's no longer in the failed list
      // actually succeeded (this is the only place that's known — the retry POST just
      // means "accepted", not "worked"). One still-present is either still processing or
      // failed again; either way it's no longer "pending", so drop it from tracking too —
      // a repeat failure is already covered by the growth check above.
      const pending = pendingRetryRef.current;
      if (pending.size > 0) {
        const stillFailed = new Set(list.map(c => c.id));
        const confirmed = [];
        for (const [id, title] of pending) {
          if (!stillFailed.has(id)) { confirmed.push(title); pending.delete(id); }
          else pending.delete(id);
        }
        if (confirmed.length > 0) {
          showLocalNotification(
            confirmed.length === 1 ? 'Capture synced' : `${confirmed.length} captures synced`,
            {
              body: confirmed.length === 1
                ? `${confirmed[0]} finished processing.`
                : `${confirmed.join(', ')} finished processing.`,
              tag: 'obsopt-capture-retry',   // replaces the earlier "Retry queued" notification
            },
          );
        }
      }
    } catch { /* not signed in yet, or offline — silently skip */ }
  }

  useEffect(() => {
    if (!isAuthenticated) return;
    loadFailed();
    // Poll so a retry's actual outcome (known only on the NEXT fetch) gets checked without
    // requiring the user to keep the page open and manually refresh — same cadence/pattern
    // as the outbox retry interval (offlineApi.js OUTBOX_RETRY_MS).
    const id = setInterval(loadFailed, OUTBOX_RETRY_MS);
    return () => clearInterval(id);
  }, [isAuthenticated]);

  async function handleRetry(id) {
    const label = failed.find(c => c.id === id)?.title || 'Capture';
    setFailedBusyId(id);
    try {
      await retryCapture(id);
      showToast('Retry queued.');
      // "Queued" here means the retry request itself resolved, not the eventual ingest
      // outcome — that's only known on the next loadFailed() poll (worker runs async, no
      // backend push), where pendingRetryRef is checked and the REAL success notification
      // fires. Track it here rather than declaring victory now.
      pendingRetryRef.current.set(id, label);
      showLocalNotification('Retry queued', {
        body: `${label} is retrying now.`,
        tag: 'obsopt-capture-retry',
      });
      setFailed(f => f.filter(c => c.id !== id));
      localStorage.setItem(FAILED_COUNT_KEY, String(failed.length - 1));
    } catch (e) {
      showToast(`Retry failed: ${e.message ?? e}`);
      showLocalNotification('Retry failed', {
        body: `Could not retry ${label}: ${e.message ?? e}`,
        tag: 'obsopt-capture-retry',
      });
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
        // force: true — an explicit manual tap is allowed to replace today's already-
        // prepared list (unlike autoSync/reconnect, which must not — see drivePull.js).
        const res = online
          ? await refreshAndPull({ onStage: setStage, force: true })
          : await pullReviewFromDrive({ onStage: setStage, force: true });
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
  const downloading = busy || Boolean(stage);

  return (
    <div className={styles.page}>
      {/* ── One status card: what's offline-ready and how fresh ─────────────── */}
      <Card className={styles.statusCard}>
        <p className={styles.statusLine}>
          {online ? '' : 'Offline - '}
          {offlineCount == null ? 'Offline set' : `Offline ready: ${offlineCount} card${offlineCount === 1 ? '' : 's'}`}
          {' '}&middot; {lastSync ? `synced ${ago(lastSync)}` : 'never synced'}
        </p>
        {stale && (
          <p className={`${styles.hint} ${styles.warn}`}>
            Your offline set is over 12 h old - download again for fresh cards.
          </p>
        )}
        {isAuthenticated ? (
          // stage (store-backed) reflects a sync in flight even if THIS mount of the page
          // didn't start it - the background auto-sync, or a download begun before
          // navigating away and still running.
          <Button variant="primary" onClick={download} loading={downloading} disabled={!online}>
            {stage ? stageText(stage) : 'Download for offline'}
          </Button>
        ) : (
          <Button variant="primary" onClick={() => setShowLogin(true)}>Sign in to sync</Button>
        )}
        {status && (
          <p className={`${styles.hint} ${styles[status.tone] || ''}`} role="status" aria-live="polite">{status.text}</p>
        )}
        <p className={styles.hint}>
          Install this app and open it over the tunnel domain once on wifi, then Download -
          after that Review works with no connection.
        </p>
      </Card>

      {/* ── Failed captures (surfaced, not buried) ──────────────────────────── */}
      {isAuthenticated && failed.length > 0 && (
        <Disclosure title={`Failed captures (${failed.length})`} storageKey="pwa.sync.failed" defaultOpen>
          <p className={styles.hint}>
            These auto-retry every 6h on their own - retry now or give up on one below.
          </p>
          {failed.map(c => (
            <div key={c.id} className={styles.failedItem}>
              <div className={styles.failedTitle}>{c.title || c.sourceRef}</div>
              <p className={`${styles.hint} ${styles.err}`}>{c.lastError || 'Unknown error'}</p>
              <p className={styles.hint}>
                {c.sourceType} &middot; {c.retryCount} attempt{c.retryCount === 1 ? '' : 's'}
              </p>
              <div className={styles.rowActions}>
                <Button onClick={() => handleRetry(c.id)} disabled={failedBusyId === c.id || !online}>Retry</Button>
                <Button variant="ghost" onClick={() => handleDismiss(c.id)} disabled={failedBusyId === c.id}>Dismiss</Button>
              </div>
            </div>
          ))}
        </Disclosure>
      )}

      {/* ── Account ───────────────────────────────────────────────────────────── */}
      {/* Always-reachable sign in / sign out. Without this, dismissing the auto-login
          modal left no way back in (and no way to sign out) from the installed PWA. */}
      <Disclosure title="Account" storageKey="pwa.sync.account">
        <div className={styles.row}>
          <span className={styles.hint}>{isAuthenticated ? 'Signed in' : 'Not signed in'}</span>
          {isAuthenticated
            ? <Button variant="ghost" onClick={logout}>Sign out</Button>
            : <Button variant="primary" onClick={() => setShowLogin(true)}>Sign in</Button>}
        </div>
      </Disclosure>

      {/* ── Drive link (server-independent offline) ─────────────────────────── */}
      <Disclosure title="Drive link" storageKey="pwa.sync.drive">
        <div className={styles.section}>
          <p className={styles.hint}>
            Link once to read your vault straight from Google Drive - then the phone works
            even when the laptop is off.
          </p>
          {!linked ? (
            <Button onClick={link} loading={busy} disabled={!online || !isAuthenticated}>
              {isAuthenticated ? 'Link this device' : 'Sign in to link'}
            </Button>
          ) : (
            <div className={styles.rowActions}>
              <Button onClick={testDrive} disabled={busy}>Test: read a note from Drive</Button>
              <Button variant="ghost" onClick={unlink} disabled={busy}>Unlink</Button>
            </div>
          )}
          {driveMsg && <p className={`${styles.hint} ${styles[driveMsg.tone] || ''}`}>{driveMsg.text}</p>}
        </div>
      </Disclosure>

      {/* ── Automatic backup (server-side upload cron - different from the device-local
          "Drive link" above, which lets this phone read Drive directly) ─────────── */}
      {isAuthenticated && (
        <Disclosure title="Automatic backup" storageKey="pwa.sync.backup">
          <div className={styles.section}>
            <p className={styles.hint}>
              Uploads your vault to Drive on a schedule (every 6h), independent of the
              device link. Runs on the server, so it works with this phone closed.
            </p>
            <Select
              aria-label="Automatic backup"
              value={String(settings.syncEnabled ?? true)}
              disabled={backupBusy}
              onChange={toggleAutoBackup}
              options={[
                { value: 'false', label: 'Off' },
                { value: 'true', label: 'On - scheduled background uploads' },
              ]}
            />
          </div>
        </Disclosure>
      )}

      {/* ── Quit-guard notifications ─────────────────────────────────────────── */}
      {notificationsSupported() && (
        <Disclosure title="Notifications" storageKey="pwa.sync.notif">
          <div className={styles.section}>
            <p className={styles.hint}>
              Get a nudge the moment you switch away with today's duty unfinished. On iOS this
              only works if the app is installed to your home screen (iOS 16.4+).
            </p>
            {notifPerm === 'granted' ? (
              <Checkbox
                label="Notify when a queued capture sends"
                checked={notifyCaptureSent}
                onChange={e => toggleCaptureSentNotify(e.target.checked)}
              />
            ) : notifPerm === 'denied' ? (
              <p className={`${styles.hint} ${styles.warn}`}>
                Blocked - re-enable in your browser/OS notification settings for this app.
              </p>
            ) : (
              <Button onClick={enableNotifications}>Enable "are you a quitter?" nudges</Button>
            )}
          </div>
        </Disclosure>
      )}
    </div>
  );
}
