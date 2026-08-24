import { useEffect, useState } from 'react';
import useStore from '../../store/useStore';
import {
  buildAssignmentOffline as buildAssignment,
  submitAttemptOffline as submitAttempt,
  completeAssignmentOffline as completeAssignment,
  flagCardOffline as flagCard,
} from '../../pwa/offlineApi';
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
  const dismissFromReview   = useStore(s => s.dismissFromReview);
  const recordFlashcardDone = useStore(s => s.recordFlashcardDone);
  const showToast           = useStore(s => s.showToast);
  const [flagged, setFlagged]       = useState({});        // { [cardId]: true } once flagged
  const [phase, setPhase]           = useState('loading'); // loading | quiz | result | error
  const [error, setError]           = useState(null);
  const [assignment, setAssignment] = useState(null);
  const [idx, setIdx]               = useState(0);
  const [answers, setAnswers]       = useState({});  // { [cardId]: string } — buffered client-side, unsent until Finish
  const [verdicts, setVerdicts]     = useState({});  // { [cardId]: {verdict, pointsEarned, maxPoints, feedback} } — filled at Finish
  const [completion, setCompletion] = useState(null); // { notes: [{notePath, score, band, due}] }
  const [submitting, setSubmitting] = useState(false); // true while Finish is grading all buffered answers

  useEffect(() => {
    let cancelled = false;
    buildAssignment(notePath, SESSION_POINTS)
      .then(a => {
        if (cancelled) return;
        // A malformed/empty assignment (missing cards array) must NOT crash the render at
        // `cards[idx]` — route it to the error phase, which offers "Review note directly".
        if (!a || !Array.isArray(a.cards) || a.cards.length === 0) {
          throw new Error('No cards available for this note yet.');
        }
        setAssignment(a); setPhase('quiz');
      })
      .catch(e => { if (!cancelled) { setError(String(e.message ?? e)); setPhase('error'); } });
    return () => { cancelled = true; };
  }, [notePath]);

  if (phase === 'loading') {
    return <div className={styles.session} data-testid="flashcard-loading">
      <p className={styles.lockedNote}>Building your test…</p>
      {/* Escape hatch: never trap the user on a slow/stuck build. */}
      <div className={styles.nav}>
        <button className={styles.navBtn} onClick={onClose}>Cancel</button>
      </div>
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

  const cards      = Array.isArray(assignment.cards) ? assignment.cards : [];
  const variants   = assignment.variants ?? {};
  const card       = cards[idx];
  const isAnswered = card ? (answers[card.id] != null && answers[card.id] !== '') : false;

  function setAnswer(cardId, value) {
    setAnswers(prev => ({ ...prev, [cardId]: value }));
  }

  // Flagging is available any time — before or after answering (quiz phase and result
  // breakdown both use this). A flagged card is excluded from grading server-side
  // (AssignmentService.submitAttempt) and kicks off an immediate feedback-aware
  // replacement, not a nightly-only one.
  async function flagThisCard(cardId, reason) {
    try {
      await flagCard(cardId, reason);
      setFlagged(p => ({ ...p, [cardId]: true }));
    } catch (e) {
      showToast(`Flag failed: ${e.message ?? e}`);
    }
  }

  // Finish: send every buffered answer at once (each card's own submitAttempt,
  // same per-card verification the server has always done — nothing server-side
  // changed), then complete the assignment. Cards never touched are treated the
  // same as an explicit "I don't know" — sent as '' so they still record a WRONG
  // attempt (keeping their difficulty in the per-note score denominator) rather
  // than being silently skipped.
  async function finish() {
    setSubmitting(true);
    const results = {};
    await Promise.all(cards.map(async c => {
      const raw = answers[c.id];
      const sent = (raw == null || raw === IDK) ? '' : raw;
      try {
        results[c.id] = await submitAttempt(assignment.id, c.id, sent);
      } catch (e) {
        results[c.id] = { verdict: 'WRONG', pointsEarned: 0, maxPoints: 0, feedback: 'verification failed' };
      }
    }));
    setVerdicts(results);
    try {
      const result = await completeAssignment(assignment.id);
      setCompletion(result);
      // Grading succeeded server-side (FSRS + bandit rescheduled the note), so it's
      // no longer due — drop it from the visible review list. Slideshow mode does
      // the same on rate(). Without this the note lingers and looks un-reviewed.
      dismissFromReview(notePath);
      // Count this test against today's flashcard budget so a reload won't re-offer
      // flashcard slots past the daily cap (see reviewPlan.js / getReviewSession).
      recordFlashcardDone();
    } catch { /* result phase still renders per-card verdicts */ }
    setSubmitting(false);
    setPhase('result');
  }

  function next() {
    if (idx < cards.length - 1) { setIdx(i => i + 1); return; }
    finish();
  }

  // ── Quiz phase ────────────────────────────────────────────────────────────

  if (phase === 'quiz') {
    return (
      <div className={styles.session} data-testid="flashcard-session">
        <div className={styles.progress}>
          <span className={styles.progressText}>
            {idx + 1} / {cards.length}
            {isAnswered && <span data-testid="answered-badge" title="Answered">{' '}✓</span>}
          </span>
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
            <McqOptions card={card} answer={answers[card.id]}
                        onSelect={val => setAnswer(card.id, val)} />
          )}
          {card.type === 'open' && (
            <OpenEndedInput answer={answers[card.id] === IDK ? '' : (answers[card.id] ?? '')}
                            onChange={val => setAnswer(card.id, val)} />
          )}
          {card.type === 'exercise' && (
            <ExerciseInput answer={answers[card.id] === IDK ? '' : (answers[card.id] ?? '')}
                           onChange={val => setAnswer(card.id, val)} />
          )}

          {/* Nothing is sent to the server here — answers stay editable, and can be
              changed by re-navigating to this card, until Finish grades everything
              at once. Exam style is preserved: correctness is never revealed here,
              only in the end-of-test breakdown. */}
          {isAnswered && (
            <p className={styles.lockedNote} data-testid="answered-note">
              {answers[card.id] === IDK ? 'Marked “I don’t know”.' : 'Answered.'}
              {' '}You can change this until you press Finish.
            </p>
          )}

          {/* Flag a bad card any time — before or after answering. Excluded from
              grading, and a replacement starts generating right away. */}
          <FlagControl
            flagged={Boolean(flagged[card.id])}
            onFlag={(reason) => flagThisCard(card.id, reason)}
          />
        </div>

        <div className={styles.nav}>
          <button className={styles.navBtn} onClick={() => setIdx(i => Math.max(0, i - 1))}
                  disabled={idx === 0 || submitting}>← Prev</button>
          <button className={styles.navBtn} onClick={() => setAnswer(card.id, IDK)}
                  disabled={submitting} data-testid="idk-btn">I don’t know</button>
          <button className={styles.navBtnPrimary} onClick={next} disabled={submitting}
                  data-testid="next-btn">
            {submitting ? 'Grading…' : (idx === cards.length - 1 ? 'Finish' : 'Next →')}
          </button>
        </div>
      </div>
    );
  }

  // ── Result phase ──────────────────────────────────────────────────────────

  const deferred = Boolean(completion?.deferred);   // offline: server grades on sync
  // Flagged cards are excluded from both numerator and denominator server-side
  // (AssignmentService.submitAttempt never records an attempt for one) — match that here
  // so the displayed total agrees with noteResult.band, which comes from the same query.
  const scoredCards = cards.filter(c => verdicts[c.id]?.verdict !== 'FLAGGED');
  const earned = scoredCards.reduce((sum, c) => sum + (verdicts[c.id]?.pointsEarned ?? 0), 0);
  const max    = scoredCards.reduce((sum, c) => sum + c.difficulty, 0);
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
          const flaggedResult = v?.verdict === 'FLAGGED';
          // Deferred (offline): neutral mark, no correctness/answer reveal — the server
          // hasn't graded yet. Flagged: also neutral — excluded from grading, not "wrong".
          // Online otherwise: the usual correct/partial/wrong breakdown.
          const cls = deferred || flaggedResult ? styles.resultPending
                    : v?.verdict === 'CORRECT' ? styles.resultCorrect
                    : v?.verdict === 'PARTIAL' ? styles.resultPending
                    : styles.resultWrong;
          return (
            <div key={c.id} className={`${styles.resultCard} ${cls}`}
                 data-testid={`result-card-${c.id}`}>
              <span className={styles.resultMark}>
                {deferred ? '◐' : flaggedResult ? '🚩'
                  : v?.verdict === 'CORRECT' ? '✓' : v?.verdict === 'PARTIAL' ? '◐' : '✗'}
              </span>
              <div className={styles.resultCardBody}>
                <p className={styles.resultQuestion}>{questionOf(c, variants)}</p>
                {flaggedResult ? (
                  <p className={styles.resultAnswer}>Flagged — excluded from this test's grading.</p>
                ) : (
                  <p className={styles.resultAnswer}>
                    Your answer: {answerDisplayOf(c, answers[c.id])}
                    {!deferred && <>{' '}· {v?.pointsEarned ?? 0}/{c.difficulty} pts</>}
                  </p>
                )}
                {!deferred && !flaggedResult && v?.verdict !== 'CORRECT' && correctAnswerOf(c, variants) != null && (
                  <p className={styles.resultCorrectAnswer}>
                    Correct answer: {String(correctAnswerOf(c, variants))}
                  </p>
                )}
                {!deferred && !flaggedResult && c.payload.explanation && (
                  <p className={styles.resultExplanation}>Why: {c.payload.explanation}</p>
                )}
                {!deferred && !flaggedResult && v?.feedback && (
                  <p className={styles.resultFeedback}>{v.feedback}</p>
                )}
                {/* Flag a bad card: quarantines it now and starts a replacement generating
                    right away, using the reason to steer the agent away from the flaw. */}
                <FlagControl
                  flagged={Boolean(flagged[c.id])}
                  onFlag={(reason) => flagThisCard(c.id, reason)}
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
      🚩 Flagged — excluded from grading, replacement generating now.
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

function McqOptions({ card, answer, onSelect }) {
  // Picking an option just highlights it and buffers the choice — nothing is sent
  // to the server, so the student can change their pick freely, including after
  // navigating away and back. Exam style: never reveal which is correct here —
  // that's in the end-of-test breakdown.
  return (
    <div className={styles.openBlock}>
      <div className={styles.options} data-testid="mcq-options">
        {card.payload.options.map((opt, i) => {
          const strI   = String(i);
          const chosen = answer === strI;
          return (
            <button key={i} onClick={() => onSelect(strI)}
                    className={`${styles.option} ${chosen ? styles.optionChosen : ''}`}
                    data-testid={`option-${i}`}>
              <span className={styles.optionLetter}>{String.fromCharCode(65 + i)}</span>
              {opt}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function OpenEndedInput({ answer, onChange }) {
  return (
    <div className={styles.openBlock}>
      <textarea className={styles.openTextarea} placeholder="Type your answer…"
                value={answer} rows={5}
                onChange={e => onChange(e.target.value)} data-testid="open-textarea" />
    </div>
  );
}

function ExerciseInput({ answer, onChange }) {
  return (
    <div className={styles.openBlock}>
      <input className={styles.openTextarea} placeholder="Your answer…"
             value={answer}
             onChange={e => onChange(e.target.value)}
             data-testid="exercise-input" />
    </div>
  );
}
