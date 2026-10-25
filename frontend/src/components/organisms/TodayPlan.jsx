import { useEffect, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import useStore from '../../store/useStore';
import Button from '../atoms/Button';
import Badge from '../atoms/Badge';
import Checkbox from '../atoms/Checkbox';
import Skeleton from '../atoms/Skeleton';
import Card from '../molecules/Card';
import EmptyState from '../molecules/EmptyState';
import { isInboxNotePath } from '../../utils/sourceColor';
import { listItem } from '../motion/presets';
import styles from './TracksPlan.module.css';

const listItemReduced = { initial: { opacity: 0 }, animate: { opacity: 1 }, exit: { opacity: 0 } };


// "Add to spaced review" is remembered per track (not per item) so it sticks across days.
const reviewKey = trackId => `tracks:addToReview:${trackId}`;
function readReviewPref(trackId) {
  try { return localStorage.getItem(reviewKey(trackId)) === '1'; } catch { return false; }
}
function writeReviewPref(trackId, v) {
  try { localStorage.setItem(reviewKey(trackId), v ? '1' : '0'); } catch { /* storage unavailable */ }
}

export default function TodayPlan({ onCreateTrack, onManage }) {
  const allTodayItems  = useStore(s => s.todayItems);
  const todayMode      = useStore(s => s.todayMode);
  const todayOverBudget = useStore(s => s.todayOverBudget);
  const todayCapacity  = useStore(s => s.todayCapacity);
  const tracks         = useStore(s => s.tracks);
  const fetchTracks    = useStore(s => s.fetchTracks);
  const fetchTodayPlan = useStore(s => s.fetchTodayPlan);
  const setTrackMode   = useStore(s => s.setTrackMode);
  const completeTrackItem = useStore(s => s.completeTrackItem);
  const showToast       = useStore(s => s.showToast);
  const reduce          = useReducedMotion();
  const [reviewPref, setReviewPref] = useState({}); // trackId -> bool (cache over localStorage)
  const [busy, setBusy] = useState(null); // itemId currently completing
  const [switching, setSwitching] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    Promise.allSettled([fetchTodayPlan(), fetchTracks()]).then(() => setLoaded(true));
  }, [fetchTodayPlan, fetchTracks]);

  const wantsReview = trackId => reviewPref[trackId] ?? readReviewPref(trackId);

  async function markDone(item) {
    setBusy(item.itemId);
    try {
      await completeTrackItem(item.itemId, wantsReview(item.trackId));
      showToast({ message: 'Done', tone: 'success' });
    } catch (e) {
      showToast(`Couldn't complete item: ${e.message ?? e}`);
    } finally {
      setBusy(null);
    }
  }

  async function toggleMode() {
    setSwitching(true);
    try {
      await setTrackMode(todayMode === 'lockin' ? 'normal' : 'lockin');
    } catch (e) {
      showToast(`Couldn't switch mode: ${e.message ?? e}`);
    } finally {
      setSwitching(false);
    }
  }

  // Items whose note is still in _inbox are covered by the queue's Today units (InboxReview).
  const todayItems = allTodayItems.filter(i => !isInboxNotePath(i.notePath));
  const focus = todayMode === 'lockin';

  if (!loaded) {
    return <div className={styles.todayList}><Skeleton lines={3} height={48} /></div>;
  }

  return (
    <div className={styles.todayList}>
      <div className={styles.modeRow}>
        <div className={styles.modeText}>
          <span className={styles.modeLabel}>{focus ? 'Focus on one track' : 'Balanced'}</span>
          <span className={styles.modeHint}>
            {focus
              ? 'Only your most urgent track shows up, so you finish it faster.'
              : "Today's items are spread across all your tracks."}
          </span>
        </div>
        <Button size="sm" onClick={toggleMode} loading={switching}>
          Switch to {focus ? 'Balanced' : 'Focus on one track'}
        </Button>
      </div>

      {todayOverBudget && (
        <div className={styles.overBudget} role="status">
          <span>You're behind — must-priority deadlines alone exceed today's capacity.</span>
          <Button size="sm" onClick={toggleMode} loading={switching}>Focus on one track?</Button>
        </div>
      )}

      {todayItems.length > 0 && todayCapacity != null && (
        <div className={styles.todayHeader}>{todayItems.length} of {todayCapacity} for today</div>
      )}

      {todayItems.length === 0 && (
        tracks.length === 0 ? (
          <EmptyState
            icon="inbox"
            title="No tracks yet"
            description="A track is a book, course or topic you work through a little each day."
            action={{ label: 'Create your first track', onClick: onCreateTrack }}
          />
        ) : (
          <EmptyState
            icon="inbox"
            title="Nothing scheduled today"
            description="Give a track a deadline or a weekly schedule."
            action={{ label: 'Set up a schedule', onClick: onManage }}
          />
        )
      )}

      <AnimatePresence initial={false}>
        {todayItems.map(item => (
          <motion.div
            key={item.itemId}
            data-testid="today-row"
            variants={reduce ? listItemReduced : listItem}
            initial="initial"
            animate="animate"
            exit="exit"
          >
            <Card className={styles.todayItem}>
              <div className={styles.todayItemMain}>
                <Badge tone="accent">{item.trackTitle}</Badge>
                <div className={styles.todayTextCol}>
                  <span className={styles.todayItemTitle}>{item.title}</span>
                  {item.reason && <span className={styles.todayReason}>{item.reason}</span>}
                </div>
              </div>
              <div className={styles.todayItemActions}>
                {item.notePath && (
                  <Checkbox
                    label="Add to spaced review"
                    checked={wantsReview(item.trackId)}
                    onChange={e => {
                      writeReviewPref(item.trackId, e.target.checked);
                      setReviewPref(s => ({ ...s, [item.trackId]: e.target.checked }));
                    }}
                  />
                )}
                <Button size="sm" loading={busy === item.itemId} onClick={() => markDone(item)}>
                  Mark done
                </Button>
              </div>
            </Card>
          </motion.div>
        ))}
      </AnimatePresence>

    </div>
  );
}
