import { useState, useEffect, useRef } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import useStore from '../store/useStore';
import { captureUrl, captureText } from './offlineApi';
import { addFeedSource } from '../api/feed';
import { captureFile } from '../api/capture';
import { ApiError } from '../api/notes';
import useOffline from './useOffline';
import Button from '../components/atoms/Button';
import Input from '../components/atoms/Input';
import Textarea from '../components/atoms/Textarea';
import SegmentedTabs from '../components/molecules/SegmentedTabs';
import styles from './MobilePages.module.css';

// Capture tab - four ways in, all landing in the Learn inbox via the ingest pipeline:
//   Link      - paste a URL (the share-sheet path is handled in public/sw.js).
//   Note      - a rough brain dump; becomes its own source in the inbox.
//   File      - pick a PDF/audio/video (online only: api/capture.captureFile, no outbox).
//   Subscribe - add a feed source.
// Every outcome says WHERE it went and links there (LEARN / SYNC below).
const LEARN  = { to: '/learn',  label: 'Open Learn inbox' };
const SYNC   = { to: '/sync',   label: 'View sync status' };

// Precise about WHAT happened and WHERE the thing is right now - "sent to the server"
// (accepted, synthesis pipeline picks it up next) is as far as any state can honestly claim.
export const SHARED_MSG = {
  ok:          { text: 'Sent to server - now processing into your Learn inbox.', tone: 'ok', links: [LEARN] },
  offline:     { text: "You're offline - queued on this device. It sends when you're back online.", tone: 'warn', links: [SYNC] },
  unreachable: { text: 'Server unreachable - queued on this device. Will retry automatically.', tone: 'warn', links: [SYNC] },
  auth:        { text: 'Sign in, then it sends (queued on this device for now).', tone: 'warn', links: [SYNC] },
  err:         { text: "Couldn't read what was shared.", tone: 'err', links: [] },
};

const MODES = [
  { id: 'link', label: 'Link' },
  { id: 'note', label: 'Note' },
  { id: 'file', label: 'File' },
  { id: 'subscribe', label: 'Subscribe' },
];

export default function CapturePage() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const [params] = useSearchParams();
  const shared = params.get('shared');

  const online = useOffline();

  const [mode, setMode] = useState('link');
  const [url, setUrl] = useState('');
  const [noteText, setNoteText] = useState('');
  const [noteTitle, setNoteTitle] = useState('');
  const [file, setFile] = useState(null);
  const fileRef = useRef(null);
  const [status, setStatus] = useState(null); // { text, tone, links }
  const [busy, setBusy] = useState(false);
  const [subUrl, setSubUrl] = useState('');

  // Where it went: sent -> Learn inbox ; queued -> Sync.
  function resultMsg(res) {
    if (!res.queued) {
      return { ...SHARED_MSG.ok, links: [LEARN] };
    }
    return SHARED_MSG[res.reason] || SHARED_MSG.unreachable;
  }

  const failMsg = (e) => ({ text: `Failed: ${e.message ?? e}`, tone: 'err', links: [] });

  async function submitLink(e) {
    e.preventDefault();
    const link = url.trim();
    if (!link) return;
    setBusy(true);
    try {
      setStatus(resultMsg(await captureUrl(link)));
      setUrl('');
    } catch (err) {
      setStatus(failMsg(err));
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
      setStatus(resultMsg(await captureText(text, noteTitle.trim() || null)));
      setNoteText('');
      setNoteTitle('');
    } catch (err) {
      setStatus(failMsg(err));
    } finally {
      setBusy(false);
    }
  }

  async function submitFile(e) {
    e.preventDefault();
    if (!file) return;
    if (!online) {
      setStatus({ text: "You're offline - pick the file again once connected (files aren't queued).", tone: 'warn', links: [] });
      return;
    }
    setBusy(true);
    try {
      const res = await captureFile(file);
      if (!res.ok) {
        setStatus({ text: `Failed: ${res.error}`, tone: 'err', links: [] });
      } else {
        setStatus(res.duplicate
          ? { text: 'Already captured - it is in the pipeline.', tone: 'warn', links: [LEARN] }
          : { text: `Uploaded ${file.name} - now processing into your Learn inbox.`, tone: 'ok', links: [LEARN] });
        setFile(null);
        if (fileRef.current) fileRef.current.value = '';
        }
    } catch (err) {
      setStatus(failMsg(err));
    } finally {
      setBusy(false);
    }
  }

  async function submitSubscribe(e) {
    e.preventDefault();
    const link = subUrl.trim();
    if (!link) return;
    if (!online) {
      setStatus({ text: "You're offline - try again once connected.", tone: 'warn', links: [] });
      return;
    }
    setBusy(true);
    try {
      await addFeedSource(link);
      setStatus({ text: 'Subscribed - new items show up in Learn > Feed.', tone: 'ok', links: [{ to: '/learn', label: 'Open Learn' }] });
      setSubUrl('');
    } catch (err) {
      const clean4xx = err instanceof ApiError && err.status >= 400 && err.status < 500;
      setStatus(clean4xx
        ? failMsg(err)
        : { text: "You're offline - try again once connected.", tone: 'warn', links: [] });
    } finally {
      setBusy(false);
    }
  }

  const sharedMsg = shared && SHARED_MSG[shared];
  const shown = status || sharedMsg || null;

  return (
    <div className={styles.page}>
      <SegmentedTabs
        aria-label="Capture type"
        tabs={MODES}
        value={mode}
        onChange={id => { setMode(id); setStatus(null); }}
      />

      {mode === 'link' && (
        <form onSubmit={submitLink} className={styles.form}>
          <Input
            type="url"
            inputMode="url"
            placeholder="Paste a link to capture"
            aria-label="Link"
            value={url}
            onChange={e => setUrl(e.target.value)}
          />
          <Button type="submit" variant="primary" loading={busy} disabled={!url.trim()}>Capture</Button>
        </form>
      )}

      {mode === 'note' && (
        <form onSubmit={submitNote} className={styles.form}>
          <Input
            type="text"
            placeholder="Title (optional)"
            aria-label="Title"
            value={noteTitle}
            onChange={e => setNoteTitle(e.target.value)}
          />
          <Textarea
            className={styles.textarea}
            placeholder="Dump a rough note - it becomes a source and lands in your Learn inbox."
            aria-label="Note"
            value={noteText}
            onChange={e => setNoteText(e.target.value)}
          />
          <Button type="submit" variant="primary" loading={busy} disabled={!noteText.trim()}>Send to inbox</Button>
        </form>
      )}

      {mode === 'file' && (
        <form onSubmit={submitFile} className={styles.form}>
          <Input
            ref={fileRef}
            type="file"
            accept="application/pdf,.pdf,audio/*,video/*"
            aria-label="File"
            onChange={e => setFile(e.target.files?.[0] ?? null)}
          />
          <Button type="submit" variant="primary" loading={busy} disabled={!file}>Upload</Button>
        </form>
      )}

      {mode === 'subscribe' && (
        <form onSubmit={submitSubscribe} className={styles.form}>
          <Input
            type="url"
            inputMode="url"
            placeholder="Channel, feed or page URL"
            aria-label="Source URL"
            value={subUrl}
            onChange={e => setSubUrl(e.target.value)}
          />
          <Button type="submit" variant="primary" loading={busy} disabled={!subUrl.trim()}>Subscribe</Button>
        </form>
      )}

      {shown && (
        <div className={styles.result} role="status" aria-live="polite">
          <span className={styles[shown.tone] || ''}>{shown.text}</span>
          {shown.links?.length > 0 && (
            <span className={styles.resultLinks}>
              {shown.links.map(l => <Link key={l.to} to={l.to}>{l.label}</Link>)}
            </span>
          )}
        </div>
      )}

      {!isAuthenticated && mode !== 'subscribe' && (
        <p className={styles.hint}>You're signed out - captures queue until you sign in.</p>
      )}

      <p className={styles.hint}>
        {mode === 'link'
          ? 'Tip: once installed, share any link or file from another app and pick "Obsidian Optimizer".'
          : mode === 'note'
          ? "Rough notes you'd never revisit? Dump them here - the inbox makes you triage each one once."
          : mode === 'file'
          ? 'PDF, audio or video. Needs a connection - files are not queued offline.'
          : 'Browsing a YouTube channel or blog? Subscribe and new items show up in Learn > Feed to swipe.'}
      </p>
    </div>
  );
}
