import { useEffect, useState } from 'react';
import useStore from '../../store/useStore';
import styles from './FeedView.module.css';

const toVal = a => a === true ? 'on' : a === false ? 'off' : 'inherit';
const fromVal = v => v === 'on' ? true : v === 'off' ? false : null;

export default function FeedSources() {
  const sources = useStore(s => s.feedSources);
  const fetchSources = useStore(s => s.fetchFeedSources);
  const add = useStore(s => s.addFeedSource);
  const setAuto = useStore(s => s.setFeedSourceAutoAccept);
  const del = useStore(s => s.deleteFeedSource);
  const check = useStore(s => s.checkFeedSource);
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => { fetchSources(); }, [fetchSources]);

  async function submit(e) {
    e.preventDefault();
    const u = url.trim();
    if (!u) return;
    setBusy(true);
    const ok = await add(u);
    setBusy(false);
    if (ok) setUrl('');
  }

  return (
    <div>
      <form className={styles.addForm} onSubmit={submit}>
        <input
          className={styles.input} type="url" inputMode="url"
          placeholder="Channel, feed or page URL" aria-label="Source URL"
          value={url} onChange={e => setUrl(e.target.value)}
        />
        <button className={styles.btn} type="submit" disabled={busy || !url.trim()}>Add</button>
      </form>
      {!sources.length && <p className={styles.empty}>No sources yet.</p>}
      <ul className={styles.list}>
        {sources.map(src => (
          <li key={src.id} className={styles.row}>
            <div className={styles.rowText}>
              <span className={styles.rowTitle}>{src.title || src.url}</span>
              <span className={styles.cardMeta}>
                {src.kind} · {src.pendingCount ?? 0} pending
                {src.lastCheckedAt ? ` · checked ${new Date(src.lastCheckedAt).toLocaleString()}` : ''}
              </span>
            </div>
            <select
              className={styles.select} aria-label={`Auto-accept for ${src.title || src.url}`}
              value={toVal(src.autoAccept)} onChange={e => setAuto(src.id, fromVal(e.target.value))}
            >
              <option value="inherit">Auto: inherit</option>
              <option value="on">Auto: on</option>
              <option value="off">Auto: off</option>
            </select>
            <button className={styles.btn} onClick={() => check(src.id)}>Check now</button>
            <button className={styles.btn} onClick={() => del(src.id)} aria-label={`Delete ${src.title || src.url}`}>Delete</button>
          </li>
        ))}
      </ul>
    </div>
  );
}
