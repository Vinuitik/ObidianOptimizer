import { useCallback, useEffect, useState } from 'react';
import useStore from '../../store/useStore';
import { fetchWeekPlan } from '../../api/tracks';
import Button from '../atoms/Button';
import Badge from '../atoms/Badge';
import Skeleton from '../atoms/Skeleton';
import Card from '../molecules/Card';
import EmptyState from '../molecules/EmptyState';
import NewTrackForm from './NewTrackForm';
import { TrackDetail, CapacityPanel, ImportPanel, WEEKDAYS } from './TrackEditor';
import styles from './TracksPlan.module.css';

// Week = 7 days from today (backend: GET /api/tracks/week) + the track editor in a side drawer.
// panel: null | { kind: 'track', id } | { kind: 'new' } | { kind: 'capacity' } | { kind: 'import' }
export default function WeekPlan() {
  const tracks        = useStore(s => s.tracks);
  const fetchTracks   = useStore(s => s.fetchTracks);
  const trackCapacity = useStore(s => s.trackCapacity);
  const [days, setDays]       = useState(null);
  const [error, setError]     = useState(null);
  const [panel, setPanel]     = useState(null);

  const load = useCallback(() => {
    return fetchWeekPlan().then(d => { setDays(d); setError(null); }).catch(e => setError(e.message ?? String(e)));
  }, []);

  useEffect(() => { fetchTracks(); }, [fetchTracks]);
  // Any edit (tracks/schedules/capacity) changes the projection → refetch.
  useEffect(() => { load(); }, [load, tracks, trackCapacity]);

  const selected = panel?.kind === 'track' ? tracks.find(t => t.id === panel.id) : null;
  const close = () => { setPanel(null); load(); };

  return (
    <div className={styles.weekPage}>
      <div className={styles.weekToolbar}>
        <Button variant="primary" size="sm" onClick={() => setPanel({ kind: 'new' })}>+ New track</Button>
        <Button size="sm" onClick={() => setPanel({ kind: 'capacity' })}>Daily capacity</Button>
        <Button size="sm" onClick={() => setPanel({ kind: 'import' })}>Import…</Button>
      </div>

      {days == null && !error && <Skeleton lines={3} height={64} />}
      {error && <p className={styles.error} role="alert">Couldn't load the week: {error}</p>}
      {days && tracks.length === 0 && (
        <EmptyState icon="inbox" title="No tracks yet"
          description="A track is a book, course or topic you work through a little each day."
          action={{ label: 'Create your first track', onClick: () => setPanel({ kind: 'new' }) }} />
      )}

      {days && (
        <div className={styles.weekGrid} data-testid="week-grid">
          {days.map(d => (
            <Card key={d.date} className={styles.weekDay} data-testid="week-day">
              <div className={styles.weekDayHead}>
                <span className={styles.weekDayName}>{WEEKDAYS[d.weekday] ?? d.date}</span>
                <span className={styles.muted}>{d.date.slice(5)}</span>
              </div>
              {d.items.length === 0 && <span className={styles.muted}>Nothing</span>}
              {d.items.map(it => (
                <button
                  key={it.trackId}
                  type="button"
                  className={styles.weekChip}
                  onClick={() => setPanel({ kind: 'track', id: it.trackId })}
                >
                  <span className={`${styles.truncate} ${styles.grow}`}>{it.trackTitle}</span>
                  <Badge tone="accent">{it.count}</Badge>
                </button>
              ))}
              <span className={styles.muted}>cap {d.capacity}</span>
            </Card>
          ))}
        </div>
      )}

      {tracks.length > 0 && (
        <div className={styles.weekTracks}>
          <span className={styles.muted}>All tracks:</span>
          {tracks.map(t => (
            <button key={t.id} type="button" className={styles.weekChip}
              onClick={() => setPanel({ kind: 'track', id: t.id })}>{t.title}</button>
          ))}
        </div>
      )}

      {panel && (
        <>
          <div className={styles.drawerBackdrop} onClick={close} />
          <aside className={styles.drawer} role="dialog" aria-label="Track editor" data-testid="track-drawer">
            <div className={styles.drawerHead}>
              <Button size="sm" onClick={close}>Close</Button>
            </div>
            {panel.kind === 'new' && (
              <NewTrackForm autoFocus onCreated={t => setPanel(t?.id ? { kind: 'track', id: t.id } : null)} />
            )}
            {panel.kind === 'capacity' && <CapacityPanel />}
            {panel.kind === 'import' && <ImportPanel onClose={close} />}
            {selected && <TrackDetail track={selected} onDeleted={close} />}
            {panel.kind === 'track' && !selected && <p className={styles.muted}>Track not found.</p>}
          </aside>
        </>
      )}
    </div>
  );
}
