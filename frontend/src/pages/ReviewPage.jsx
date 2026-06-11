import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import useStore from '../store/useStore';
import FlashcardSession from '../components/organisms/FlashcardSession';
import styles from './ReviewPage.module.css';

export default function ReviewPage() {
  const reviewNotes       = useStore(s => s.reviewNotes);
  const reviewHasMore     = useStore(s => s.reviewHasMore);
  const initReviewSession = useStore(s => s.initReviewSession);
  const loadMoreReview    = useStore(s => s.loadMoreReview);
  const isAuthenticated   = useStore(s => s.isAuthenticated);
  const openTab           = useStore(s => s.openTab);
  const navigate          = useNavigate();

  const [activeNote, setActiveNote]     = useState(null); // { shortName, fullPath }
  const [sessionKey, setSessionKey]     = useState(0);    // bumped to reset session

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

      {/* Right — flashcard session */}
      <div className={styles.sessionPane}>
        {activeNote ? (
          <FlashcardSession
            key={sessionKey}
            notePath={activeNote.fullPath}
            onReviewNote={handleReviewNote}
            onClose={handleClose}
          />
        ) : (
          <div className={styles.emptySession}>
            <p className={styles.emptySessionText}>
              Select a note from the list to start a flashcard session.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
