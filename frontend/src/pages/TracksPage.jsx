import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import useStore from '../store/useStore';
import Ring from '../components/atoms/Ring';
import Button from '../components/atoms/Button';
import Badge from '../components/atoms/Badge';
import Input from '../components/atoms/Input';
import Textarea from '../components/atoms/Textarea';
import Select from '../components/atoms/Select';
import Checkbox from '../components/atoms/Checkbox';
import Skeleton from '../components/atoms/Skeleton';
import Spinner from '../components/atoms/Spinner';
import Field from '../components/molecules/Field';
import Card from '../components/molecules/Card';
import Disclosure from '../components/molecules/Disclosure';
import SegmentedTabs from '../components/molecules/SegmentedTabs';
import EmptyState from '../components/molecules/EmptyState';
import ListRow from '../components/molecules/ListRow';
import { listItem } from '../components/motion/presets';
import { generateMinicourse, fetchMinicourseJob, approveMinicourse, importCsv, commitImport } from '../api/tracks';
import NewTrackForm from '../components/organisms/NewTrackForm';
import TrackAddBox, { ItemTypeChip, itemKind } from '../components/organisms/TrackAddBox';
import styles from './TracksPage.module.css';

function cap(s) { return s ? s[0].toUpperCase() + s.slice(1) : s; }

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const TRACK_TYPES = ['book', 'course', 'article', 'custom', 'subscription'];
const TYPE_OPTIONS = TRACK_TYPES.map(t => ({ value: t, label: cap(t) }));
const STATUS_OPTIONS = ['active', 'paused', 'done', 'archived'].map(t => ({ value: t, label: cap(t) }));
const PRIORITY_OPTIONS = ['must', 'should', 'could'].map(t => ({ value: t, label: cap(t) }));
const STATUS_TONE = { active: 'success', paused: 'ember', done: 'accent', archived: 'neutral' };
// Subscriptions are created via Learn ▸ Feed ▸ Sources now; legacy ones stay editable.

const listItemReduced = {
  initial: { opacity: 0 },
  animate: { opacity: 1 },
  exit: { opacity: 0 },
};

// "Add to spaced review" is remembered per track (not per item) so it sticks across days.
const reviewKey = trackId => `tracks:addToReview:${trackId}`;
function readReviewPref(trackId) {
  try { return localStorage.getItem(reviewKey(trackId)) === '1'; } catch { return false; }
}
function writeReviewPref(trackId, v) {
  try { localStorage.setItem(reviewKey(trackId), v ? '1' : '0'); } catch { /* storage unavailable */ }
}

// Pure rendering transform — items stay a flat array from the API/store. Items sharing a
// groupId came from one source-ingestion event (e.g. a long video split into several notes);
// singleton groups (groupId set but no siblings) render flat like ungrouped items.
function groupForDisplay(items) {
  const byGroup = {};
  items.forEach(i => { if (i.groupId) (byGroup[i.groupId] ??= []).push(i); });
  const rendered = new Set();
  const rows = [];
  for (const item of items) {
    if (item.groupId && byGroup[item.groupId].length > 1) {
      if (rendered.has(item.groupId)) continue;
      rendered.add(item.groupId);
      rows.push({ type: 'group', groupId: item.groupId, members: byGroup[item.groupId] });
    } else {
      rows.push({ type: 'item', item });
    }
  }
  return rows;
}

export default function TracksPage() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const [tab, setTab] = useState('today');
  const [focusNew, setFocusNew] = useState(false);

  if (!isAuthenticated) {
    return (
      <div className={styles.gate}>
        <p>Sign in to view your tracks.</p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <SegmentedTabs
        aria-label="Tracks sections"
        className={styles.tabs}
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'today', label: 'Today' },
          { id: 'manage', label: 'Manage tracks' },
          { id: 'progress', label: 'Progress' },
        ]}
      />

      {tab === 'today' && (
        <TodayTab
          onCreateTrack={() => { setFocusNew(true); setTab('manage'); }}
          onManage={() => { setFocusNew(false); setTab('manage'); }}
        />
      )}
      {tab === 'manage' && (
        <ManageTab focusNew={focusNew} onCreated={() => { setFocusNew(false); setTab('today'); }} />
      )}
      {tab === 'progress' && <ProgressTab />}
    </div>
  );
}

// ── Today ─────────────────────────────────────────────────────────────────────

const POLL_MS = 5000;
const POLL_MAX = 6;

function TodayTab({ onCreateTrack, onManage }) {
  const todayItems     = useStore(s => s.todayItems);
  const todayMode      = useStore(s => s.todayMode);
  const todayOverBudget = useStore(s => s.todayOverBudget);
  const todayCapacity  = useStore(s => s.todayCapacity);
  const tracks         = useStore(s => s.tracks);
  const trackPending   = useStore(s => s.trackPending) ?? [];
  const fetchTracks    = useStore(s => s.fetchTracks);
  const fetchTodayPlan = useStore(s => s.fetchTodayPlan);
  const setTrackMode   = useStore(s => s.setTrackMode);
  const completeTrackItem = useStore(s => s.completeTrackItem);
  const shiftTrackPending = useStore(s => s.shiftTrackPending);
  const clearTrackPending = useStore(s => s.clearTrackPending);
  const showToast       = useStore(s => s.showToast);
  const reduce          = useReducedMotion();
  const [reviewPref, setReviewPref] = useState({}); // trackId -> bool (cache over localStorage)
  const [busy, setBusy] = useState(null); // itemId currently completing
  const [switching, setSwitching] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const polls = useRef(0);
  const baseCount = useRef(todayItems.length);

  useEffect(() => {
    Promise.allSettled([fetchTodayPlan(), fetchTracks()]).then(() => setLoaded(true));
  }, [fetchTodayPlan, fetchTracks]);

  // Pending ingest: poll the plan a few times; each new item retires one "processing" row.
  const pendingCount = trackPending.length;
  useEffect(() => {
    if (pendingCount === 0) return undefined;
    const t = setTimeout(async () => {
      polls.current += 1;
      await fetchTodayPlan().catch(() => {});
      const grown = useStore.getState().todayItems.length - baseCount.current;
      if (grown > 0) { baseCount.current += grown; shiftTrackPending(grown); }
      else if (polls.current >= POLL_MAX) clearTrackPending();
    }, POLL_MS);
    return () => clearTimeout(t);
  }, [pendingCount, todayItems.length, fetchTodayPlan, shiftTrackPending, clearTrackPending]);

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

      {todayItems.length === 0 && trackPending.length === 0 && (
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

      {trackPending.map((p, i) => (
        <Card key={`${p.label}-${i}`} className={styles.todayItem} data-testid="processing-row">
          <div className={styles.todayItemMain}>
            <Badge tone="accent">{p.trackTitle}</Badge>
            <div className={styles.todayTextCol}>
              <span className={`${styles.todayItemTitle} ${styles.truncate}`} title={p.label}>{p.label}</span>
              <span className={styles.todayReason}>Processing — it'll appear here when ready</span>
            </div>
          </div>
          <Spinner size={16} label="Processing" />
        </Card>
      ))}
    </div>
  );
}

// ── Manage tracks ────────────────────────────────────────────────────────────

function ManageTab({ focusNew, onCreated }) {
  const tracks       = useStore(s => s.tracks);
  const fetchTracks  = useStore(s => s.fetchTracks);
  const [selectedId, setSelectedId] = useState(null);
  const [importing, setImporting] = useState(false);

  useEffect(() => { fetchTracks(); }, [fetchTracks]);

  const selected = tracks.find(t => t.id === selectedId) ?? null;

  return (
    <div className={styles.manage}>
      <div className={styles.trackList}>
        <NewTrackForm autoFocus={focusNew} onCreated={onCreated} />

        <Disclosure title="Advanced" storageKey="tracks-manage-advanced">
          <CapacityPanel />
          <Button
            className={styles.importTrigger}
            onClick={() => { setImporting(true); setSelectedId(null); }}
          >
            Import from spreadsheet…
          </Button>
        </Disclosure>

        <div className={styles.trackItems}>
          {tracks.length === 0 && <p className={styles.muted}>No tracks yet.</p>}
          {tracks.map(t => (
            <ListRow
              key={t.id}
              title={t.title}
              trailing={<Badge tone={STATUS_TONE[t.status] ?? 'neutral'}>{cap(t.status)}</Badge>}
              active={selectedId === t.id}
              onClick={() => { setImporting(false); setSelectedId(t.id); }}
            />
          ))}
        </div>
      </div>

      <div className={styles.trackDetail}>
        {importing ? (
          <ImportPanel onClose={() => setImporting(false)} />
        ) : selected ? (
          <TrackDetail track={selected} onDeleted={() => setSelectedId(null)} />
        ) : (
          <p className={styles.muted}>Select a track to edit its items and schedule, or create a new one.</p>
        )}
      </div>
    </div>
  );
}

// ── Import from spreadsheet (Phase 3) ───────────────────────────────────────

function ImportPanel({ onClose }) {
  const fetchTracks = useStore(s => s.fetchTracks);
  const showToast   = useStore(s => s.showToast);

  const [text, setText] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [previewTracks, setPreviewTracks] = useState(null); // [{ title, type }]
  const [previewItems, setPreviewItems]   = useState(null); // [{ _key, trackIndex, title, status }]
  const [committing, setCommitting] = useState(false);
  const [result, setResult] = useState(null); // { tracksCreated, itemsCreated }

  async function handleFile(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    setText(await file.text());
  }

  async function handlePreview() {
    if (!text.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const res = await importCsv(text);
      setPreviewTracks(res.tracks.map(t => ({ ...t })));
      setPreviewItems(res.items.map((it, i) => ({ ...it, _key: i })));
    } catch (e) {
      setError(e.message ?? String(e));
    } finally {
      setLoading(false);
    }
  }

  function editTrack(i, patch) {
    setPreviewTracks(ts => ts.map((t, idx) => idx === i ? { ...t, ...patch } : t));
  }

  function deleteTrack(i) {
    setPreviewTracks(ts => ts.filter((_, idx) => idx !== i));
    setPreviewItems(its => its
      .filter(it => it.trackIndex !== i)
      .map(it => it.trackIndex > i ? { ...it, trackIndex: it.trackIndex - 1 } : it));
  }

  function editItem(key, patch) {
    setPreviewItems(its => its.map(it => it._key === key ? { ...it, ...patch } : it));
  }

  function deleteItem(key) {
    setPreviewItems(its => its.filter(it => it._key !== key));
  }

  async function handleCommit() {
    setCommitting(true);
    try {
      const items = previewItems.map(({ _key, ...rest }) => rest);
      const res = await commitImport({ tracks: previewTracks, items });
      setResult(res);
      setPreviewTracks(null);
      setPreviewItems(null);
      fetchTracks();
    } catch (e) {
      showToast(`Couldn't import: ${e.message ?? e}`);
    } finally {
      setCommitting(false);
    }
  }

  if (result) {
    return (
      <div className={styles.stack}>
        <p>Imported {result.tracksCreated} track(s) and {result.itemsCreated} item(s).</p>
        <div className={styles.actionsRow}>
          <Button variant="primary" onClick={onClose}>Done</Button>
        </div>
      </div>
    );
  }

  if (previewTracks) {
    return (
      <div className={styles.stack}>
        <h3 className={styles.sectionTitle}>Import preview</h3>
        {previewTracks.map((t, i) => (
          <Card key={i} className={styles.stack}>
            <div className={styles.rowWrap}>
              <Input
                aria-label="Track title"
                className={styles.grow}
                value={t.title}
                onChange={e => editTrack(i, { title: e.target.value })}
              />
              <Select
                aria-label="Track type"
                options={TYPE_OPTIONS}
                value={t.type}
                onChange={e => editTrack(i, { type: e.target.value })}
              />
              <Button variant="danger" size="sm" onClick={() => deleteTrack(i)}>Delete track</Button>
            </div>
            <ul className={styles.itemList}>
              {previewItems.filter(it => it.trackIndex === i).map(it => (
                <li key={it._key} className={styles.itemRow}>
                  <Input
                    aria-label="Item title"
                    className={styles.grow}
                    value={it.title}
                    onChange={e => editItem(it._key, { title: e.target.value })}
                  />
                  <Badge>{cap(it.status)}</Badge>
                  <Button variant="ghost" size="sm" aria-label="Remove item" onClick={() => deleteItem(it._key)}>×</Button>
                </li>
              ))}
              {previewItems.filter(it => it.trackIndex === i).length === 0 && (
                <li className={styles.muted}>No items in this track.</li>
              )}
            </ul>
          </Card>
        ))}
        {previewTracks.length === 0 && <p className={styles.muted}>No tracks left to import.</p>}
        <div className={styles.actionsRow}>
          <Button variant="primary" onClick={handleCommit} loading={committing} disabled={previewTracks.length === 0}>
            Commit import
          </Button>
          <Button onClick={onClose}>Cancel</Button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Import from spreadsheet</h3>
      <p className={styles.muted}>
        Paste CSV text or select a .csv file. An LLM parses it into tracks and items for you to review before anything is created.
      </p>
      <Textarea
        aria-label="CSV text"
        placeholder="Paste CSV here…"
        value={text}
        onChange={e => setText(e.target.value)}
        rows={10}
      />
      <input type="file" accept=".csv,text/csv" aria-label="CSV file" onChange={handleFile} />
      {error && <p className={styles.error} role="alert">{error}</p>}
      <div className={styles.actionsRow}>
        <Button variant="primary" onClick={handlePreview} loading={loading} disabled={!text.trim()}>
          {loading ? 'Parsing…' : 'Preview'}
        </Button>
        <Button onClick={onClose}>Cancel</Button>
      </div>
    </div>
  );
}

// Daily capacity ceiling used by Phase 1c's Today allocation (Normal mode only) — plain
// per-weekday numbers, not a base×multiplier, so any single day can be hand-tuned without
// touching the others. Lives on the Tracks page (not generic Settings) since it's
// tracks-specific, same as the weekly schedule editor.
function CapacityPanel() {
  const trackCapacity      = useStore(s => s.trackCapacity);
  const fetchTrackCapacity = useStore(s => s.fetchTrackCapacity);
  const saveTrackCapacity  = useStore(s => s.saveTrackCapacity);
  const showToast          = useStore(s => s.showToast);
  const [values, setValues] = useState({});

  useEffect(() => { fetchTrackCapacity(); }, [fetchTrackCapacity]);
  useEffect(() => { setValues(trackCapacity); }, [trackCapacity]);

  async function save(weekday, value) {
    const n = Number(value);
    if (!value || Number.isNaN(n) || n < 0) return;
    try {
      await saveTrackCapacity({ [weekday]: n });
    } catch (e) {
      showToast(`Couldn't save capacity: ${e.message ?? e}`);
    }
  }

  return (
    <div className={styles.capacityPanel}>
      <h3 className={styles.sectionTitle}>Daily capacity</h3>
      <p className={styles.muted}>Most items to show on Today per weekday.</p>
      <div className={styles.capacityRow}>
        {WEEKDAYS.map((label, i) => (
          <div key={i} className={styles.capacityCell}>
            <span className={styles.muted}>{label}</span>
            <Input
              type="number"
              min={0}
              aria-label={`${label} capacity`}
              value={values[i] ?? ''}
              onChange={e => setValues(v => ({ ...v, [i]: e.target.value }))}
              onBlur={e => save(i, e.target.value)}
            />
          </div>
        ))}
      </div>
    </div>
  );
}

function TrackDetail({ track, onDeleted }) {
  const updateTrack   = useStore(s => s.updateTrack);
  const deleteTrack   = useStore(s => s.deleteTrack);
  const showToast     = useStore(s => s.showToast);

  const [title, setTitle] = useState(track.title);
  const [type, setType]   = useState(track.type);
  const [deadline, setDeadline] = useState(track.deadline ?? '');
  const [priority, setPriority] = useState(track.priority ?? '');
  const [includeInProgress, setIncludeInProgress] = useState(track.includeInProgress);

  // Reset local form state whenever a different track is selected.
  useEffect(() => {
    setTitle(track.title);
    setType(track.type);
    setDeadline(track.deadline ?? '');
    setPriority(track.priority ?? '');
    setIncludeInProgress(track.includeInProgress);
  }, [track.id]);

  async function saveField(patch) {
    try {
      await updateTrack(track.id, patch);
    } catch (e) {
      showToast(`Couldn't save: ${e.message ?? e}`);
    }
  }

  async function handleDelete() {
    if (!window.confirm(`Delete "${track.title}" and all its items? This can't be undone.`)) return;
    try {
      await deleteTrack(track.id);
      onDeleted();
    } catch (e) {
      showToast(`Couldn't delete: ${e.message ?? e}`);
    }
  }

  return (
    <div className={styles.stack}>
      <div className={styles.detailHeader}>
        <Input
          aria-label="Track name"
          className={styles.grow}
          value={title}
          onChange={e => setTitle(e.target.value)}
          onBlur={() => title.trim() && title !== track.title && saveField({ title: title.trim() })}
        />
        <Button variant="danger" size="sm" onClick={handleDelete}>Delete track</Button>
      </div>

      <Card>
        <Field label="Deadline">
          <Input
            type="date"
            value={deadline}
            onChange={e => setDeadline(e.target.value)}
            onBlur={() => {
              if (deadline) saveField({ deadline });
              else if (track.deadline) saveField({ clearDeadline: true });
            }}
          />
        </Field>
      </Card>
      <Card>
        <TrackItemsEditor trackId={track.id} />
      </Card>
      <Card>
        <Disclosure title="Advanced">
          <div className={styles.stack}>
            <Field label="Type">
              <Select
                options={TYPE_OPTIONS}
                value={type}
                onChange={e => { setType(e.target.value); saveField({ type: e.target.value }); }}
              />
            </Field>
            <Field label="Status">
              <Select
                options={STATUS_OPTIONS}
                value={track.status}
                onChange={e => saveField({ status: e.target.value })}
              />
            </Field>
            <Checkbox
              label="Show on Progress tab"
              checked={Boolean(includeInProgress)}
              onChange={e => { setIncludeInProgress(e.target.checked); saveField({ includeInProgress: e.target.checked }); }}
            />
            {deadline && (
              <Field label="Priority">
                <Select
                  options={PRIORITY_OPTIONS}
                  value={priority || 'should'}
                  onChange={e => { setPriority(e.target.value); saveField({ priority: e.target.value }); }}
                />
              </Field>
            )}
            <TrackScheduleEditor trackId={track.id} />
            <MinicoursePanel trackId={track.id} />
          </div>
        </Disclosure>
      </Card>
    </div>
  );
}

// ── Mini-course (Phase 2) ────────────────────────────────────────────────────

function MinicoursePanel({ trackId }) {
  const items = useStore(s => s.trackItems[trackId]) ?? [];
  const fetchTrackItems = useStore(s => s.fetchTrackItems);
  const showToast = useStore(s => s.showToast);
  const [job, setJob] = useState(null);
  const [checked, setChecked] = useState({}); // lesson index -> bool
  const [busy, setBusy] = useState(false);

  const hasNotes = items.some(i => i.notePath);

  // Different track selected — drop any job state from the previous one.
  useEffect(() => { setJob(null); }, [trackId]);

  const polling = job != null && (job.status === 'QUEUED' || job.status === 'RUNNING');
  useEffect(() => {
    if (!polling) return undefined;
    const id = setInterval(async () => {
      try {
        const updated = await fetchMinicourseJob(job.id);
        setJob(updated);
        if (updated.status === 'DONE') fetchTrackItems(trackId);
      } catch (e) {
        showToast(`Couldn't check mini-course status: ${e.message ?? e}`);
      }
    }, 2000);
    return () => clearInterval(id);
  }, [polling, job?.id, trackId, fetchTrackItems, showToast]);

  useEffect(() => {
    if (job?.status === 'AWAITING_APPROVAL' && job.plan) {
      setChecked(Object.fromEntries(job.plan.lessons.map((_, i) => [i, true])));
    }
  }, [job?.status, job?.plan]);

  async function start() {
    setBusy(true);
    try {
      setJob(await generateMinicourse(trackId));
    } catch (e) {
      showToast(`Couldn't start mini-course: ${e.message ?? e}`);
    } finally {
      setBusy(false);
    }
  }

  async function approve() {
    const approvedIndexes = Object.entries(checked).filter(([, v]) => v).map(([i]) => Number(i));
    setBusy(true);
    try {
      setJob(await approveMinicourse(job.id, approvedIndexes));
    } catch (e) {
      showToast(`Couldn't approve mini-course: ${e.message ?? e}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Generate a course</h3>

      {(!job || job.status === 'FAILED') && (
        <div className={styles.actionsRow}>
          <Button
            onClick={start}
            loading={busy}
            disabled={!hasNotes}
            title={hasNotes ? undefined : 'Add at least one item with a linked note first'}
          >
            Generate mini-course
          </Button>
        </div>
      )}

      {job && (job.status === 'QUEUED' || job.status === 'RUNNING') && (
        <div className={styles.statusRow} aria-live="polite">
          <Spinner size={14} label="Working" />
          <span>{job.stage === 'lessons' ? 'Writing lessons…' : 'Generating syllabus…'}</span>
        </div>
      )}

      {job?.status === 'AWAITING_APPROVAL' && job.plan && (
        <div className={styles.stack}>
          <p className={styles.planTitle}>{job.plan.course_title}</p>
          <ul className={styles.itemList}>
            {job.plan.lessons.map((lesson, i) => (
              <li key={i} className={styles.lessonRow}>
                <Checkbox
                  checked={Boolean(checked[i])}
                  onChange={e => setChecked(c => ({ ...c, [i]: e.target.checked }))}
                  label={
                    <span className={styles.lessonText}>
                      <span>{lesson.title}</span>
                      <span className={styles.muted}>{lesson.objective}</span>
                    </span>
                  }
                />
              </li>
            ))}
          </ul>
          <div className={styles.actionsRow}>
            <Button variant="primary" onClick={approve} loading={busy}>Approve &amp; write notes</Button>
          </div>
        </div>
      )}

      {job?.status === 'DONE' && (
        <div className={styles.stack}>
          <p>{job.results?.length ?? 0} lesson note(s) written.</p>
          {job.lesson_failures?.length > 0 && (
            <p className={styles.error}>
              Failed: {job.lesson_failures.map(f => f.title).join(', ')}
            </p>
          )}
        </div>
      )}

      {job?.status === 'FAILED' && (
        <p className={styles.error} role="alert">{job.error}</p>
      )}
    </div>
  );
}

function TrackItemsEditor({ trackId }) {
  const items = useStore(s => s.trackItems[trackId]) ?? [];
  const fetchTrackItems  = useStore(s => s.fetchTrackItems);
  const updateTrackItem  = useStore(s => s.updateTrackItem);
  const deleteTrackItem  = useStore(s => s.deleteTrackItem);
  const showToast        = useStore(s => s.showToast);
  const [dragId, setDragId] = useState(null);

  useEffect(() => { fetchTrackItems(trackId); }, [trackId, fetchTrackItems]);

  async function handleDrop(targetItem) {
    if (dragId == null || dragId === targetItem.id) return;
    try {
      await updateTrackItem(trackId, dragId, { position: targetItem.position });
    } catch (e) {
      showToast(`Couldn't reorder: ${e.message ?? e}`);
    } finally {
      setDragId(null);
    }
  }

  function renderItemRow(item) {
    return (
      <li
        key={item.id}
        className={styles.itemRow}
        draggable
        onDragStart={() => setDragId(item.id)}
        onDragOver={e => e.preventDefault()}
        onDrop={() => handleDrop(item)}
      >
        <span className={styles.dragHandle} aria-hidden="true">⠿</span>
        <span className={`${styles.itemTitle} ${item.status === 'done' ? styles.itemDone : ''}`}>
          {item.title}
        </span>
        <ItemTypeChip kind={itemKind(item)} />
        <Button
          variant="ghost"
          size="sm"
          aria-label={`Remove ${item.title}`}
          onClick={() => deleteTrackItem(trackId, item.id)}
        >
          ×
        </Button>
      </li>
    );
  }

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Items</h3>
      <TrackAddBox trackId={trackId} />

      <ul className={styles.itemList}>
        {groupForDisplay(items).map(row => row.type === 'group' ? (
          <li key={`group-${row.groupId}`}>
            <Disclosure title={`${row.members.length} items from one source`}>
              <ul className={styles.itemList}>
                {row.members.map(renderItemRow)}
              </ul>
            </Disclosure>
          </li>
        ) : renderItemRow(row.item))}
        {items.length === 0 && <li className={styles.muted}>No items yet.</li>}
      </ul>
    </div>
  );
}

function TrackScheduleEditor({ trackId }) {
  const fetchTrackSchedule = useStore(s => s.fetchTrackSchedule);
  const updateSchedule     = useStore(s => s.updateSchedule);
  const showToast          = useStore(s => s.showToast);
  const [budgets, setBudgets] = useState({}); // weekday(string) -> budget or '' (off)

  useEffect(() => {
    fetchTrackSchedule(trackId).then(sched => {
      const next = {};
      WEEKDAYS.forEach((_, i) => { next[i] = sched?.[i] ?? ''; });
      setBudgets(next);
    });
  }, [trackId, fetchTrackSchedule]);

  function toggleDay(i) {
    setBudgets(s => ({ ...s, [i]: s[i] === '' ? 1 : '' }));
  }

  function setBudget(i, value) {
    setBudgets(s => ({ ...s, [i]: value }));
  }

  async function save() {
    const payload = {};
    Object.entries(budgets).forEach(([i, v]) => { if (v !== '' && Number(v) > 0) payload[i] = Number(v); });
    try {
      await updateSchedule(trackId, payload);
    } catch (e) {
      showToast(`Couldn't save schedule: ${e.message ?? e}`);
    }
  }

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Weekly schedule</h3>
      <div className={styles.weekRow}>
        {WEEKDAYS.map((label, i) => (
          <div key={i} className={styles.dayCell}>
            <Button
              size="sm"
              variant={budgets[i] !== '' && budgets[i] != null ? 'primary' : 'secondary'}
              aria-pressed={budgets[i] !== '' && budgets[i] != null}
              onClick={() => toggleDay(i)}
            >
              {label}
            </Button>
            {budgets[i] !== '' && budgets[i] != null && (
              <Input
                type="number"
                min={1}
                aria-label={`${label} items per day`}
                value={budgets[i]}
                onChange={e => setBudget(i, e.target.value)}
              />
            )}
          </div>
        ))}
      </div>
      <div className={styles.actionsRow}>
        <Button onClick={save}>Save schedule</Button>
      </div>
    </div>
  );
}

// ── Progress (Phase 1d) ──────────────────────────────────────────────────────

function ProgressTab() {
  const trackProgress = useStore(s => s.trackProgress);
  const fetchTrackProgress = useStore(s => s.fetchTrackProgress);

  useEffect(() => { fetchTrackProgress(); }, [fetchTrackProgress]);

  if (trackProgress.length === 0) {
    return (
      <EmptyState
        icon="inbox"
        title="Nothing to show yet"
        description={'Turn on "Show on Progress tab" for a track under Manage tracks.'}
      />
    );
  }

  return (
    <div className={styles.progressGrid}>
      {trackProgress.map(t => {
        const pct = t.itemsTotal > 0 ? Math.round((t.itemsDone / t.itemsTotal) * 100) : 0;
        return (
          <Card key={t.id} className={styles.progressCard}>
            <Ring pct={pct} size={56} />
            <div className={styles.progressBody}>
              <div className={styles.progressHeader}>
                <Badge>{cap(t.type)}</Badge>
                <span className={styles.progressTitle}>{t.title}</span>
              </div>
              <span className={styles.muted}>{t.itemsDone}/{t.itemsTotal} done</span>
              {t.deadline && (
                <Badge tone={t.onTrack ? 'success' : 'ember'}>
                  {t.onTrack ? 'On track' : 'Behind'} — due {t.deadline}
                </Badge>
              )}
            </div>
          </Card>
        );
      })}
    </div>
  );
}
