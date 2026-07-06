import { useState, useEffect } from 'react';
import useStore from '../store/useStore';
import useOffline from './useOffline';
import { syncForOffline } from './syncOffline';
import { flushOutbox } from './offlineApi';
import { getMeta } from './db';
import { linkDevice, hasCreds, unlinkDevice, proofReadNote } from './setup';
import styles from './MobilePages.module.css';

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

export default function SyncPage() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const setShowLogin    = useStore(s => s.setShowLogin);
  const online          = useOffline();

  const [lastSync, setLastSync] = useState(null);
  const [busy, setBusy]         = useState(false);
  const [progress, setProgress] = useState(null); // { done, total }
  const [status, setStatus]     = useState(null);  // { text, tone }
  const [linked, setLinked]     = useState(false);
  const [driveMsg, setDriveMsg] = useState(null);  // { text, tone }

  useEffect(() => {
    getMeta('lastSync').then(v => setLastSync(v || null)).catch(() => {});
    hasCreds().then(setLinked).catch(() => {});
  }, []);

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
      setDriveMsg({ text: `✓ Decrypted “${r.path}” (${r.total} files on Drive): ${r.preview}`, tone: 'ok' });
    } catch (e) {
      setDriveMsg({ text: `Drive read failed: ${e.message ?? e}`, tone: 'err' });
    } finally { setBusy(false); }
  }

  async function download() {
    setBusy(true); setStatus(null); setProgress(null);
    try {
      await flushOutbox().catch(() => {});
      const res = await syncForOffline({ onProgress: setProgress });
      setLastSync(Date.now());
      setStatus({ text: `Downloaded ${res.notes} notes · ${res.media} media for offline.`, tone: 'ok' });
    } catch (e) {
      setStatus({ text: `Sync failed: ${e.message ?? e}`, tone: 'err' });
    } finally {
      setBusy(false); setProgress(null);
    }
  }

  const stale = lastSync && Date.now() - lastSync > 12 * 60 * 60 * 1000;

  return (
    <div className={styles.page}>
      <h1 className={styles.pageTitle}>Sync</h1>

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
          {busy
            ? (progress ? `Downloading ${progress.done}/${progress.total}…` : 'Downloading…')
            : 'Download for offline'}
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
