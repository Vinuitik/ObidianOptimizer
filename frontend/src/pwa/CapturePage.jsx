import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import useStore from '../store/useStore';
import { captureUrl } from './offlineApi';
import styles from './MobilePages.module.css';

// Capture tab: the manual side of the Web Share Target. The share sheet path is
// handled in public/sw.js (POST /share-target → /api/capture) and lands back here
// with ?shared=… ; this page also offers a paste-a-link box for the same endpoint.
const SHARED_MSG = {
  ok:     { text: 'Saved — the ingest pipeline is turning it into a note.', tone: 'ok' },
  queued: { text: 'Offline — queued. It will be sent when you reconnect.',   tone: 'warn' },
  auth:   { text: 'Sign in, then it will be sent (queued for now).',         tone: 'warn' },
  err:    { text: "Couldn't read the shared link.",                          tone: 'err' },
};

export default function CapturePage() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const [params] = useSearchParams();
  const shared = params.get('shared');

  const [url, setUrl] = useState('');
  const [status, setStatus] = useState(null); // { text, tone }
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    const link = url.trim();
    if (!link) return;
    setBusy(true);
    try {
      const res = await captureUrl(link);
      setStatus(res.queued
        ? { text: 'Offline — queued. Will send on reconnect.', tone: 'warn' }
        : { text: 'Saved — ingesting into a note.', tone: 'ok' });
      setUrl('');
    } catch (e) {
      setStatus({ text: `Failed: ${e.message ?? e}`, tone: 'err' });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.pageTitle}>Capture</h1>

      {shared && SHARED_MSG[shared] && (
        <p className={`${styles.hint} ${styles[SHARED_MSG[shared].tone] || ''}`}>
          {SHARED_MSG[shared].text}
        </p>
      )}

      <form onSubmit={submit} className={styles.captureForm}>
        <input
          className={styles.captureInput}
          type="url"
          inputMode="url"
          placeholder="Paste a link to capture"
          value={url}
          onChange={e => setUrl(e.target.value)}
        />
        <button className={styles.captureBtn} type="submit" disabled={busy || !url.trim()}>
          {busy ? 'Sending…' : 'Capture'}
        </button>
      </form>

      {status && (
        <p className={`${styles.hint} ${styles[status.tone] || ''}`}>{status.text}</p>
      )}

      {!isAuthenticated && (
        <p className={styles.hint}>You're signed out — captures will queue until you sign in.</p>
      )}

      <p className={styles.hint}>
        Tip: once installed, share any link from another app and pick “Obsidian Optimizer”.
      </p>
    </div>
  );
}
