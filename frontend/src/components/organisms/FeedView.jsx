import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import useStore from '../../store/useStore';
import Button from '../atoms/Button';
import Skeleton from '../atoms/Skeleton';
import SegmentedTabs from '../molecules/SegmentedTabs';
import EmptyState from '../molecules/EmptyState';
import ListRow from '../molecules/ListRow';
import { swipe, fadeRiseReduced } from '../motion/presets';
import FeedSources from './FeedSources';
import styles from './FeedView.module.css';

const SWIPE_PX = 80;
const YT_HOST_RE = /(^|\.)youtube\.com$|^youtu\.be$/i;

function isYouTubeUrl(url) {
  try { return YT_HOST_RE.test(new URL(url).hostname); } catch { return false; }
}

function fmtDuration(s) {
  if (!s) return '';
  const m = Math.floor(s / 60), sec = String(s % 60).padStart(2, '0');
  return m >= 60 ? `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}
function fmtDate(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d) ? '' : d.toLocaleDateString();
}

// One card, swipeable with plain pointer events. dx drives a transform; release past
// SWIPE_PX commits (right = accept, left = bin), otherwise springs back. The exit/enter
// animation lives on the wrapping motion.div (see Stack) so the two transforms never fight.
function FeedCard({ item, onDecide }) {
  const start = useRef(null);
  const [dx, setDx] = useState(0);
  const subscribeToChannel = useStore(s => s.subscribeToChannel);

  function down(e) { start.current = e.clientX; }
  function move(e) { if (start.current != null) setDx(e.clientX - start.current); }
  function up(e) {
    if (start.current == null) return;
    const d = e.clientX - start.current;
    start.current = null;
    setDx(0);
    if (d >= SWIPE_PX) onDecide('accept');
    else if (d <= -SWIPE_PX) onDecide('bin');
  }

  const hint = dx > 20 ? 'accept' : dx < -20 ? 'bin' : null;
  return (
    <div
      className={`${styles.card} ${hint ? styles[`card_${hint}`] : ''}`}
      data-testid="feed-card"
      style={{ transform: `translateX(${dx}px) rotate(${dx / 30}deg)` }}
      onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}
    >
      {item.thumbUrl && <img className={styles.thumb} src={item.thumbUrl} alt="" draggable={false} />}
      <div className={styles.cardBody}>
        <h2 className={styles.cardTitle}>{item.title}</h2>
        <p className={styles.cardMeta}>
          {[item.sourceTitle, fmtDate(item.publishedAt), fmtDuration(item.durationS)].filter(Boolean).join(' · ')}
        </p>
        {isYouTubeUrl(item.itemUrl) && (
          <Button
            variant="ghost" size="sm"
            onPointerDown={e => e.stopPropagation()}
            onClick={e => { e.stopPropagation(); subscribeToChannel(item.itemUrl); }}
          >🔔 Subscribe to this channel</Button>
        )}
      </div>
    </div>
  );
}

function BinList() {
  const binned = useStore(s => s.feedBinned);
  const fetchFeedBin = useStore(s => s.fetchFeedBin);
  const ingest = useStore(s => s.ingestFeedItem);
  const restore = useStore(s => s.restoreFeedItem);
  const [loaded, setLoaded] = useState(false);
  useEffect(() => { Promise.resolve(fetchFeedBin()).finally(() => setLoaded(true)); }, [fetchFeedBin]);

  if (!loaded && !binned.length) return <Skeleton lines={4} height={44} />;
  if (!binned.length) return <p className={styles.empty}>Bin is empty.</p>;
  return (
    <ul className={styles.list}>
      {binned.map(item => (
        <li key={item.id}>
          <ListRow
            title={item.title}
            meta={item.sourceTitle}
            trailing={
              <span className={styles.rowActions}>
                <Button size="sm" onClick={() => ingest(item)}>Ingest anyway</Button>
                <Button size="sm" variant="ghost" onClick={() => restore(item)}>Restore</Button>
              </span>
            }
          />
        </li>
      ))}
    </ul>
  );
}

function Stack() {
  const pending = useStore(s => s.feedPending);
  const loaded = useStore(s => s.feedLoaded);
  const decide = useStore(s => s.decideFeed);
  const undo = useStore(s => s.undoFeed);
  const last = useStore(s => s.feedLastDecision);
  const setLearnView = useStore(s => s.setLearnView);
  const reduced = useReducedMotion();
  const item = pending[0];
  // Exit direction = the decision that just removed the card (right = accept, left = bin).
  const dir = last?.kind === 'bin' ? -1 : 1;

  useEffect(() => {
    function onKey(e) {
      const t = e.target;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return;
      if (e.key === 'ArrowRight') decide('accept');
      else if (e.key === 'ArrowLeft') decide('bin');
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [decide]);

  // Never claim "all caught up" before the first fetch resolved.
  if (!item && !loaded) {
    return <div className={styles.stage}><Skeleton height={260} className={styles.cardSkeleton} /></div>;
  }
  if (!item) {
    return (
      <EmptyState
        icon="check"
        title="You're all caught up"
        description="New videos and posts from your sources land here. Add sources in the Sources tab."
        action={{ label: 'Open Inbox', onClick: () => setLearnView('inbox') }}
      />
    );
  }
  return (
    <>
      <div className={styles.stage}>
        <AnimatePresence custom={dir} initial={false}>
          <motion.div
            key={item.id}
            className={styles.motionCard}
            custom={dir}
            variants={reduced ? fadeRiseReduced : swipe}
            initial="initial" animate="animate" exit={reduced ? { opacity: 0 } : 'exit'}
          >
            <FeedCard item={item} onDecide={decide} />
          </motion.div>
        </AnimatePresence>
      </div>
      <div className={styles.actions}>
        <Button variant="danger" onClick={() => decide('bin')} aria-label="Bin">← Bin</Button>
        <span className={styles.center}>
          <span className={styles.count}>{pending.length} left</span>
          {last && <Button variant="ghost" size="sm" onClick={undo}>Undo</Button>}
        </span>
        <Button variant="primary" onClick={() => decide('accept')} aria-label="Accept">Accept →</Button>
      </div>
    </>
  );
}

const TABS = [
  { id: 'stack', label: 'Swipe' },
  { id: 'bin', label: 'Bin' },
  { id: 'sources', label: 'Sources' },
];

export default function FeedView() {
  const [tab, setTab] = useState('stack');

  return (
    <div className={styles.wrap}>
      <div className={styles.tabs}>
        <SegmentedTabs tabs={TABS} value={tab} onChange={setTab} aria-label="Feed sections" />
      </div>
      <div className={styles.body}>
        {tab === 'stack' && <Stack />}
        {tab === 'bin' && <BinList />}
        {tab === 'sources' && <FeedSources onSubscribed={() => setTab('stack')} />}
      </div>
    </div>
  );
}
