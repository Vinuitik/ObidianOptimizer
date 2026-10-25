import { useCallback, useEffect, useMemo, useState } from 'react';
import { fetchBin, restoreBinItem, deleteBinItem, emptyBin } from '../api/bin';
import Button from '../components/atoms/Button';
import SegmentedTabs from '../components/molecules/SegmentedTabs';
import styles from './SettingsPage.module.css';

// Settings ▸ Bin. Segments hide at 0 items; track_item folds into Tracks, unknown = Other.
const SEGMENTS = [
  { id: 'note', label: 'Notes', kinds: ['note'] },
  { id: 'folder', label: 'Folders', kinds: ['folder'] },
  { id: 'file', label: 'Files', kinds: ['file'] },
  { id: 'feed', label: 'Feed', kinds: ['feed'] },
  { id: 'track', label: 'Tracks', kinds: ['track', 'track_item'] },
  { id: 'subscription', label: 'Subscriptions', kinds: ['subscription'] },
  { id: 'unknown', label: 'Other', kinds: ['unknown'] },
];

const RESTORE_ERRORS = {
  404: 'That item is no longer in the bin.',
  409: 'Can’t restore — you’ve already subscribed to that URL again.',
  410: 'Can’t restore — the saved copy is gone.',
};

export function ago(ts, now = Date.now()) {
  const t = new Date(ts).getTime();
  if (!ts || isNaN(t)) return '';
  const mins = Math.floor((now - t) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs} h ago`;
  return `${Math.floor(hrs / 24)} d ago`;
}

export default function BinPanel({ isAuthenticated }) {
  const [bin, setBin]         = useState(null);
  const [loadErr, setLoadErr] = useState(false);
  const [tab, setTab]         = useState(null);
  const [busy, setBusy]       = useState(false);
  const [error, setError]     = useState(null);

  const load = useCallback(async () => {
    try { setBin(await fetchBin()); setLoadErr(false); }
    catch { setLoadErr(true); }
  }, []);
  useEffect(() => { if (isAuthenticated) load(); }, [isAuthenticated, load]);

  const segments = useMemo(() => SEGMENTS.map(s => ({
    ...s, items: s.kinds.flatMap(k => bin?.[k] ?? []),
  })).filter(s => s.items.length), [bin]);

  const current = segments.find(s => s.id === tab) ?? segments[0];

  async function act(fn, failMsg) {
    setBusy(true); setError(null);
    try { await fn(); await load(); }
    catch (e) { setError(failMsg(e)); }
    finally { setBusy(false); }
  }

  const restore = item => act(
    () => restoreBinItem(item.kind, item.id),
    e => RESTORE_ERRORS[e?.status] ?? 'Couldn’t restore — try again.',
  );
  const forever = item => {
    if (!window.confirm(`Delete "${item.label}" forever? This can’t be undone.`)) return;
    return act(() => deleteBinItem(item.kind, item.id), () => 'Couldn’t delete — try again.');
  };
  const empty = () => {
    if (!window.confirm('Empty the bin? Everything in it is deleted forever.')) return;
    return act(() => emptyBin(), () => 'Couldn’t empty the bin — try again.');
  };

  return (
    <section id="bin" className={styles.section} aria-label="Bin">
      <h2 className={styles.sectionTitle}>Bin</h2>
      <p className={styles.sectionDesc}>Deleted items wait here. Items are deleted forever after 30 days.</p>
      <div className={styles.fields}>
        {loadErr && <div className={styles.fieldRow}><p className={styles.error} role="alert">Couldn’t load the bin.</p></div>}
        {bin && !current && !loadErr && <div className={styles.fieldRow}><p className={styles.note}>Bin is empty.</p></div>}
        {current && (
          <>
            <div className={styles.fieldRow}>
              <SegmentedTabs
                aria-label="Bin sections" value={current.id} onChange={setTab}
                tabs={segments.map(s => ({ id: s.id, label: s.label, badge: s.items.length }))}
              />
            </div>
            {current.items.map(item => (
              <div key={`${item.kind}:${item.id}`} className={styles.fieldRow} data-testid="bin-row">
                <div className={styles.inline}>
                  <strong>{item.label}</strong>
                  {item.kind === 'track_item' && <span className={styles.note}>item</span>}
                </div>
                {item.originalPath && <span className={styles.note} title={item.originalPath}>{item.originalPath}</span>}
                <div className={styles.inline}>
                  <span className={styles.note}>Deleted {ago(item.deletedAt)}</span>
                  <Button size="sm" variant="secondary" disabled={busy} onClick={() => restore(item)}>Restore</Button>
                  <Button size="sm" variant="danger" disabled={busy} onClick={() => forever(item)}>Delete forever</Button>
                </div>
              </div>
            ))}
            <div className={styles.fieldRow}>
              <Button size="sm" variant="danger" disabled={busy} onClick={empty}>Empty bin</Button>
            </div>
          </>
        )}
        {error && <div className={styles.fieldRow}><p className={styles.error} role="alert">{error}</p></div>}
      </div>
    </section>
  );
}
