import { useEffect, useState } from 'react';
import useStore from '../store/useStore';
import { gradeNoteOffline as gradeNote, fetchNoteContentOffline as fetchNoteContent, isDriveMode } from '../pwa/offlineApi';
import useOffline from '../pwa/useOffline';
import FlashcardSession from '../components/organisms/FlashcardSession';
import NoteRenderer from '../components/molecules/NoteRenderer';
import styles from './ReviewPage.module.css';

export default function ReviewPage() {
  const reviewNotes       = useStore(s => s.reviewNotes);
  const reviewHasMore     = useStore(s => s.reviewHasMore);
  const initReviewSession = useStore(s => s.initReviewSession);
  const loadMoreReview    = useStore(s => s.loadMoreReview);
  const isAuthenticated   = useStore(s => s.isAuthenticated);
  const flashcardsEnabled = useStore(s => s.settings.flashcardsEnabled ?? true);
  const online            = useOffline();
  // Flashcards work offline in Drive mode via pre-built assignments (server grades on
  // sync). Non-Drive offline (desktop blip) falls back to self-rated review of the note.
  const useFlashcards     = flashcardsEnabled && (online || isDriveMode());

  const [activeNote, setActiveNote] = useState(null); // { shortName, fullPath }
  const [sessionKey, setSessionKey] = useState(0);    // bumped to reset session
  const [inlineNote, setInlineNote] = useState(null); // { title, raw } while showing note inline

  useEffect(() => {
    if (isAuthenticated) initReviewSession();
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

  function handleClose() {
    setActiveNote(null);
    setInlineNote(null);
  }

  if (!isAuthenticated) {
    return (
      <div className={styles.gate}>
        <p className={styles.gateText}>Sign in to review.</p>
      </div>
    );
  }

  const hasSession = Boolean(activeNote || inlineNote);

  // Split summary for the list header (how the day is divided).
  const flashcardCount = reviewNotes.filter(n => n.track === 'flashcard').length;
  const readCount      = reviewNotes.length - flashcardCount;

  return (
    <div className={`${styles.page} ${hasSession ? styles.hasSession : ''}`}>
      {/* Left — note list */}
      <div className={styles.noteList}>
        <div className={styles.listHeader}>
          <h2 className={styles.listTitle}>Due for review</h2>
          <span className={styles.listCount}>{reviewNotes.length}</span>
        </div>
        {reviewNotes.length > 0 && (
          <p className={styles.listSplit}>
            {flashcardCount > 0 ? `${flashcardCount} flashcard${flashcardCount > 1 ? 's' : ''} · ` : ''}
            {readCount} to read
          </p>
        )}

        <div className={styles.listItems}>
          {reviewNotes.length === 0 && (
            <p className={styles.emptyMsg}>No notes due — you're all caught up!</p>
          )}
          {reviewNotes.map(note => (
            <button
              key={note.fullPath}
              className={`${styles.noteItem} ${activeNote?.fullPath === note.fullPath ? styles.noteItemActive : ''}`}
              onClick={() => startSession(note)}
            >
              <span className={styles.noteItemName}>{note.shortName}</span>
              <span className={styles.trackBadge} data-track={note.track === 'flashcard' ? 'flashcard' : 'read'}>
                {note.track === 'flashcard' ? 'cards' : 'read'}
              </span>
            </button>
          ))}
        </div>

        {reviewHasMore && (
          <button className={styles.loadMore} onClick={loadMoreReview}>Load more</button>
        )}
      </div>

      {/* Divider */}
      <div className={styles.divider} />

      {/* Right — flashcard test (flashcard track) or inline read + self-rate (read track) */}
      <div className={styles.sessionPane}>
        {inlineNote ? (
          <InlineNoteReview
            note={inlineNote}
            onBack={() => setInlineNote(null)}
            onClose={handleClose}
          />
        ) : activeNote ? (
          <FlashcardSession
            key={sessionKey}
            notePath={activeNote.fullPath}
            onReviewNote={handleReviewNote}
            onClose={handleClose}
          />
        ) : (
          <div className={styles.emptySession}>
            <p className={styles.emptySessionText}>
              {online || isDriveMode()
                ? 'Select a note — “cards” notes start a flashcard test, “read” notes open to read and self-rate.'
                : 'Offline — select a downloaded note to read and self-rate. Grades sync when you reconnect.'}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

// The four FSRS grade bands (cards/FLOWS.md → Review Flow). Shared by the
// slideshow and the direct-note review. No "very hard" — HARD is the lowest.
const BANDS = [
  { value: 'HARD',      label: 'Hard',      hint: 'review soon' },
  { value: 'GOOD',      label: 'Good',      hint: 'standard interval' },
  { value: 'EASY',      label: 'Easy',      hint: 'longer interval' },
  { value: 'VERY_EASY', label: 'Very easy', hint: 'longest interval' },
];

// ── Direct-note review (no flashcards yet): read the note, self-grade to FSRS ──
// Shown when a note has no cards — FlashcardSession's "Review note directly →"
// routes here. The band buttons post to POST /api/reviews/grade, same as the
// slideshow, so the note is actually rescheduled instead of being read-only.

function InlineNoteReview({ note, onBack, onClose }) {
  const dismissFromReview = useStore(s => s.dismissFromReview);
  const showToast         = useStore(s => s.showToast);
  const [graded, setGraded] = useState(null);
  const canGrade = note.canGrade ?? false;

  async function rate(band) {
    try {
      const result = await gradeNote(note.fullPath, band);
      setGraded(result);
      dismissFromReview(note.fullPath);
    } catch (e) {
      showToast(`Rating failed: ${e.message ?? e}`);
    }
  }

  return (
    <div className={styles.inlineNote}>
      <div className={styles.inlineNoteHeader}>
        <button className={styles.inlineNoteBack} onClick={onBack}>← Back</button>
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
          <button className={styles.inlineGradeBtn} onClick={onClose}>Next note →</button>
        </div>
      ) : (
      <div className={styles.inlineGrade} data-testid="inline-grade">
        {!graded ? (
          <>
            <span className={styles.inlineGradePrompt}>How well did you remember this note?</span>
            <div className={styles.inlineGradeBtns} data-testid="band-buttons">
              {BANDS.map(b => (
                <button key={b.value} className={styles.inlineGradeBtn}
                        onClick={() => rate(b.value)} data-testid={`band-${b.value}`}
                        title={b.hint}>
                  {b.label}
                </button>
              ))}
            </div>
          </>
        ) : (
          <div className={styles.inlineGraded} data-testid="inline-graded">
            <span>
              Graded <strong>{graded.band.replace('_', ' ')}</strong>
              {graded.queued
                ? ' — queued, will sync when you reconnect'
                : ` — next review ${new Date(graded.due).toLocaleDateString()}`}
            </span>
            <button className={styles.inlineGradeBtn} onClick={onClose}>Next note →</button>
          </div>
        )}
      </div>
      )}
    </div>
  );
}

// (Former SlideshowReview removed: the read track now uses InlineNoteReview, which
// renders the note body inline above the same four self-rate band buttons.)
