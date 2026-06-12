import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import useStore from '../store/useStore';
import { gradeNote } from '../api/notes';
import FlashcardSession from '../components/organisms/FlashcardSession';
import cardStyles from '../components/organisms/FlashcardSession.module.css';
import styles from './ReviewPage.module.css';

export default function ReviewPage() {
  const reviewNotes       = useStore(s => s.reviewNotes);
  const reviewHasMore     = useStore(s => s.reviewHasMore);
  const initReviewSession = useStore(s => s.initReviewSession);
  const loadMoreReview    = useStore(s => s.loadMoreReview);
  const isAuthenticated   = useStore(s => s.isAuthenticated);
  const flashcardsEnabled = useStore(s => s.settings.flashcardsEnabled ?? true);
  const openTab           = useStore(s => s.openTab);
  const navigate          = useNavigate();

  const [activeNote, setActiveNote] = useState(null); // { shortName, fullPath }
  const [sessionKey, setSessionKey] = useState(0);    // bumped to reset session

  useEffect(() => {
    if (isAuthenticated) initReviewSession();
  }, [isAuthenticated]);

  function startSession(note) {
    setActiveNote(note);
    setSessionKey(k => k + 1);
  }

  function handleReviewNote(fullPath) {
    openTab(fullPath);
    navigate('/');
  }

  function handleClose() {
    setActiveNote(null);
  }

  if (!isAuthenticated) {
    return (
      <div className={styles.gate}>
        <p className={styles.gateText}>Sign in to review.</p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      {/* Left — note list */}
      <div className={styles.noteList}>
        <div className={styles.listHeader}>
          <h2 className={styles.listTitle}>Due for review</h2>
          <span className={styles.listCount}>{reviewNotes.length}</span>
        </div>

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
              {note.shortName}
            </button>
          ))}
        </div>

        {reviewHasMore && (
          <button className={styles.loadMore} onClick={loadMoreReview}>Load more</button>
        )}
      </div>

      {/* Divider */}
      <div className={styles.divider} />

      {/* Right — flashcard test OR self-rated slideshow, per settings toggle */}
      <div className={styles.sessionPane}>
        {activeNote ? (
          flashcardsEnabled ? (
            <FlashcardSession
              key={sessionKey}
              notePath={activeNote.fullPath}
              onReviewNote={handleReviewNote}
              onClose={handleClose}
            />
          ) : (
            <SlideshowReview
              key={sessionKey}
              note={activeNote}
              onReviewNote={handleReviewNote}
              onClose={handleClose}
            />
          )
        ) : (
          <div className={styles.emptySession}>
            <p className={styles.emptySessionText}>
              {flashcardsEnabled
                ? 'Select a note from the list to start a flashcard test.'
                : 'Select a note from the list to review and self-rate it.'}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

// ── Slideshow mode (flashcards off): read the note, self-rate with 4 bands ────

const BANDS = [
  { value: 'HARD',      label: 'Hard',      hint: 'review soon' },
  { value: 'GOOD',      label: 'Good',      hint: 'standard interval' },
  { value: 'EASY',      label: 'Easy',      hint: 'longer interval' },
  { value: 'VERY_EASY', label: 'Very easy', hint: 'longest interval' },
];

function SlideshowReview({ note, onReviewNote, onClose }) {
  const dismissFromReview = useStore(s => s.dismissFromReview);
  const showToast         = useStore(s => s.showToast);
  const [graded, setGraded] = useState(null); // grade result after rating

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
    <div className={cardStyles.session} data-testid="slideshow-review">
      <div className={cardStyles.card}>
        <span className={cardStyles.cardType}>Self-rated review</span>
        <p className={cardStyles.question}>{note.shortName}</p>

        {!graded ? (
          <>
            <p className={cardStyles.lockedNote}>
              Read the note, then rate how well you remembered it.
            </p>
            <div className={cardStyles.options} data-testid="band-buttons">
              {BANDS.map(b => (
                <button key={b.value} className={cardStyles.option}
                        onClick={() => rate(b.value)} data-testid={`band-${b.value}`}>
                  <span className={cardStyles.optionLetter}>{b.label[0]}</span>
                  {b.label} — {b.hint}
                </button>
              ))}
            </div>
          </>
        ) : (
          <p className={cardStyles.lockedNote} data-testid="slideshow-graded">
            Graded <strong>{graded.band.replace('_', ' ')}</strong> — next review{' '}
            {new Date(graded.due).toLocaleDateString()}
          </p>
        )}
      </div>

      <div className={cardStyles.resultFooter}>
        <button className={cardStyles.reviewNoteBtn} onClick={() => onReviewNote(note.fullPath)}>
          Open note →
        </button>
        <button className={cardStyles.navBtn} onClick={onClose}>
          {graded ? 'Next note' : 'Close'}
        </button>
      </div>
    </div>
  );
}
