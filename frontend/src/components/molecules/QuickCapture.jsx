import { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import useStore from '../../store/useStore';
import { captureResource, captureFile } from '../../api/capture';
import { createNote, updateNote } from '../../api/notes';
import { classifyInput } from '../organisms/TrackAddBox';
import Button from '../atoms/Button';
import Textarea from '../atoms/Textarea';
import styles from './QuickCapture.module.css';

// Pure: a safe note name from free text (first line, no path/wiki-link characters).
export function noteNameFromText(text, now = new Date()) {
  const first = String(text ?? '').split('\n').find(l => l.trim()) ?? '';
  const clean = first.replace(/[\\/:*?"<>|#^[\]]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 60).trim();
  if (clean) return clean;
  const p = n => String(n).padStart(2, '0');
  return `Quick note ${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ${p(now.getHours())}${p(now.getMinutes())}`;
}

function isTyping(el) {
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
}

// Global capture: URLs / files go to the capture pipeline (no track), plain text becomes a
// note staged in _inbox. Success toast links to the Inbox (Learn page).
export default function QuickCapture() {
  const navigate = useNavigate();
  const vaultRoot = useStore(s => s.vaultRoot);
  const showToast = useStore(s => s.showToast);

  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [dragOver, setDragOver] = useState(false);
  const fileRef = useRef(null);
  const rootRef = useRef(null);
  const areaRef = useRef(null);

  const cls = classifyInput(text);

  const close = useCallback(() => { setOpen(false); setError(null); }, []);

  // `c` opens (unless typing or using a modifier); Esc / outside click closes.
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') { close(); return; }
      if (e.key.toLowerCase() === 'c' && !e.metaKey && !e.ctrlKey && !e.altKey && !isTyping(e.target)) {
        e.preventDefault();
        setOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [close]);

  useEffect(() => {
    if (!open) return undefined;
    areaRef.current?.focus();
    const onDown = (e) => { if (rootRef.current && !rootRef.current.contains(e.target)) close(); };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open, close]);

  function done({ ok, duplicate, failed, what, errMsg }) {
    setBusy(false);
    if (failed && !ok && !duplicate) { setError(errMsg || `Couldn't capture ${what} — try again.`); return; }
    const parts = [];
    if (ok) parts.push(`Captured ${what}`);
    if (duplicate) parts.push(`${duplicate} already captured`);
    if (failed) parts.push(`${failed} failed`);
    setText('');
    close();
    showToast({
      message: `${parts.join(' · ')} — processing now.`,
      tone: failed ? 'error' : 'success',
      action: { label: 'View in Inbox', onClick: () => navigate('/learn') },
    });
  }

  async function submitUrls(urls) {
    setBusy(true); setError(null);
    let ok = 0, duplicate = 0, failed = 0, errMsg = null;
    for (const url of urls) {
      const res = await captureResource(url);
      if (!res.ok) { failed += 1; errMsg = res.error; }
      else if (res.duplicate) duplicate += 1;
      else ok += 1;
    }
    done({ ok, duplicate, failed, errMsg, what: ok === 1 ? '1 link' : `${ok} links` });
  }

  async function submitFiles(files) {
    setBusy(true); setError(null);
    let ok = 0, duplicate = 0, failed = 0, errMsg = null;
    for (const file of files) {
      const res = await captureFile(file);
      if (!res.ok) { failed += 1; errMsg = res.error; }
      else if (res.duplicate) duplicate += 1;
      else ok += 1;
    }
    done({ ok, duplicate, failed, errMsg, what: ok === 1 ? '1 file' : `${ok} files` });
  }

  async function submitText(raw) {
    if (!vaultRoot) { setError('Vault is still loading — try again in a moment.'); return; }
    setBusy(true); setError(null);
    try {
      const { path } = await createNote(`${vaultRoot}/_inbox`, noteNameFromText(raw));
      await updateNote(path, raw.trim());
      done({ ok: 1, what: 'note' });
    } catch (e) {
      setBusy(false);
      setError(`Couldn't save the note — ${e?.message ?? e}`);
    }
  }

  function submit(e) {
    e?.preventDefault();
    if (busy) return;
    if (cls.kind === 'urls') submitUrls(cls.urls);
    else if (cls.kind === 'text') submitText(text);
  }

  function onDrop(e) {
    e.preventDefault();
    setDragOver(false);
    const files = [...(e.dataTransfer?.files ?? [])];
    if (files.length) submitFiles(files);
  }

  const label = cls.kind === 'urls'
    ? `Capture ${cls.urls.length} link${cls.urls.length > 1 ? 's' : ''}`
    : cls.kind === 'text' ? 'Save as note' : 'Capture';

  return (
    <div className={styles.root} ref={rootRef}>
      <Button
        variant="secondary"
        size="sm"
        icon="plus"
        onClick={() => (open ? close() : setOpen(true))}
        aria-expanded={open}
        aria-haspopup="dialog"
        title="Quick capture (c)"
      >
        Capture
      </Button>

      {open && (
        <form
          className={`${styles.popover} ${dragOver ? styles.dragOver : ''}`}
          role="dialog"
          aria-label="Quick capture"
          onSubmit={submit}
          onDragOver={e => { if (e.dataTransfer?.types?.includes('Files')) { e.preventDefault(); setDragOver(true); } }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
        >
          <Textarea
            ref={areaRef}
            className={styles.input}
            rows={text.includes('\n') ? 4 : 2}
            placeholder="Paste a link, jot a note, or drop a file"
            aria-label="Capture"
            value={text}
            onChange={e => setText(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey || (!e.shiftKey && cls.kind === 'urls'))) submit(e);
            }}
          />
          {error && <p className={styles.error} role="alert">{error}</p>}
          <div className={styles.actions}>
            <Button variant="ghost" size="sm" onClick={() => fileRef.current?.click()} disabled={busy}>Attach file</Button>
            <input
              ref={fileRef}
              type="file"
              multiple
              hidden
              data-testid="quick-capture-file"
              onChange={e => { const f = [...e.target.files]; e.target.value = ''; if (f.length) submitFiles(f); }}
            />
            <Button type="submit" variant="primary" size="sm" loading={busy} disabled={cls.kind === 'empty'}>{label}</Button>
          </div>
        </form>
      )}
    </div>
  );
}
