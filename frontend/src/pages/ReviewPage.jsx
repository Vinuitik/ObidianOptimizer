import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import useStore from '../store/useStore';
import { gradeNoteOffline as gradeNote, fetchNoteContentOffline as fetchNoteContent, isDriveMode } from '../pwa/offlineApi';
import useOffline from '../pwa/useOffline';
import FlashcardSession from '../components/organisms/FlashcardSession';
import NoteRenderer from '../components/molecules/NoteRenderer';
import Button from '../components/atoms/Button';
import Badge from '../components/atoms/Badge';
import Skeleton from '../components/atoms/Skeleton';
import ListRow from '../components/molecules/ListRow';
import EmptyState from '../components/molecules/EmptyState';
import ErrorState from '../components/molecules/ErrorState';
import PageHeader from '../components/molecules/PageHeader';
import { listItem } from '../components/motion/presets';
import { pickNextInOrder } from './reviewOrder';
import styles from './ReviewPage.module.css';

// How long the "Graded Good" confirmation stays before the next note opens.
export const ADVANCE_MS = 800;

const reducedItem = { initial: { opacity: 0 }, animate: { opacity: 1 }, exit: { opacity: 0 } };

const bandLabel = (b) => {
  const t = b.replace('_', ' ').toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
};

export default function ReviewPage() {
  const reviewNotes       = useStore(s => s.reviewNotes);
  const reviewHasMore     = useStore(s => s.reviewHasMore);
  const initReviewSession = useStore(s => s.initReviewSession);
  const loadMoreReview    = useStore(s => s.loadMoreReview);
  const isAuthenticated   = useStore(s => s.isAuthenticated);
  const flashcardsEnabled = useStore(s => s.settings.flashcardsEnabled ?? true);
  const online            = useOffline();
  const reduced           = useReducedMotion();
  const itemMotion        = reduced ? reducedItem : listItem;
  // Flashcards work offline in Drive mode via pre-built assignments (server grades on
  // sync). Non-Drive offline (desktop blip) falls back to self-rated review of the note.
  const useFlashcards     = flashcardsEnabled && (online || isDriveMode());

  const [activeNote, setActiveNote] = useState(null); // { shortName, fullPath }
  const [sessionKey, setSessionKey] = useState(0);    // bumped to reset session
  const [inlineNote, setInlineNote] = useState(null); // { title, raw } while showing note inline
  const [loadState, setLoadState]   = useState('loading'); // loading | ready | error
  const [flash, setFlash]           = useState(null);      // { title, band } — "Graded Good" row, ~800ms
  const autoOpened = useRef(false);

  function load() {
    setLoadState('loading');
    return Promise.resolve(initReviewSession())
      .then(() => setLoadState('ready'))
      .catch(() => setLoadState('error'));
  }

  useEffect(() => {
    if (isAuthenticated) load();
  }, [isAuthenticated]);

  function startSession(note) {
    // Hybrid routing: a flashcard-track note (and flashcards actually usable here) →
    // the auto-graded test. Everything else is read-and-self-rate — open the note
    // inline with the grade bar (canGrade=true), the same surface the no-cards path uses.
    if (note.track === 'flashcard' && useFlashcards) {
      setActiveNote(note);
      setInlineNote(null);
      setSessionKey(k => k + 1);
    } else {
      setActiveNote(null);
      handleReviewNote(note.fullPath, true);
    }
  }

  // Auto-open the first due item once the queue has loaded (re-arms when the queue
  // empties, so "Load more" batches open too). Back/Close never re-trigger it.
  useEffect(() => {
    if (loadState !== 'ready') return;
    if (reviewNotes.length === 0) { autoOpened.current = false; return; }
    if (!autoOpened.current && !activeNote && !inlineNote) {
      autoOpened.current = true;
      startSession(reviewNotes[0]);
    }
  }, [loadState, reviewNotes, activeNote, inlineNote]);

  // The "Graded Good" confirmation row clears itself.
  useEffect(() => {
    if (!flash) return undefined;
    const t = setTimeout(() => setFlash(null), ADVANCE_MS + 100);
    return () => clearTimeout(t);
  }, [flash]);

  // canGrade: show the self-rate band bar only when the note was NOT already graded.
  // The no-cards path (FlashcardSession error → "Review note directly") passes true —
  // there's no test, so the inline note IS the grading surface. After a completed test
  // (result phase) or in slideshow mode (graded by its own bands) it's false, so
  // "Review note directly" just re-opens the note read-only instead of re-prompting.
  async function handleReviewNote(fullPath, canGrade = false) {
    try {
      const raw = await fetchNoteContent(fullPath);
      const title = fullPath.split(/[/\\]/).pop().replace(/\.md$/, '');
      setInlineNote({ title, raw, fullPath, canGrade });
    } catch {
      // fallback: if fetch fails just stay on current view
    }
  }

  // After a note is reviewed, auto-advance to the next due note in DFS pre-order
  // (vault tree order: siblings in a folder before descending into subfolders, files
  // before subdirectories — see pages/reviewOrder.js). `fromPath` is the note just
  // finished — used as the walk's reference point so we resume right after it.
  // Nothing left → fall back to the empty state. Read fresh from the store: grading
  // dismisses the finished note from reviewNotes, so getState() reflects the real
  // remaining set (fromPath itself is already gone from it).
  function handleClose(fromPath) {
    const done = fromPath ?? inlineNote?.fullPath ?? activeNote?.fullPath ?? null;
    const next = pickNextInOrder(useStore.getState().reviewNotes, done);
    if (next) {
      startSession(next);   // transitions cleanly (sets/clears active+inline itself)
    } else {
      setActiveNote(null);
      setInlineNote(null);
    }
  }

  if (!isAuthenticated) {
    return (
      <div className={styles.gate}>
        <EmptyState icon="user" title="Sign in to review" />
      </div>
    );
  }

  if (loadState === 'error') {
    return (
      <div className={styles.gate}>
        <ErrorState message="Couldn't load your review queue" detail="Check your connection and try again." onRetry={load} />
      </div>
    );
  }

  const hasSession = Boolean(activeNote || inlineNote);

  // Nothing due (and nothing mid-flight): one friendly full-page state.
  if (loadState === 'ready' && reviewNotes.length === 0 && !hasSession && !flash) {
    return (
      <div className={styles.gate}>
        <EmptyState
          icon="check"
          title="All caught up"
          description="No notes are due right now. Nice work."
          action={reviewHasMore ? { label: 'Load more', onClick: loadMoreReview } : undefined}
        />
      </div>
    );
  }

  // Split summary for the list header (how the day is divided).
  const flashcardCount = reviewNotes.filter(n => n.track === 'flashcard').length;
  const readCount      = reviewNotes.length - flashcardCount;
  const subtitle = loadState === 'loading' ? undefined
    : `${reviewNotes.length} due · ${flashcardCount > 0 ? `${flashcardCount} flashcard${flashcardCount > 1 ? 's' : ''} · ` : ''}${readCount} to read`;

  return (
    <div className={`${styles.page} ${hasSession ? styles.hasSession : ''}`}>
      {/* Left — note list (queue order = server's oldest-due-first; the payload carries no due dates) */}
      <div className={styles.noteList}>
        <PageHeader className={styles.listHeader} title="Review" subtitle={subtitle} />

        <div className={styles.listItems} aria-live="polite">
          {loadState === 'loading' && <Skeleton lines={5} height={36} />}
          <ul className={styles.rows}>
            <AnimatePresence initial={false}>
              {flash && (
                <motion.li key="__flash" className={styles.row} {...itemMotion} data-testid="graded-flash">
                  <ListRow leading="✓" title={flash.title} trailing={<Badge tone="success">Graded {flash.band}</Badge>} />
                </motion.li>
              )}
              {reviewNotes.map(note => (
                <motion.li key={note.fullPath} className={styles.row} {...itemMotion}>
                  <ListRow
                    title={note.shortName}
                    active={activeNote?.fullPath === note.fullPath || inlineNote?.fullPath === note.fullPath}
                    onClick={() => startSession(note)}
                    trailing={
                      <Badge tone={note.track === 'flashcard' ? 'accent' : 'neutral'}>
                        {note.track === 'flashcard' ? 'cards' : 'read'}
                      </Badge>
                    }
                  />
                </motion.li>
              ))}
            </AnimatePresence>
          </ul>
        </div>

        {reviewHasMore && (
          <Button className={styles.loadMore} onClick={loadMoreReview}>Load more</Button>
        )}
      </div>

      {/* Divider */}
      <div className={styles.divider} />

      {/* Right — flashcard test (flashcard track) or inline read + self-rate (read track) */}
      <div className={styles.sessionPane}>
        {inlineNote ? (
          <InlineNoteReview
            key={inlineNote.fullPath}
            note={inlineNote}
            onBack={() => setInlineNote(null)}
            onGraded={(g) => setFlash({ title: inlineNote.title, band: bandLabel(g.band) })}
            onClose={() => handleClose(inlineNote.fullPath)}
          />
        ) : activeNote ? (
          <FlashcardSession
            key={sessionKey}
            notePath={activeNote.fullPath}
            onReviewNote={handleReviewNote}
            onClose={() => handleClose(activeNote.fullPath)}
          />
        ) : loadState === 'loading' ? (
          <div className={styles.emptySession}><Skeleton lines={6} height={18} /></div>
        ) : (
          <div className={styles.emptySession}>
            <EmptyState
              icon="file"
              title="Pick a note"
              description={online || isDriveMode()
                ? '“cards” notes start a flashcard test, “read” notes open to read and self-rate.'
                : 'Offline — select a downloaded note to read and self-rate. Grades sync when you reconnect.'}
            />
          </div>
        )}
      </div>
    </div>
  );
}

// The four FSRS grade bands (cards/FLOWS.md → Review Flow). Keys 1-4 map in this order;
// Enter = Good. No "very hard" — HARD is the lowest.
const BANDS = [
  { value: 'HARD',      label: 'Hard',      hint: 'review soon',       key: '1' },
  { value: 'GOOD',      label: 'Good',      hint: 'standard interval', key: '2' },
  { value: 'EASY',      label: 'Easy',      hint: 'longer interval',   key: '3' },
  { value: 'VERY_EASY', label: 'Very easy', hint: 'longest interval',  key: '4' },
];

const isTyping = (el) => !!el && (['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName) || el.isContentEditable);

// ── Direct-note review: read the note, self-grade to FSRS ──
// Shown for read-track notes and when a note has no cards. Band buttons (or keys 1-4 /
// Enter=Good) post to POST /api/reviews/grade; ~800ms after a grade the next note opens.

function InlineNoteReview({ note, onBack, onClose, onGraded }) {
  const completeReview = useStore(s => s.completeReview);
  const [graded, setGraded] = useState(null);
  const busy = useRef(false);
  const timer = useRef(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  const canGrade = note.canGrade ?? false;

  useEffect(() => () => clearTimeout(timer.current), []);

  async function rate(band) {
    if (busy.current || graded) return;
    busy.current = true;
    try {
      const result = await completeReview(note.fullPath, () => gradeNote(note.fullPath, band));
      setGraded(result);
      onGraded?.(result);
      timer.current = setTimeout(() => closeRef.current(), ADVANCE_MS);
    } catch { /* toast already shown by completeReview */ }
    busy.current = false;
  }

  function nextNow() {
    clearTimeout(timer.current);
    onClose();
  }

  const rateRef = useRef(rate);
  rateRef.current = rate;
  useEffect(() => {
    if (!canGrade || graded) return undefined;
    function onKey(e) {
      if (e.metaKey || e.ctrlKey || e.altKey || e.repeat || isTyping(e.target)) return;
      const band = BANDS.find(b => b.key === e.key);
      if (band) { e.preventDefault(); rateRef.current(band.value); return; }
      // Enter = Good, unless it's meant to activate a focused button/link.
      if (e.key === 'Enter' && !['BUTTON', 'A'].includes(e.target?.tagName)) {
        e.preventDefault(); rateRef.current('GOOD');
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [canGrade, graded]);

  return (
    <div className={styles.inlineNote}>
      <div className={styles.inlineNoteHeader}>
        <Button variant="ghost" size="sm" onClick={onBack}>← Back</Button>
        <span className={styles.inlineNoteTitle}>{note.title}</span>
      </div>
      <div className={styles.inlineNoteBody}>
        <NoteRenderer content={note.raw} resetKey={note.fullPath || note.title} />
      </div>

      {/* Grade bar — only when this note is the grading surface (no test done).
          After a completed flashcard test the note is already scheduled, so we
          just show a "Next note" bar instead of re-asking how hard it was. */}
      {!canGrade ? (
        <div className={styles.inlineGrade} data-testid="inline-grade">
          <Button variant="primary" onClick={onClose} autoFocus>Next note →</Button>
        </div>
      ) : (
      <div className={styles.inlineGrade} data-testid="inline-grade">
        {!graded ? (
          <>
            <span className={styles.inlineGradePrompt}>How well did you remember this note? Keys 1-4 · Enter = Good</span>
            <div className={styles.inlineGradeBtns} data-testid="band-buttons">
              {BANDS.map(b => (
                <Button key={b.value} variant={b.value === 'GOOD' ? 'primary' : 'secondary'}
                        className={styles.inlineGradeBtn}
                        onClick={() => rate(b.value)} data-testid={`band-${b.value}`}
                        title={b.hint} aria-keyshortcuts={b.key}>
                  {b.label} <kbd className={styles.kbd}>{b.key}</kbd>
                </Button>
              ))}
            </div>
          </>
        ) : (
          <div className={styles.inlineGraded} data-testid="inline-graded" role="status">
            <span>
              Graded <strong>{bandLabel(graded.band)}</strong>
              {graded.queued
                ? ' — queued, will sync when you reconnect'
                : ` — next review ${new Date(graded.due).toLocaleDateString()}`}
            </span>
            <Button variant="secondary" onClick={nextNow} autoFocus>Next note →</Button>
          </div>
        )}
      </div>
      )}
    </div>
  );
}
