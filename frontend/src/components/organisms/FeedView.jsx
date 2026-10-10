import { useEffect, useRef, useState } from 'react';
import useStore from '../../store/useStore';
import FeedSources from './FeedSources';
import styles from './FeedView.module.css';

const SWIPE_PX = 80;

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
// SWIPE_PX commits (right = accept, left = bin), otherwise springs back.
function FeedCard({ item, onDecide }) {
  const start = useRef(null);
  const [dx, setDx] = useState(0);

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
      </div>
    </div>
  );
}

function BinList() {
  const binned = useStore(s => s.feedBinned);
  const fetchFeedBin = useStore(s => s.fetchFeedBin);
  const ingest = useStore(s => s.ingestFeedItem);
  const restore = useStore(s => s.restoreFeedItem);
  useEffect(() => { fetchFeedBin(); }, [fetchFeedBin]);

  if (!binned.length) return <p className={styles.empty}>Bin is empty.</p>;
  return (
    <ul className={styles.list}>
      {binned.map(item => (
        <li key={item.id} className={styles.row}>
          <div className={styles.rowText}>
            <span className={styles.rowTitle}>{item.title}</span>
            <span className={styles.cardMeta}>{item.sourceTitle}</span>
          </div>
          <button className={styles.btn} onClick={() => ingest(item)}>Ingest anyway</button>
          <button className={styles.btn} onClick={() => restore(item)}>Restore</button>
        </li>
      ))}
    </ul>
  );
}

function Stack() {
  const pending = useStore(s => s.feedPending);
  const decide = useStore(s => s.decideFeed);
  const item = pending[0];

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

  if (!item) {
    return <p className={styles.empty}>You're all caught up. Add sources in the Sources tab.</p>;
  }
  return (
    <>
      <div className={styles.stage}>
        <FeedCard key={item.id} item={item} onDecide={decide} />
      </div>
      <div className={styles.actions}>
        <button className={`${styles.btn} ${styles.binBtn}`} onClick={() => decide('bin')} aria-label="Bin">← Bin</button>
        <span className={styles.count}>{pending.length} left</span>
        <button className={`${styles.btn} ${styles.acceptBtn}`} onClick={() => decide('accept')} aria-label="Accept">Accept →</button>
      </div>
    </>
  );
}

export default function FeedView() {
  const [tab, setTab] = useState('stack');
  const notice = useStore(s => s.feedNotice);
  const clearNotice = useStore(s => s.clearFeedNotice);
  const undo = useStore(s => s.undoFeed);
  const lastDecision = useStore(s => s.feedLastDecision);

  return (
    <div className={styles.wrap}>
      <div className={styles.tabs} role="tablist">
        {[['stack', 'Swipe'], ['bin', 'Bin'], ['sources', 'Sources']].map(([k, label]) => (
          <button
            key={k} role="tab" aria-selected={tab === k}
            className={`${styles.tab} ${tab === k ? styles.tabActive : ''}`}
            onClick={() => setTab(k)}
          >{label}</button>
        ))}
      </div>
      <div className={styles.body}>
        {tab === 'stack' && <Stack />}
        {tab === 'bin' && <BinList />}
        {tab === 'sources' && <FeedSources />}
      </div>
      {notice && (
        <div className={styles.notice} role="status">
          <span>{notice.message}</span>
          {notice.undo && lastDecision && <button className={styles.noticeBtn} onClick={undo}>Undo</button>}
          <button className={styles.noticeBtn} onClick={clearNotice} aria-label="Dismiss">×</button>
        </div>
      )}
    </div>
  );
}
