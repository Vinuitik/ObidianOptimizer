import { useEffect, useState } from 'react';
import useStore from '../../store/useStore';
import {
  buildAssignmentOffline as buildAssignment,
  submitAttemptOffline as submitAttempt,
  completeAssignmentOffline as completeAssignment,
} from '../../pwa/offlineApi';
import { flagCard } from '../../api/notes';
import styles from './FlashcardSession.module.css';

const SESSION_POINTS = 10; // point budget per mini-test (points = card difficulty 1-5)

// question text: exercises render their frozen variant, others carry it in the payload
function questionOf(card, variants) {
  if (card.type === 'exercise') return variants?.[card.id]?.rendered ?? card.payload.template;
  return card.payload.question;
}

const TYPE_LABELS = { mcq: 'Multiple choice', open: 'Open ended', exercise: 'Exercise' };

// Internal sentinel for an explicit "I don't know". Stored in the answers map so
// the breakdown can label it; sent to the backend as '' which every card type
// grades WRONG (mcq: index≠correct · open: empty-answer fast path · exercise:
// parse/compare fail). Crucially it STILL records an attempt, so the card's
// difficulty stays in the per-note score denominator (a skipped card would not).
const IDK = '\u0000idk';

// The correct answer to reveal at the end (data already on the client).
function correctAnswerOf(card, variants) {
  if (card.type === 'mcq') return card.payload.options[card.payload.correct];
  if (card.type === 'open') return (card.payload.reference_answers ?? [])[0] ?? null;
  if (card.type === 'exercise') return variants?.[card.id]?.expected ?? null;
  return null;
}

// What the student answered, for the end-of-test breakdown.
function answerDisplayOf(card, value) {
  if (value === IDK) return 'I don’t know';
  if (card.type === 'mcq') {
    return (value != null && value !== '' ? card.payload.options[Number(value)] : null) ?? '—';
  }
  return value || '—';
}

export default function FlashcardSession({ notePath, onReviewNote, onClose }) {
  const dismissFromReview = useStore(s => s.dismissFromReview);
  const showToast         = useStore(s => s.showToast);
  const [flagged, setFlagged]       = useState({});        // { [cardId]: true } once flagged
  const [phase, setPhase]           = useState('loading'); // loading | quiz | result | error
  const [error, setError]           = useState(null);
  const [assignment, setAssignment] = useState(null);
  const [idx, setIdx]               = useState(0);
  const [answers, setAnswers]       = useState({});  // { [cardId]: string }
  const [verdicts, setVerdicts]     = useState({});  // { [cardId]: {verdict, pointsEarned, maxPoints, feedback} }
  const [completion, setCompletion] = useState(null); // { notes: [{notePath, score, band, due}] }

  useEffect(() => {
    let cancelled = false;
    buildAssignment(notePath, SESSION_POINTS)
      .then(a => { if (!cancelled) { setAssignment(a); setPhase('quiz'); } })
      .catch(e => { if (!cancelled) { setError(String(e.message ?? e)); setPhase('error'); } });
    return () => { cancelled = true; };
  }, [notePath]);

  if (phase === 'loading') {
    return <div className={styles.session} data-testid="flashcard-loading">
      <p className={styles.lockedNote}>Building your test…</p>
    </div>;
  }
  if (phase === 'error') {
    return <div className={styles.session} data-testid="flashcard-error">
      <p className={styles.lockedNote}>Could not build a test for this note: {error}</p>
      <div className={styles.nav}>
        {/* No cards for this note → the inline note IS the grading surface (canGrade). */}
        <button className={styles.reviewNoteBtn} onClick={() => onReviewNote(notePath, true)}>
          Review note directly →
        </button>
        <button className={styles.navBtn} onClick={onClose}>Close</button>
      </div>
    </div>;
  }

  const cards    = assignment.cards;
  const variants = assignment.variants ?? {};
  const card     = cards[idx];
  const isLocked = Boolean(verdicts[card?.id]);

  async function submitAnswer(cardId, value) {
    setAnswers(prev => ({ ...prev, [cardId]: value }));
    const sent = value === IDK ? '' : value;  // IDK records a WRONG attempt, not a skip
    try {
      const result = await submitAttempt(assignment.id, cardId, sent);
      setVerdicts(prev => ({ ...prev, [cardId]: result }));
    } catch (e) {
      setVerdicts(prev => ({ ...prev, [cardId]: { verdict: 'WRONG', pointsEarned: 0, maxPoints: 0, feedback: 'verification failed' } }));
    }
  }

  async function next() {
    if (idx < cards.length - 1) { setIdx(i => i + 1); return; }
    try {
      const result = await completeAssignment(assignment.id);
      setCompletion(result);
      // Grading succeeded server-side (FSRS + bandit rescheduled the note), so it's
      // no longer due — drop it from the visible review list. Slideshow mode does
      // the same on rate(). Without this the note lingers and looks un-reviewed.
      dismissFromReview(notePath);
    } catch { /* result phase still renders per-card verdicts */ }
    setPhase('result');
  }

  // ── Quiz phase ────────────────────────────────────────────────────────────

  if (phase === 'quiz') {
    return (
      <div className={styles.session} data-testid="flashcard-session">
        <div className={styles.progress}>
          <span className={styles.progressText}>{idx + 1} / {cards.length}</span>
          <div className={styles.progressBar}>
            <div className={styles.progressFill}
                 style={{ width: `${((idx + 1) / cards.length) * 100}%` }} />
          </div>
        </div>

        <div className={styles.card} data-testid="flashcard-card">
          <span className={styles.cardType}>
            {TYPE_LABELS[card.type]} · {card.difficulty} pt{card.difficulty > 1 ? 's' : ''}
          </span>
          <p className={styles.question}>{questionOf(card, variants)}</p>

          {card.type === 'mcq' && (
            <McqOptions card={card} answer={answers[card.id]} locked={isLocked}
                        onSelect={val => setAnswers(prev => ({ ...prev, [card.id]: val }))}
                        onSubmit={() => submitAnswer(card.id, answers[card.id])} />
          )}
          {card.type === 'open' && (
            <OpenEndedInput answer={answers[card.id] === IDK ? '' : (answers[card.id] ?? '')} locked={isLocked}
                            onChange={val => setAnswers(prev => ({ ...prev, [card.id]: val }))}
                            onSubmit={() => submitAnswer(card.id, answers[card.id] ?? '')} />
          )}
          {card.type === 'exercise' && (
            <ExerciseInput answer={answers[card.id] === IDK ? '' : (answers[card.id] ?? '')} locked={isLocked}
                           onChange={val => setAnswers(prev => ({ ...prev, [card.id]: val }))}
                           onSubmit={() => submitAnswer(card.id, answers[card.id] ?? '')} />
          )}

          {/* Exam style: the answer is locked but correctness is NOT revealed here —
              the full breakdown (your answer, correct answer, feedback) shows only
              at the end, so earlier questions can't leak answers to later ones. */}
          {isLocked && (
            <p className={styles.lockedNote} data-testid="recorded">
              {answers[card.id] === IDK ? 'Marked “I don’t know”.' : 'Answer recorded.'}
              {' '}Press {idx === cards.length - 1 ? 'Finish' : 'Next'} to continue.
            </p>
          )}
        </div>

        <div className={styles.nav}>
          <button className={styles.navBtn} onClick={() => setIdx(i => Math.max(0, i - 1))}
                  disabled={idx === 0}>← Prev</button>
          {!isLocked && (
            <button className={styles.navBtn} onClick={() => submitAnswer(card.id, IDK)}
                    data-testid="idk-btn">I don’t know</button>
          )}
          <button className={styles.navBtnPrimary} onClick={next} disabled={!isLocked}
                  data-testid="next-btn">
            {idx === cards.length - 1 ? 'Finish' : 'Next →'}
          </button>
        </div>
      </div>
    );
  }

  // ── Result phase ──────────────────────────────────────────────────────────

  const deferred = Boolean(completion?.deferred);   // offline: server grades on sync
  const earned = cards.reduce((sum, c) => sum + (verdicts[c.id]?.pointsEarned ?? 0), 0);
  const max    = cards.reduce((sum, c) => sum + c.difficulty, 0);
  const noteResult = completion?.notes?.find(n => n.notePath === notePath) ?? completion?.notes?.[0];

  return (
    <div className={styles.session} data-testid="flashcard-result">
      <div className={styles.resultHeader}>
        <h2 className={styles.resultTitle}>Session complete</h2>
        {!deferred && (
          <div className={styles.scoreBlock}>
            <span className={styles.scoreBig}>{earned}</span>
            <span className={styles.scoreOf}>/ {max} pts</span>
          </div>
        )}
      </div>

      {deferred && (
        <p className={styles.lockedNote} data-testid="deferred-result">
          Answers recorded ✓ — they’ll be graded and this note rescheduled when you sync.
        </p>
      )}

      {noteResult && !deferred && (
        <p className={styles.lockedNote} data-testid="band-result">
          Graded <strong>{noteResult.band.replace('_', ' ')}</strong> — next review{' '}
          {new Date(noteResult.due).toLocaleDateString()}
        </p>
      )}

      <div className={styles.breakdown}>
        {cards.map(c => {
          const v = verdicts[c.id];
          // Deferred (offline): neutral mark, no correctness/answer reveal — the server
          // hasn't graded yet. Online: the usual correct/partial/wrong breakdown.
          const cls = deferred ? styles.resultPending
                    : v?.verdict === 'CORRECT' ? styles.resultCorrect
                    : v?.verdict === 'PARTIAL' ? styles.resultPending
                    : styles.resultWrong;
          return (
            <div key={c.id} className={`${styles.resultCard} ${cls}`}
                 data-testid={`result-card-${c.id}`}>
              <span className={styles.resultMark}>
                {deferred ? '◐'
                  : v?.verdict === 'CORRECT' ? '✓' : v?.verdict === 'PARTIAL' ? '◐' : '✗'}
              </span>
              <div className={styles.resultCardBody}>
                <p className={styles.resultQuestion}>{questionOf(c, variants)}</p>
                <p className={styles.resultAnswer}>
                  Your answer: {answerDisplayOf(c, answers[c.id])}
                  {!deferred && <>{' '}· {v?.pointsEarned ?? 0}/{c.difficulty} pts</>}
                </p>
                {!deferred && v?.verdict !== 'CORRECT' && correctAnswerOf(c, variants) != null && (
                  <p className={styles.resultCorrectAnswer}>
                    Correct answer: {String(correctAnswerOf(c, variants))}
                  </p>
                )}
                {!deferred && c.payload.explanation && (
                  <p className={styles.resultExplanation}>Why: {c.payload.explanation}</p>
                )}
                {!deferred && v?.feedback && (
                  <p className={styles.resultFeedback}>{v.feedback}</p>
                )}
                {/* Flag a bad card: quarantines it now; the nightly regen replaces it
                    1-for-1, using the reason to steer the agent away from the flaw. */}
                <FlagControl
                  flagged={Boolean(flagged[c.id])}
                  onFlag={async (reason) => {
                    try {
                      await flagCard(c.id, reason);
                      setFlagged(p => ({ ...p, [c.id]: true }));
                    } catch (e) {
                      showToast(`Flag failed: ${e.message ?? e}`);
                    }
                  }}
                />
              </div>
            </div>
          );
        })}
      </div>

      <div className={styles.resultFooter}>
        <button className={styles.reviewNoteBtn} onClick={() => onReviewNote(notePath)}>
          Review note directly →
        </button>
        <button className={styles.navBtn} onClick={onClose}>Close</button>
      </div>
    </div>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

// Per-card flag control in the result breakdown. Collapsed: a small "Flag this card"
// link. Expanded: an optional reason box + confirm. After flagging: a done note.
function FlagControl({ flagged, onFlag }) {
  const [open, setOpen]     = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy]     = useState(false);

  if (flagged) {
    return <p className={styles.flagDone} data-testid="flag-done">
      🚩 Flagged — a replacement is generated tonight.
    </p>;
  }
  if (!open) {
    return <button className={styles.flagLink} data-testid="flag-btn"
                   onClick={() => setOpen(true)}>🚩 Flag this card</button>;
  }
  return (
    <div className={styles.flagBox} data-testid="flag-box">
      <textarea className={styles.flagReason} rows={2} value={reason}
                placeholder="Why is this card bad? (optional — helps the AI fix it)"
                onChange={e => setReason(e.target.value)} data-testid="flag-reason" />
      <div className={styles.flagActions}>
        <button className={styles.flagCancel} onClick={() => { setOpen(false); setReason(''); }}
                disabled={busy}>Cancel</button>
        <button className={styles.flagConfirm} data-testid="flag-confirm" disabled={busy}
                onClick={async () => { setBusy(true); await onFlag(reason.trim()); setBusy(false); }}>
          {busy ? 'Flagging…' : 'Flag card'}
        </button>
      </div>
    </div>
  );
}

function McqOptions({ card, answer, locked, onSelect, onSubmit }) {
  // Selecting an option only HIGHLIGHTS it — the choice isn't submitted (and thus
  // isn't locked) until "Submit answer", so the student can freely change picks.
  // Exam style: never reveal which is correct here — that's in the end-of-test breakdown.
  const hasChoice = answer != null && answer !== '';
  return (
    <div className={styles.openBlock}>
      <div className={styles.options} data-testid="mcq-options">
        {card.payload.options.map((opt, i) => {
          const strI   = String(i);
          const chosen = answer === strI;
          return (
            <button key={i} disabled={locked} onClick={() => onSelect(strI)}
                    className={`${styles.option} ${chosen ? styles.optionChosen : ''}`}
                    data-testid={`option-${i}`}>
              <span className={styles.optionLetter}>{String.fromCharCode(65 + i)}</span>
              {opt}
            </button>
          );
        })}
      </div>
      {!locked && (
        <button className={styles.navBtnPrimary} onClick={onSubmit} disabled={!hasChoice}
                data-testid="mcq-submit">
          Submit answer
        </button>
      )}
    </div>
  );
}

function OpenEndedInput({ answer, locked, onChange, onSubmit }) {
  return (
    <div className={styles.openBlock}>
      <textarea className={styles.openTextarea} placeholder="Type your answer…"
                value={answer} readOnly={locked} rows={5}
                onChange={e => onChange(e.target.value)} data-testid="open-textarea" />
      {!locked && (
        <button className={styles.navBtnPrimary} onClick={onSubmit} disabled={!answer.trim()}>
          Submit answer
        </button>
      )}
    </div>
  );
}

function ExerciseInput({ answer, locked, onChange, onSubmit }) {
  return (
    <div className={styles.openBlock}>
      <input className={styles.openTextarea} placeholder="Your answer…"
             value={answer} readOnly={locked}
             onChange={e => onChange(e.target.value)}
             onKeyDown={e => { if (e.key === 'Enter' && answer.trim()) onSubmit(); }}
             data-testid="exercise-input" />
      {!locked && (
        <button className={styles.navBtnPrimary} onClick={onSubmit} disabled={!answer.trim()}>
          Submit answer
        </button>
      )}
    </div>
  );
}
