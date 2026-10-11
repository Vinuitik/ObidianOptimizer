import { useEffect, useState } from 'react';
import useStore from '../../store/useStore';
import Button from '../atoms/Button';
import Input from '../atoms/Input';
import Select from '../atoms/Select';
import Field from '../molecules/Field';
import Card from '../molecules/Card';
import ListRow from '../molecules/ListRow';
import Skeleton from '../atoms/Skeleton';
import styles from './FeedView.module.css';

const toVal = a => a === true ? 'on' : a === false ? 'off' : 'inherit';
const fromVal = v => v === 'on' ? true : v === 'off' ? false : null;

const KIND_LABELS = {
  youtube: 'YouTube channel',
  youtube_channel: 'YouTube channel',
  youtube_posts: 'YouTube community posts',
  rss: 'RSS feed',
  page_watch: 'Web page',
};
export const kindLabel = k => KIND_LABELS[k] ?? (k ? k.replace(/_/g, ' ') : 'Source');

const AUTO_OPTIONS = [
  { value: 'inherit', label: 'Auto-accept: default' },
  { value: 'on', label: 'Auto-accept: on' },
  { value: 'off', label: 'Auto-accept: off' },
];

// Subscribe = preview -> confirm (same two steps as the extension): "Preview" resolves the URL
// without persisting; "Subscribe" creates the source, then onSubscribed() flips to Swipe.
export default function FeedSources({ onSubscribed }) {
  const sources = useStore(s => s.feedSources);
  const fetchSources = useStore(s => s.fetchFeedSources);
  const resolve = useStore(s => s.resolveFeedSource);
  const add = useStore(s => s.addFeedSource);
  const setAuto = useStore(s => s.setFeedSourceAutoAccept);
  const del = useStore(s => s.deleteFeedSource);
  const check = useStore(s => s.checkFeedSource);
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [preview, setPreview] = useState(null);   // { url, kind, title, preview:[…] }
  const [loaded, setLoaded] = useState(false);

  useEffect(() => { Promise.resolve(fetchSources()).finally(() => setLoaded(true)); }, [fetchSources]);

  async function doPreview(e) {
    e.preventDefault();
    const u = url.trim();
    if (!u) return;
    setBusy(true);
    const res = await resolve(u);
    setBusy(false);
    if (res) setPreview({ url: u, ...res });
  }

  async function confirm() {
    setBusy(true);
    const ok = await add(preview.url);
    setBusy(false);
    if (ok) { setUrl(''); setPreview(null); onSubscribed?.(); }
  }

  return (
    <div>
      <form className={styles.sourceForm} onSubmit={doPreview}>
        <Field label="Add a source" hint="A YouTube channel, RSS feed or web page.">
          <Input
            type="url" inputMode="url"
            placeholder="Channel, feed or page URL" aria-label="Source URL"
            value={url} onChange={e => { setUrl(e.target.value); setPreview(null); }}
          />
        </Field>
        <Button type="submit" loading={busy && !preview} disabled={!url.trim()}>Preview</Button>
      </form>

      {preview && (
        <Card className={styles.preview} data-testid="source-preview">
          <Card.Header title={preview.title || preview.url} subtitle={kindLabel(preview.kind)} />
          {preview.preview?.length ? (
            <ul className={styles.previewItems}>
              {preview.preview.map((it, i) => <li key={i}>{it.title}</li>)}
            </ul>
          ) : (
            <p className={styles.cardMeta}>Nothing to show yet — new items will appear after the first check.</p>
          )}
          <div className={styles.previewActions}>
            <Button variant="primary" loading={busy} onClick={confirm}>Subscribe</Button>
            <Button variant="ghost" onClick={() => setPreview(null)}>Cancel</Button>
          </div>
        </Card>
      )}

      {!loaded && !sources.length && <Skeleton lines={3} height={44} />}
      {loaded && !sources.length && <p className={styles.empty}>No sources yet. Paste a URL above to subscribe.</p>}
      <ul className={styles.list}>
        {sources.map(src => (
          <li key={src.id}>
            <ListRow
              title={src.title || src.url}
              meta={`${kindLabel(src.kind)} · ${src.pendingCount ?? 0} pending${src.lastCheckedAt ? ` · checked ${new Date(src.lastCheckedAt).toLocaleString()}` : ''}`}
              trailing={
                <span className={styles.sourceControls}>
                  <Select
                    aria-label={`Auto-accept for ${src.title || src.url}`}
                    options={AUTO_OPTIONS}
                    value={toVal(src.autoAccept)} onChange={e => setAuto(src.id, fromVal(e.target.value))}
                  />
                  <Button size="sm" onClick={() => check(src.id)}>Check now</Button>
                  <Button size="sm" variant="danger" onClick={() => del(src.id)} aria-label={`Delete ${src.title || src.url}`}>Delete</Button>
                </span>
              }
            />
          </li>
        ))}
      </ul>
    </div>
  );
}
