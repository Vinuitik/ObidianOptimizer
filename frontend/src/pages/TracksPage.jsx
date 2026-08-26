import { useEffect, useState } from 'react';
import useStore from '../store/useStore';
import Ring from '../components/atoms/Ring';
import { generateMinicourse, fetchMinicourseJob, approveMinicourse, importCsv, commitImport, pollTrackNow } from '../api/tracks';
import styles from './TracksPage.module.css';

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const TRACK_TYPES = ['book', 'course', 'article', 'custom', 'subscription'];
const SOURCE_TYPES = ['youtube_channel', 'feed'];

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

  if (!isAuthenticated) {
    return (
      <div className={styles.gate}>
        <p className={styles.gateText}>Sign in to view your tracks.</p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <div className={styles.tabBar}>
        {[
          { key: 'today',  label: 'Today' },
          { key: 'manage', label: 'Manage tracks' },
          { key: 'progress', label: 'Progress' },
        ].map(t => (
          <button
            key={t.key}
            className={`${styles.tabBtn} ${tab === t.key ? styles.tabBtnActive : ''}`}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'today' && <TodayTab />}
      {tab === 'manage' && <ManageTab />}
      {tab === 'progress' && <ProgressTab />}
    </div>
  );
}

// ── Today ─────────────────────────────────────────────────────────────────────

function TodayTab() {
  const todayItems     = useStore(s => s.todayItems);
  const todayMode      = useStore(s => s.todayMode);
  const todayOverBudget = useStore(s => s.todayOverBudget);
  const fetchTodayPlan = useStore(s => s.fetchTodayPlan);
  const setTrackMode   = useStore(s => s.setTrackMode);
  const completeTrackItem = useStore(s => s.completeTrackItem);
  const showToast       = useStore(s => s.showToast);
  const [addToReview, setAddToReview] = useState({}); // itemId -> bool
  const [busy, setBusy] = useState(null); // itemId currently completing
  const [switching, setSwitching] = useState(false);

  useEffect(() => { fetchTodayPlan(); }, [fetchTodayPlan]);

  async function markDone(item) {
    setBusy(item.itemId);
    try {
      await completeTrackItem(item.itemId, Boolean(addToReview[item.itemId]));
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

  return (
    <div className={styles.todayList}>
      <div className={styles.modeRow}>
        <span className={styles.modeLabel}>
          {todayMode === 'lockin' ? 'Lock-in mode — single-track focus' : 'Normal mode'}
        </span>
        <button className={styles.secondaryBtn} onClick={toggleMode} disabled={switching}>
          Switch to {todayMode === 'lockin' ? 'Normal' : 'Lock-in'}
        </button>
      </div>

      {todayOverBudget && (
        <div className={styles.overBudgetBanner}>
          <span>You're behind — must-priority deadlines alone exceed today's capacity.</span>
          <button className={styles.secondaryBtn} onClick={toggleMode} disabled={switching}>
            Switch to Lock-in?
          </button>
        </div>
      )}

      {todayItems.length === 0 && (
        <p className={styles.emptyMsg}>Nothing scheduled for today — create a track and set its weekly schedule under "Manage tracks".</p>
      )}
      {todayItems.map(item => (
        <div key={item.itemId} className={styles.todayItem}>
          <div className={styles.todayItemMain}>
            <span className={styles.trackBadge}>{item.trackTitle}</span>
            <span className={styles.todayItemTitle}>{item.title}</span>
          </div>
          <div className={styles.todayItemActions}>
            {item.notePath && (
              <label className={styles.reviewCheck}>
                <input
                  type="checkbox"
                  checked={Boolean(addToReview[item.itemId])}
                  onChange={e => setAddToReview(s => ({ ...s, [item.itemId]: e.target.checked }))}
                />
                add to spaced review
              </label>
            )}
            <button
              className={styles.doneBtn}
              disabled={busy === item.itemId}
              onClick={() => markDone(item)}
            >
              Mark done
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}

// ── Manage tracks ────────────────────────────────────────────────────────────

function ManageTab() {
  const tracks       = useStore(s => s.tracks);
  const fetchTracks  = useStore(s => s.fetchTracks);
  const createTrack  = useStore(s => s.createTrack);
  const showToast    = useStore(s => s.showToast);
  const [selectedId, setSelectedId] = useState(null);
  const [newTitle, setNewTitle] = useState('');
  const [newType, setNewType] = useState('book');
  const [sourceUrl, setSourceUrl] = useState('');
  const [sourceType, setSourceType] = useState('feed');
  const [sourceTypeTouched, setSourceTypeTouched] = useState(false);
  const [creating, setCreating] = useState(false);
  const [importing, setImporting] = useState(false);

  useEffect(() => { fetchTracks(); }, [fetchTracks]);

  function handleSourceUrlChange(value) {
    setSourceUrl(value);
    if (!sourceTypeTouched) {
      setSourceType(/youtube\.com|youtu\.be/.test(value) ? 'youtube_channel' : 'feed');
    }
  }

  async function handleCreate(e) {
    e.preventDefault();
    if (!newTitle.trim()) return;
    setCreating(true);
    try {
      const extra = newType === 'subscription' && sourceUrl.trim()
        ? { sourceUrl: sourceUrl.trim(), sourceType }
        : {};
      const track = await createTrack(newTitle.trim(), newType, extra);
      setNewTitle('');
      setSourceUrl('');
      setSourceType('feed');
      setSourceTypeTouched(false);
      setSelectedId(track.id);
    } catch (e) {
      showToast(`Couldn't create track: ${e.message ?? e}`);
    } finally {
      setCreating(false);
    }
  }

  const selected = tracks.find(t => t.id === selectedId) ?? null;

  return (
    <div className={styles.manage}>
      <div className={styles.trackList}>
        <CapacityPanel />
        <form className={styles.newTrackForm} onSubmit={handleCreate}>
          <input
            className={styles.textInput}
            placeholder="New track title…"
            value={newTitle}
            onChange={e => setNewTitle(e.target.value)}
          />
          <select className={styles.select} value={newType} onChange={e => setNewType(e.target.value)}>
            {TRACK_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
          {newType === 'subscription' && (
            <>
              <input
                className={styles.textInput}
                placeholder="Feed or channel URL…"
                value={sourceUrl}
                onChange={e => handleSourceUrlChange(e.target.value)}
              />
              <select
                className={styles.select}
                value={sourceType}
                onChange={e => { setSourceType(e.target.value); setSourceTypeTouched(true); }}
              >
                {SOURCE_TYPES.map(st => <option key={st} value={st}>{st}</option>)}
              </select>
            </>
          )}
          <button className={styles.primaryBtn} type="submit" disabled={creating || !newTitle.trim()}>
            + New track
          </button>
        </form>

        <button
          className={`${styles.secondaryBtn} ${styles.importTriggerBtn}`}
          onClick={() => { setImporting(true); setSelectedId(null); }}
        >
          Import from spreadsheet…
        </button>

        <div className={styles.trackItems}>
          {tracks.length === 0 && <p className={styles.emptyMsg}>No tracks yet.</p>}
          {tracks.map(t => (
            <button
              key={t.id}
              className={`${styles.trackListItem} ${selectedId === t.id ? styles.trackListItemActive : ''}`}
              onClick={() => setSelectedId(t.id)}
            >
              <span className={styles.trackListItemTitle}>{t.title}</span>
              <span className={styles.trackListItemMeta} data-status={t.status}>{t.status}</span>
            </button>
          ))}
        </div>
      </div>

      <div className={styles.divider} />

      <div className={styles.trackDetail}>
        {importing ? (
          <ImportPanel onClose={() => setImporting(false)} />
        ) : selected ? (
          <TrackDetail track={selected} onDeleted={() => setSelectedId(null)} />
        ) : (
          <div className={styles.emptySession}>
            <p className={styles.emptySessionText}>Select a track to edit its items and schedule, or create a new one.</p>
          </div>
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
      <div className={styles.importPanel}>
        <p className={styles.importSummary}>
          Imported {result.tracksCreated} track(s) and {result.itemsCreated} item(s).
        </p>
        <button className={styles.primaryBtn} onClick={onClose}>Done</button>
      </div>
    );
  }

  if (previewTracks) {
    return (
      <div className={styles.importPanel}>
        <h3 className={styles.sectionTitle}>Review import</h3>
        {previewTracks.map((t, i) => (
          <div key={i} className={styles.importTrackGroup}>
            <div className={styles.importTrackHeader}>
              <input
                className={styles.textInput}
                value={t.title}
                onChange={e => editTrack(i, { title: e.target.value })}
              />
              <select
                className={styles.select}
                value={t.type}
                onChange={e => editTrack(i, { type: e.target.value })}
              >
                {TRACK_TYPES.map(ty => <option key={ty} value={ty}>{ty}</option>)}
              </select>
              <button className={styles.dangerBtn} onClick={() => deleteTrack(i)}>Delete track</button>
            </div>
            <ul className={styles.itemList}>
              {previewItems.filter(it => it.trackIndex === i).map(it => (
                <li key={it._key} className={styles.itemRow}>
                  <input
                    className={styles.textInput}
                    value={it.title}
                    onChange={e => editItem(it._key, { title: e.target.value })}
                  />
                  <span className={styles.importItemStatus}>{it.status}</span>
                  <button className={styles.removeBtn} onClick={() => deleteItem(it._key)}>×</button>
                </li>
              ))}
              {previewItems.filter(it => it.trackIndex === i).length === 0 && (
                <p className={styles.emptyMsg}>No items in this track.</p>
              )}
            </ul>
          </div>
        ))}
        {previewTracks.length === 0 && <p className={styles.emptyMsg}>No tracks left to import.</p>}
        <div className={styles.importActions}>
          <button
            className={styles.primaryBtn}
            onClick={handleCommit}
            disabled={committing || previewTracks.length === 0}
          >
            Commit import
          </button>
          <button className={styles.secondaryBtn} onClick={onClose}>Cancel</button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.importPanel}>
      <h3 className={styles.sectionTitle}>Import from spreadsheet</h3>
      <p className={styles.emptySessionText}>
        Paste CSV text or select a .csv file. An LLM parses it into tracks and items for you to review before anything is created.
      </p>
      <textarea
        className={styles.importTextarea}
        placeholder="Paste CSV here…"
        value={text}
        onChange={e => setText(e.target.value)}
        rows={10}
      />
      <input type="file" accept=".csv,text/csv" onChange={handleFile} />
      {error && <p className={styles.minicourseError}>{error}</p>}
      <div className={styles.importActions}>
        <button className={styles.primaryBtn} onClick={handlePreview} disabled={loading || !text.trim()}>
          {loading ? 'Parsing…' : 'Preview'}
        </button>
        <button className={styles.secondaryBtn} onClick={onClose}>Cancel</button>
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
  const [open, setOpen] = useState(false);

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
      <button className={styles.capacityToggle} onClick={() => setOpen(o => !o)}>
        Daily capacity {open ? '▾' : '▸'}
      </button>
      {open && (
        <div className={styles.capacityRow}>
          {WEEKDAYS.map((label, i) => (
            <div key={i} className={styles.capacityCell}>
              <span className={styles.capacityLabel}>{label[0]}</span>
              <input
                type="number"
                min={0}
                className={styles.capacityInput}
                value={values[i] ?? ''}
                onChange={e => setValues(v => ({ ...v, [i]: e.target.value }))}
                onBlur={e => save(i, e.target.value)}
              />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TrackDetail({ track, onDeleted }) {
  const updateTrack   = useStore(s => s.updateTrack);
  const deleteTrack   = useStore(s => s.deleteTrack);
  const fetchTracks   = useStore(s => s.fetchTracks);
  const showToast     = useStore(s => s.showToast);

  const [title, setTitle] = useState(track.title);
  const [type, setType]   = useState(track.type);
  const [deadline, setDeadline] = useState(track.deadline ?? '');
  const [priority, setPriority] = useState(track.priority ?? '');
  const [includeInProgress, setIncludeInProgress] = useState(track.includeInProgress);
  const [polling, setPolling] = useState(false);

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

  async function handlePoll() {
    setPolling(true);
    try {
      await pollTrackNow(track.id);
      await fetchTracks();
    } catch (e) {
      showToast(`Couldn't poll: ${e.message ?? e}`);
    } finally {
      setPolling(false);
    }
  }

  return (
    <div className={styles.detailBody}>
      <div className={styles.detailHeader}>
        <input
          className={styles.titleInput}
          value={title}
          onChange={e => setTitle(e.target.value)}
          onBlur={() => title.trim() && title !== track.title && saveField({ title: title.trim() })}
        />
        <button className={styles.dangerBtn} onClick={handleDelete}>Delete track</button>
      </div>

      <div className={styles.fieldRow}>
        <label className={styles.fieldLabel}>Type</label>
        <select
          className={styles.select}
          value={type}
          onChange={e => { setType(e.target.value); saveField({ type: e.target.value }); }}
        >
          {TRACK_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
        </select>

        <label className={styles.fieldLabel}>Status</label>
        <select
          className={styles.select}
          value={track.status}
          onChange={e => saveField({ status: e.target.value })}
        >
          {['active', 'paused', 'done', 'archived'].map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>

      {track.type === 'subscription' && (
        <div className={styles.fieldRow}>
          <span className={styles.fieldLabel}>
            Last checked: {track.lastCheckedAt ? new Date(track.lastCheckedAt).toLocaleString() : 'never'}
          </span>
          <button className={styles.secondaryBtn} onClick={handlePoll} disabled={polling}>
            {polling ? 'Polling…' : 'Poll now'}
          </button>
        </div>
      )}

      <div className={styles.fieldRow}>
        <label className={styles.fieldLabel}>Deadline</label>
        <input
          type="date"
          className={styles.dateInput}
          value={deadline}
          onChange={e => setDeadline(e.target.value)}
          onBlur={() => {
            if (deadline) saveField({ deadline });
            else if (track.deadline) saveField({ clearDeadline: true });
          }}
        />
        {deadline && (
          <>
            <label className={styles.fieldLabel}>Priority</label>
            <select
              className={styles.select}
              value={priority || 'should'}
              onChange={e => { setPriority(e.target.value); saveField({ priority: e.target.value }); }}
            >
              {['must', 'should', 'could'].map(p => <option key={p} value={p}>{p}</option>)}
            </select>
          </>
        )}
      </div>

      <label className={styles.checkRow}>
        <input
          type="checkbox"
          checked={includeInProgress}
          onChange={e => { setIncludeInProgress(e.target.checked); saveField({ includeInProgress: e.target.checked }); }}
        />
        Include in Progress tab
      </label>

      <TrackItemsEditor trackId={track.id} />
      <TrackScheduleEditor trackId={track.id} />
      <MinicoursePanel trackId={track.id} />
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
    <div className={styles.minicoursePanel}>
      <h3 className={styles.sectionTitle}>Mini-course</h3>

      {(!job || job.status === 'FAILED') && (
        <button
          className={styles.primaryBtn}
          onClick={start}
          disabled={busy || !hasNotes}
          title={hasNotes ? undefined : 'Add at least one item with a linked note first'}
        >
          Generate mini-course
        </button>
      )}

      {job && (job.status === 'QUEUED' || job.status === 'RUNNING') && (
        <div className={styles.minicourseStatus}>
          <span className={styles.spinnerSm} />
          <span>{job.stage === 'lessons' ? 'Writing lessons…' : 'Generating syllabus…'}</span>
        </div>
      )}

      {job?.status === 'AWAITING_APPROVAL' && job.plan && (
        <div className={styles.minicoursePlan}>
          <p className={styles.minicoursePlanTitle}>{job.plan.course_title}</p>
          <ul className={styles.minicourseLessonList}>
            {job.plan.lessons.map((lesson, i) => (
              <li key={i} className={styles.minicourseLessonRow}>
                <label className={styles.checkRow}>
                  <input
                    type="checkbox"
                    checked={Boolean(checked[i])}
                    onChange={e => setChecked(c => ({ ...c, [i]: e.target.checked }))}
                  />
                  <span className={styles.minicourseLessonText}>
                    <span className={styles.minicourseLessonTitle}>{lesson.title}</span>
                    <span className={styles.minicourseLessonObjective}>{lesson.objective}</span>
                  </span>
                </label>
              </li>
            ))}
          </ul>
          <button className={styles.primaryBtn} onClick={approve} disabled={busy}>
            Approve &amp; write notes
          </button>
        </div>
      )}

      {job?.status === 'DONE' && (
        <div className={styles.minicourseDone}>
          <p className={styles.minicourseDoneText}>{job.results?.length ?? 0} lesson note(s) written.</p>
          {job.lesson_failures?.length > 0 && (
            <p className={styles.minicourseFailures}>
              Failed: {job.lesson_failures.map(f => f.title).join(', ')}
            </p>
          )}
        </div>
      )}

      {job?.status === 'FAILED' && (
        <p className={styles.minicourseError}>{job.error}</p>
      )}
    </div>
  );
}

function TrackItemsEditor({ trackId }) {
  const items = useStore(s => s.trackItems[trackId]) ?? [];
  const fetchTrackItems  = useStore(s => s.fetchTrackItems);
  const addTrackItem     = useStore(s => s.addTrackItem);
  const updateTrackItem  = useStore(s => s.updateTrackItem);
  const deleteTrackItem  = useStore(s => s.deleteTrackItem);
  const showToast        = useStore(s => s.showToast);
  const [newItemTitle, setNewItemTitle] = useState('');
  const [dragId, setDragId] = useState(null);

  useEffect(() => { fetchTrackItems(trackId); }, [trackId, fetchTrackItems]);

  async function handleAdd(e) {
    e.preventDefault();
    if (!newItemTitle.trim()) return;
    try {
      await addTrackItem(trackId, newItemTitle.trim());
      setNewItemTitle('');
    } catch (e) {
      showToast(`Couldn't add item: ${e.message ?? e}`);
    }
  }

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
        <span className={styles.dragHandle}>⠿</span>
        <span className={`${styles.itemTitle} ${item.status === 'done' ? styles.itemDone : ''}`}>
          {item.title}
        </span>
        {item.notePath && <span className={styles.itemNote} title={item.notePath}>📄</span>}
        <button className={styles.removeBtn} onClick={() => deleteTrackItem(trackId, item.id)}>×</button>
      </li>
    );
  }

  return (
    <div className={styles.itemsEditor}>
      <h3 className={styles.sectionTitle}>Items</h3>
      <form className={styles.newItemForm} onSubmit={handleAdd}>
        <input
          className={styles.textInput}
          placeholder="New item title…"
          value={newItemTitle}
          onChange={e => setNewItemTitle(e.target.value)}
        />
        <button className={styles.primaryBtn} type="submit" disabled={!newItemTitle.trim()}>Add</button>
      </form>
      <ul className={styles.itemList}>
        {groupForDisplay(items).map(row => row.type === 'group' ? (
          <li key={`group-${row.groupId}`} className={styles.itemGroup}>
            <details>
              <summary className={styles.itemGroupSummary}>{row.members.length} items from one source</summary>
              <ul className={styles.itemList}>
                {row.members.map(renderItemRow)}
              </ul>
            </details>
          </li>
        ) : renderItemRow(row.item))}
        {items.length === 0 && <p className={styles.emptyMsg}>No items yet.</p>}
      </ul>
    </div>
  );
}

function TrackScheduleEditor({ trackId }) {
  const schedule = useStore(s => s.trackSchedules[trackId]);
  const fetchTrackSchedule = useStore(s => s.fetchTrackSchedule);
  const updateSchedule     = useStore(s => s.updateSchedule);
  const showToast          = useStore(s => s.showToast);
  const [budgets, setBudgets] = useState({}); // weekday(string) -> budget or '' (off)

  useEffect(() => {
    fetchTrackSchedule(trackId).then(sched => {
      const next = {};
      WEEKDAYS.forEach((_, i) => { next[i] = sched[i] ?? ''; });
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
    <div className={styles.scheduleEditor}>
      <h3 className={styles.sectionTitle}>Weekly schedule</h3>
      <div className={styles.weekRow}>
        {WEEKDAYS.map((label, i) => (
          <div key={i} className={styles.dayCell}>
            <button
              className={`${styles.dayToggle} ${budgets[i] !== '' ? styles.dayToggleActive : ''}`}
              onClick={() => toggleDay(i)}
            >
              {label}
            </button>
            {budgets[i] !== '' && (
              <input
                type="number"
                min={1}
                className={styles.budgetInput}
                value={budgets[i]}
                onChange={e => setBudget(i, e.target.value)}
              />
            )}
          </div>
        ))}
      </div>
      <button className={styles.primaryBtn} onClick={save}>Save schedule</button>
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
      <div className={styles.emptySession}>
        <p className={styles.emptySessionText}>No tracks to show progress for yet — mark "Include in Progress" on a track under "Manage tracks".</p>
      </div>
    );
  }

  return (
    <div className={styles.progressGrid}>
      {trackProgress.map(t => {
        const pct = t.itemsTotal > 0 ? Math.round((t.itemsDone / t.itemsTotal) * 100) : 0;
        return (
          <div key={t.id} className={styles.progressCard}>
            <Ring pct={pct} size={56} />
            <div className={styles.progressCardBody}>
              <div className={styles.progressCardHeader}>
                <span className={styles.trackBadge}>{t.type}</span>
                <span className={styles.progressCardTitle}>{t.title}</span>
              </div>
              <span className={styles.progressCount}>{t.itemsDone}/{t.itemsTotal} done</span>
              {t.deadline && (
                <span className={t.onTrack ? styles.onTrackBadge : styles.behindBadge}>
                  {t.onTrack ? 'On track' : 'Behind'} — due {t.deadline}
                </span>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
