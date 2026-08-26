import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import useStore from '../store/useStore';
import { captureUrl, captureText } from './offlineApi';
import { fetchTracks, createTrack } from '../api/tracks';
import { ApiError } from '../api/notes';
import { getMeta, setMeta } from './db';
import useOffline from './useOffline';
import styles from './MobilePages.module.css';

// Capture tab — two ways in, both landing in the Learn inbox via the ingest pipeline:
//   • Link — paste/share a URL (the share-sheet path is handled in public/sw.js).
//   • Note — type a rough note (brain dump); the text becomes its own source and is
//            ingested into the inbox so you're guaranteed to come back to it once.
// Sharing a PDF/video/audio FILE is handled by the share-sheet (sw.js → /api/capture/file);
// there's no manual file picker here by design (the app stays narrow).
const SHARED_MSG = {
  ok:     { text: 'Saved — the ingest pipeline is turning it into a note.', tone: 'ok' },
  queued: { text: 'Offline — queued. It will be sent when you reconnect.',   tone: 'warn' },
  auth:   { text: 'Sign in, then it will be sent (queued for now).',         tone: 'warn' },
  err:    { text: "Couldn't read what was shared.",                          tone: 'err' },
};

export default function CapturePage() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const [params] = useSearchParams();
  const shared = params.get('shared');

  const online = useOffline();

  const [mode, setMode] = useState('link'); // 'link' | 'note' | 'subscribe'
  const [url, setUrl] = useState('');
  const [noteText, setNoteText] = useState('');
  const [noteTitle, setNoteTitle] = useState('');
  const [status, setStatus] = useState(null); // { text, tone }
  const [busy, setBusy] = useState(false);

  // Subscribe (tracks/FLOWS.md subscription discovery) — deliberate, infrequent action,
  // unlike Link/Note which go through the offline outbox. No queueing here by design.
  const [subTitle, setSubTitle] = useState('');
  const [subUrl, setSubUrl] = useState('');
  const [subType, setSubType] = useState('feed');
  const [subTypeTouched, setSubTypeTouched] = useState(false);

  // Track picker (tracks/FLOWS.md Phase 1b) — this manual capture form is already a
  // deliberate multi-field form (not fire-and-forget like the share-target), so it gets
  // the same "tag it at capture time" seam as the extension. '' = Just capture (default,
  // today's exact behavior), '__new__' reveals the new-track title field, else a track id.
  const [tracks, setTracks] = useState([]);
  const [trackChoice, setTrackChoice] = useState('');
  const [newTrackTitle, setNewTrackTitle] = useState('');

  // Fetch tracks, caching a successful result to meta so an offline fetch failure can
  // fall back to the last-known list instead of leaving the picker blank with no
  // explanation (stale is fine — same cached-state-beats-no-state stance as Drive sync).
  async function loadTracks() {
    try {
      const list = (await fetchTracks() || []).filter(t => t.status === 'active');
      setTracks(list);
      setMeta('cachedTracks', list).catch(() => {});
    } catch {
      const cached = await getMeta('cachedTracks').catch(() => null);
      setTracks(cached || []);
    }
  }

  useEffect(() => {
    if (!isAuthenticated) return;
    loadTracks();
  }, [isAuthenticated]);

  function trackOpts() {
    if (trackChoice === '__new__') {
      const title = newTrackTitle.trim();
      return title ? { newTrackTitle: title } : {};
    }
    return trackChoice ? { trackId: Number(trackChoice) } : {};
  }

  function resetTrackPicker() {
    if (trackChoice === '__new__') {
      loadTracks();
    }
    setTrackChoice('');
    setNewTrackTitle('');
  }

  function resultMsg(res) {
    return res.queued
      ? { text: 'Offline — queued. Will send on reconnect.', tone: 'warn' }
      : { text: 'Saved — ingesting into a note.', tone: 'ok' };
  }

  async function submitLink(e) {
    e.preventDefault();
    const link = url.trim();
    if (!link) return;
    setBusy(true);
    try {
      setStatus(resultMsg(await captureUrl(link, trackOpts())));
      setUrl('');
      resetTrackPicker();
    } catch (e) {
      setStatus({ text: `Failed: ${e.message ?? e}`, tone: 'err' });
    } finally {
      setBusy(false);
    }
  }

  async function submitNote(e) {
    e.preventDefault();
    const text = noteText.trim();
    if (!text) return;
    setBusy(true);
    try {
      setStatus(resultMsg(await captureText(text, noteTitle.trim() || null, trackOpts())));
      setNoteText('');
      setNoteTitle('');
      resetTrackPicker();
    } catch (e) {
      setStatus({ text: `Failed: ${e.message ?? e}`, tone: 'err' });
    } finally {
      setBusy(false);
    }
  }

  function handleSubUrlChange(value) {
    setSubUrl(value);
    if (!subTypeTouched) {
      setSubType(/youtube\.com|youtu\.be/.test(value) ? 'youtube_channel' : 'feed');
    }
  }

  async function submitSubscribe(e) {
    e.preventDefault();
    const title = subTitle.trim();
    const link = subUrl.trim();
    if (!title || !link) return;
    if (!online) {
      setStatus({ text: "You're offline — try again once connected.", tone: 'warn' });
      return;
    }
    setBusy(true);
    try {
      await createTrack(title, 'subscription', { sourceUrl: link, sourceType: subType });
      setStatus({ text: 'Subscribed.', tone: 'ok' });
      setSubTitle('');
      setSubUrl('');
      setSubType('feed');
      setSubTypeTouched(false);
    } catch (e) {
      const clean4xx = e instanceof ApiError && e.status >= 400 && e.status < 500;
      setStatus(clean4xx
        ? { text: `Failed: ${e.message}`, tone: 'err' }
        : { text: "You're offline — try again once connected.", tone: 'warn' });
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

      <div className={styles.modeToggle} role="tablist">
        <button
          type="button" role="tab" aria-selected={mode === 'link'}
          className={`${styles.modeBtn} ${mode === 'link' ? styles.modeBtnActive : ''}`}
          onClick={() => { setMode('link'); setStatus(null); }}
        >Link</button>
        <button
          type="button" role="tab" aria-selected={mode === 'note'}
          className={`${styles.modeBtn} ${mode === 'note' ? styles.modeBtnActive : ''}`}
          onClick={() => { setMode('note'); setStatus(null); }}
        >Note</button>
        <button
          type="button" role="tab" aria-selected={mode === 'subscribe'}
          className={`${styles.modeBtn} ${mode === 'subscribe' ? styles.modeBtnActive : ''}`}
          onClick={() => { setMode('subscribe'); setStatus(null); }}
        >Subscribe</button>
      </div>

      {isAuthenticated && mode !== 'subscribe' && (
        <div className={styles.captureFormCol}>
          <select
            className={styles.captureInput}
            value={trackChoice}
            onChange={e => setTrackChoice(e.target.value)}
            aria-label="Track"
          >
            <option value="">Just capture</option>
            {tracks.map(t => <option key={t.id} value={t.id}>{t.title}</option>)}
            <option value="__new__">+ New track…</option>
          </select>
          {trackChoice === '__new__' && (
            <input
              className={styles.captureInput}
              type="text"
              placeholder="New track title"
              value={newTrackTitle}
              onChange={e => setNewTrackTitle(e.target.value)}
            />
          )}
        </div>
      )}

      {mode === 'link' ? (
        <form onSubmit={submitLink} className={styles.captureForm}>
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
      ) : mode === 'note' ? (
        <form onSubmit={submitNote} className={styles.captureFormCol}>
          <input
            className={styles.captureInput}
            type="text"
            placeholder="Title (optional)"
            value={noteTitle}
            onChange={e => setNoteTitle(e.target.value)}
          />
          <textarea
            className={styles.captureTextarea}
            placeholder="Dump a rough note — it becomes a source and lands in your Learn inbox."
            value={noteText}
            onChange={e => setNoteText(e.target.value)}
          />
          <button className={styles.captureBtn} type="submit" disabled={busy || !noteText.trim()}>
            {busy ? 'Sending…' : 'Send to inbox'}
          </button>
        </form>
      ) : (
        <form onSubmit={submitSubscribe} className={styles.captureFormCol}>
          <input
            className={styles.captureInput}
            type="text"
            placeholder="Track title"
            value={subTitle}
            onChange={e => setSubTitle(e.target.value)}
          />
          <input
            className={styles.captureInput}
            type="url"
            inputMode="url"
            placeholder="Channel or feed URL"
            value={subUrl}
            onChange={e => handleSubUrlChange(e.target.value)}
          />
          <select
            className={styles.captureInput}
            value={subType}
            onChange={e => { setSubType(e.target.value); setSubTypeTouched(true); }}
            aria-label="Source type"
          >
            <option value="youtube_channel">YouTube channel</option>
            <option value="feed">RSS/Atom feed</option>
          </select>
          <button
            className={styles.captureBtn} type="submit"
            disabled={busy || !subTitle.trim() || !subUrl.trim()}
          >
            {busy ? 'Subscribing…' : 'Subscribe'}
          </button>
        </form>
      )}

      {status && (
        <p className={`${styles.hint} ${styles[status.tone] || ''}`}>{status.text}</p>
      )}

      {!isAuthenticated && mode !== 'subscribe' && (
        <p className={styles.hint}>You're signed out — captures will queue until you sign in.</p>
      )}

      <p className={styles.hint}>
        {mode === 'link'
          ? 'Tip: once installed, share any link — or a PDF/video/audio file — from another app and pick “Obsidian Optimizer”.'
          : mode === 'note'
          ? 'Rough notes you’d never revisit? Dump them here — the inbox guarantees you triage each one at least once.'
          : 'Browsing a YouTube channel or blog? Subscribe here and new items will show up in the track automatically.'}
      </p>
    </div>
  );
}
