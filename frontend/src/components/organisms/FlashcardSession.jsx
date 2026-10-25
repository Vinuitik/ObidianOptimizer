import { useEffect, useRef, useState } from 'react';
import useStore from '../../store/useStore';
import {
  buildAssignmentOffline as buildAssignment,
  submitAttemptOffline as submitAttempt,
  completeAssignmentOffline as completeAssignment,
  flagCardOffline as flagCard,
} from '../../pwa/offlineApi';
import Button from '../atoms/Button';
import Icon from '../atoms/Icon';
import Badge from '../atoms/Badge';
import Skeleton from '../atoms/Skeleton';
import Textarea from '../atoms/Textarea';
import Input from '../atoms/Input';
import Card from '../molecules/Card';
import {
  loadFlashSession, saveFlashSession, clearFlashSession,
} from '../../store/flashSessionPersist';
import styles from './FlashcardSession.module.css';

const GRADING_STALE_MS = 60_000; // a 'grading' marker older than this = the app died mid-finish

const SESSION_POINTS = 10; // point budget per mini-test (points = card difficulty 1-5)

// question text: exercises render their frozen variant, others carry it in the payload
function questionOf(card, variants) {
  if (card.type === 'exercise') return variants?.[card.id]?.rendered ?? card.payload.template;
  return card.payload.question;
}

const LONG_PRESS_MS = 500;
const MOVE_PX = 10;

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
  const completeReview      = useStore(s => s.completeReview);
  const recordFlashcardDone = useStore(s => s.recordFlashcardDone);
  const showToast           = useStore(s => s.showToast);
  // A test in progress (or finished-but-not-closed) for this note survives unmount (tab
  // switch) and app kill — see store/flashSessionPersist.js. Read once, on mount.
  const [restored] = useState(() => {
    const r = loadFlashSession(notePath);
    return r && r.assignment && Array.isArray(r.assignment.cards) ? r : null;
  });
  const wasGrading = Boolean(restored?.grading && Date.now() - restored.grading < GRADING_STALE_MS);
  const [flagged, setFlagged]       = useState(restored?.flagged ?? {});   // { [cardId]: true } once flagged
  const [phase, setPhase]           = useState(restored ? restored.phase : 'loading'); // loading | quiz | result | error
  const [error, setError]           = useState(null);
  const [assignment, setAssignment] = useState(restored?.assignment ?? null);
  const [idx, setIdx]               = useState(restored?.idx ?? 0);
  const [answers, setAnswers]       = useState(restored?.answers ?? {});  // { [cardId]: string } — buffered client-side, unsent until Finish
  const [verdicts, setVerdicts]     = useState(restored?.verdicts ?? {}); // { [cardId]: {verdict, pointsEarned, maxPoints, feedback} } — filled at Finish
  const [completion, setCompletion] = useState(restored?.completion ?? null); // { notes: [{notePath, score, band, due}] }
  const [submitting, setSubmitting] = useState(wasGrading); // true while Finish is grading all buffered answers
  const finishing = useRef(false);

  // Persist on every state change so a remount resumes exactly here. Never let a stale
  // quiz snapshot overwrite a stored result (finish() writes that directly, even when
  // this component was unmounted mid-grading).
  useEffect(() => {
    if (!assignment || (phase !== 'quiz' && phase !== 'result')) return;
    const stored = loadFlashSession(notePath);
    if (stored?.assignmentId === assignment.id && stored.phase === 'result' && phase !== 'result') return;
    saveFlashSession({
      notePath, assignmentId: assignment.id, assignment, idx, answers, flagged, phase, verdicts, completion,
      grading: submitting ? (stored?.grading || Date.now()) : null,
    });
  }, [notePath, assignment, phase, idx, answers, flagged, verdicts, completion, submitting]);

  // Remounted while a previous mount is still grading: wait for its result instead of re-submitting.
  useEffect(() => {
    if (!wasGrading) return undefined;
    const t = setInterval(() => {
      const s = loadFlashSession(notePath);
      if (s?.phase === 'result') {
        setVerdicts(s.verdicts ?? {}); setCompletion(s.completion ?? null);
        setSubmitting(false); setPhase('result');
      } else if (!s?.grading || Date.now() - s.grading > GRADING_STALE_MS) {
        setSubmitting(false);
      } else return;
      clearInterval(t);
    }, 300);
    return () => clearInterval(t);
  }, [notePath, wasGrading]);

  useEffect(() => {
    if (restored) return undefined;
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
      <p className={styles.lockedNote} role="status">Building your test…</p>
      <Skeleton lines={4} height={18} />
      {/* Escape hatch: never trap the user on a slow/stuck build. */}
      <div className={styles.nav}>
        <Button variant="ghost" onClick={() => { clearFlashSession(notePath); onClose(); }}>Cancel</Button>
      </div>
    </div>;
  }
  if (phase === 'error') {
    return <div className={styles.session} data-testid="flashcard-error">
      <p className={styles.lockedNote}>Could not build a test for this note: {error}</p>
      <div className={styles.nav}>
        {/* No cards for this note → the inline note IS the grading surface (canGrade). */}
        <Button variant="primary" onClick={() => onReviewNote(notePath, true)}>
          Review note directly →
        </Button>
        <Button variant="ghost" onClick={() => { clearFlashSession(notePath); onClose(); }}>Close</Button>
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
    // Idempotent per assignment: a second call (double tap, remount mid-grading, or an
    // already-finished test) must not re-submit. In-memory ref + the persisted record.
    const stored = loadFlashSession(notePath);
    const sameAsg = stored?.assignmentId === assignment.id;
    if (finishing.current || (sameAsg && (stored.phase === 'result'
        || (stored.grading && Date.now() - stored.grading < GRADING_STALE_MS)))) return;
    finishing.current = true;
    saveFlashSession({ ...(sameAsg ? stored : {}), notePath, assignmentId: assignment.id, assignment,
      idx, answers, flagged, phase: 'quiz', verdicts: {}, completion: null, grading: Date.now() });
    setSubmitting(true);
    const results = {};
    await Promise.all(cards.map(async c => {
      const raw = answers[c.id];
      const sent = (raw == null || raw === IDK) ? '' : raw;
      try {
        // card + its frozen variant let submitAttemptOffline grade mcq/exercise locally
        // when offline (see localGrade.js) — ignored by the online path, which the server
        // grades as always.
        results[c.id] = await submitAttempt(assignment.id, c.id, sent, c, variants[c.id]);
      } catch (e) {
        results[c.id] = { verdict: 'WRONG', pointsEarned: 0, maxPoints: 0, feedback: 'verification failed', deferred: false };
      }
    }));
    setVerdicts(results);
    let result = null;
    try {
      // completeReview dismisses from the review list on success (including a queued-
      // offline result — the server finishes grading on sync) and toasts on a genuine
      // failure, same as the slideshow/ReviewRating paths. Without this the note used to
      // linger looking un-reviewed with no indication anything went wrong.
      result = await completeReview(notePath, () => completeAssignment(assignment.id));
      setCompletion(result);
      // Count this test against today's flashcard budget so a reload won't re-offer
      // flashcard slots past the daily cap (see reviewPlan.js / getReviewSession).
      recordFlashcardDone();
    } catch { /* toast already shown by completeReview; result phase still renders per-card verdicts */ }
    // Written directly (not via the effect) so it lands even if we were unmounted mid-grading.
    saveFlashSession({ notePath, assignmentId: assignment.id, assignment, idx, answers, flagged,
      phase: 'result', verdicts: results, completion: result, grading: null });
    finishing.current = false;
    setSubmitting(false);
    setPhase('result');
  }

  // Leaving the test for good (Close / Next note) drops the persisted session;
  // "Review note directly" keeps it so coming back still shows the result.
  function closeSession() { clearFlashSession(notePath); onClose(); }

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
            {isAnswered && <span data-testid="answered-badge" title="Answered">{' '}<Icon name="check" size={12} /></span>}
          </span>
          <div className={styles.progressBar}>
            <div className={styles.progressFill}
                 style={{ width: `${((idx + 1) / cards.length) * 100}%` }} />
          </div>
        </div>

        <Card className={styles.card} data-testid="flashcard-card">
          <span className={styles.cardType}>
            <Badge tone="neutral">{TYPE_LABELS[card.type]} · {card.difficulty} pt{card.difficulty > 1 ? 's' : ''}</Badge>
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
            key={card.id}
            flagged={Boolean(flagged[card.id])}
            onFlag={(reason) => flagThisCard(card.id, reason)}
          />
        </Card>

        <div className={styles.nav}>
          <Button variant="ghost" onClick={() => setIdx(i => Math.max(0, i - 1))}
                  disabled={idx === 0 || submitting}>← Prev</Button>
          <Button variant="secondary" onClick={() => setAnswer(card.id, IDK)}
                  disabled={submitting} data-testid="idk-btn">I don’t know</Button>
          <Button variant="primary" onClick={next} disabled={submitting}
                  data-testid="next-btn">
            {submitting ? 'Grading…' : (idx === cards.length - 1 ? 'Finish' : 'Next →')}
          </Button>
        </div>
      </div>
    );
  }

  // ── Result phase ──────────────────────────────────────────────────────────

  // Two independent "not fully known yet" flags, not one blanket one: completionDeferred
  // is about the NOTE's own FSRS/bandit reschedule (only the server does that, so it's
  // always deferred while the completeAssignment call is queued — e.g. every driveMode
  // session). cardDeferred (below, per card) is about that ONE card's verdict — mcq/
  // exercise are graded locally the instant you submit (see localGrade.js), so only
  // 'open' (free-text, needs the LLM judge) is ever actually deferred. The two are
  // independent: a driveMode session routinely has completionDeferred=true while most
  // cards already show a real ✓/✗.
  const completionDeferred = Boolean(completion?.deferred);
  // Flagged cards are excluded from both numerator and denominator server-side
  // (AssignmentService.submitAttempt never records an attempt for one) — match that here
  // so the displayed total agrees with noteResult.band, which comes from the same query.
  const scoredCards = cards.filter(c => verdicts[c.id]?.verdict !== 'FLAGGED');
  const knownCards  = scoredCards.filter(c => !verdicts[c.id]?.deferred);
  const anyCardDeferred = scoredCards.length > knownCards.length;
  const earned = knownCards.reduce((sum, c) => sum + (verdicts[c.id]?.pointsEarned ?? 0), 0);
  const max    = knownCards.reduce((sum, c) => sum + c.difficulty, 0);
  const noteResult = completion?.notes?.find(n => n.notePath === notePath) ?? completion?.notes?.[0];

  return (
    <div className={styles.session} data-testid="flashcard-result">
      <div className={styles.resultHeader}>
        <h2 className={styles.resultTitle}>Session complete</h2>
        {knownCards.length > 0 && (
          <div className={styles.scoreBlock}>
            <span className={styles.scoreBig}>{earned}</span>
            <span className={styles.scoreOf}>/ {max} pts{anyCardDeferred ? ' so far' : ''}</span>
          </div>
        )}
      </div>

      {anyCardDeferred && (
        <p className={styles.lockedNote} data-testid="deferred-result">
          {scoredCards.length - knownCards.length} card{scoredCards.length - knownCards.length === 1 ? '' : 's'}{' '}
          need{scoredCards.length - knownCards.length === 1 ? 's' : ''} the model to grade — recorded ✓, scored when you sync.
        </p>
      )}

      {completionDeferred && (
        <p className={styles.lockedNote} data-testid="reschedule-deferred-result">
          This note will be rescheduled once everything syncs.
        </p>
      )}

      {noteResult && !completionDeferred && (
        <p className={styles.lockedNote} data-testid="band-result">
          Graded <strong>{noteResult.band.replace('_', ' ')}</strong> — next review{' '}
          {new Date(noteResult.due).toLocaleDateString()}
        </p>
      )}

      <div className={styles.breakdown}>
        {cards.map(c => {
          const v = verdicts[c.id];
          const flaggedResult = v?.verdict === 'FLAGGED';
          const cardDeferred = Boolean(v?.deferred);
          // Deferred (an 'open' card still awaiting the LLM judge): neutral mark, no
          // correctness/answer reveal yet. Flagged: also neutral — excluded from grading,
          // not "wrong". Otherwise (including mcq/exercise, graded locally offline or on):
          // the usual correct/partial/wrong breakdown.
          const cls = cardDeferred || flaggedResult ? styles.resultPending
                    : v?.verdict === 'CORRECT' ? styles.resultCorrect
                    : v?.verdict === 'PARTIAL' ? styles.resultPending
                    : styles.resultWrong;
          return (
            <div key={c.id} className={`${styles.resultCard} ${cls}`}
                 data-testid={`result-card-${c.id}`}>
              <span className={styles.resultMark}>
                {cardDeferred ? '◐' : flaggedResult ? <Icon name="flag" size={16} />
                  : v?.verdict === 'CORRECT' ? '✓' : v?.verdict === 'PARTIAL' ? '◐' : '✗'}
              </span>
              <div className={styles.resultCardBody}>
                <p className={styles.resultQuestion}>{questionOf(c, variants)}</p>
                {flaggedResult ? (
                  <p className={styles.resultAnswer}>Flagged — excluded from this test's grading.</p>
                ) : (
                  <p className={styles.resultAnswer}>
                    Your answer: {answerDisplayOf(c, answers[c.id])}
                    {!cardDeferred && <>{' '}· {v?.pointsEarned ?? 0}/{c.difficulty} pts</>}
                  </p>
                )}
                {/* The answer key and explanation are static content — already downloaded
                    with the card, same online or off — so they reveal regardless of
                    cardDeferred. Only the VERDICT (was the student's specific answer judged
                    right — genuinely unknown for 'open' until the LLM judge runs) and the
                    points below stay gated on it. */}
                {!flaggedResult && (c.type === 'open' || v?.verdict !== 'CORRECT')
                  && (c.type === 'open' ? (v?.referenceAnswer ?? correctAnswerOf(c, variants)) : correctAnswerOf(c, variants)) != null && (
                  <p className={styles.resultCorrectAnswer} data-testid={`reference-${c.id}`}>
                    Correct answer:{' '}
                    {String(c.type === 'open' ? (v?.referenceAnswer ?? correctAnswerOf(c, variants)) : correctAnswerOf(c, variants))}
                  </p>
                )}
                {!flaggedResult && !cardDeferred && v?.provisional && (
                  <p className={styles.provisional} data-testid={`provisional-${c.id}`}>
                    Provisional · refining in background
                  </p>
                )}
                {!flaggedResult && c.payload.explanation && (
                  <p className={styles.resultExplanation}>Why: {c.payload.explanation}</p>
                )}
                {!cardDeferred && !flaggedResult && v?.feedback && (
                  <p className={styles.resultFeedback}>{v.feedback}</p>
                )}
                {/* Flag a bad card: quarantines it now and starts a replacement generating
                    right away, using the reason to steer the agent away from the flaw. */}
                <FlagControl
                  key={c.id}
                  flagged={Boolean(flagged[c.id])}
                  onFlag={(reason) => flagThisCard(c.id, reason)}
                />
              </div>
            </div>
          );
        })}
      </div>

      <div className={styles.resultFooter}>
        <Button variant="ghost" onClick={() => onReviewNote(notePath)}>
          Review note directly →
        </Button>
        {/* Primary + auto-focused so Enter continues to the next due note. */}
        <Button variant="primary" onClick={closeSession} autoFocus data-testid="next-note-btn">
          Next note →
        </Button>
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
      <Icon name="flag" size={12} /> Flagged — excluded from grading, replacement generating now.
    </p>;
  }
  if (!open) {
    return <Button variant="ghost" size="sm" icon="flag" className={styles.flagLink} data-testid="flag-btn"
                   onClick={() => setOpen(true)}>Flag this card</Button>;
  }
  return (
    <div className={styles.flagBox} data-testid="flag-box">
      <Textarea className={styles.flagReason} rows={2} value={reason}
                placeholder="Why is this card bad? (optional — helps the AI fix it)"
                onChange={e => setReason(e.target.value)} data-testid="flag-reason" />
      <div className={styles.flagActions}>
        <Button variant="ghost" size="sm" onClick={() => { setOpen(false); setReason(''); }}
                disabled={busy}>Cancel</Button>
        <Button variant="danger" size="sm" data-testid="flag-confirm" disabled={busy}
                onClick={async () => { setBusy(true); await onFlag(reason.trim()); setBusy(false); }}>
          {busy ? 'Flagging…' : 'Flag card'}
        </Button>
      </div>
    </div>
  );
}

function McqOptions({ card, answer, onSelect }) {
  // Picking an option just highlights it and buffers the choice — nothing is sent
  // to the server, so the student can change their pick freely, including after
  // navigating away and back. Exam style: never reveal which is correct here —
  // that's in the end-of-test breakdown.
  // Only a clean tap selects: a touch/pen press held > LONG_PRESS_MS (long-press) or one
  // that drifted > MOVE_PX (a scroll) is swallowed. Keyboard activation and mouse clicks
  // are unaffected (no touch pointerdown precedes them).
  const press = useRef({ t: 0, x: 0, y: 0, bad: false, mouse: false });
  const onDown = e => {
    press.current = { t: Date.now(), x: e.clientX, y: e.clientY, bad: false, mouse: e.pointerType === 'mouse' };
  };
  const onMove = e => {
    const p = press.current;
    if (p.t && !p.mouse && Math.hypot(e.clientX - p.x, e.clientY - p.y) > MOVE_PX) p.bad = true;
  };
  const onUp = () => {
    const p = press.current;
    if (p.t && !p.mouse && Date.now() - p.t > LONG_PRESS_MS) p.bad = true;
  };
  const onCancel = () => { if (press.current.t) press.current.bad = true; };
  const onPick = val => {
    const bad = press.current.bad;
    press.current = { t: 0, x: 0, y: 0, bad: false, mouse: false };
    if (!bad) onSelect(val);
  };
  return (
    <div className={styles.openBlock}>
      <div className={styles.options} data-testid="mcq-options"
           onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onCancel}>
        {card.payload.options.map((opt, i) => {
          const strI   = String(i);
          const chosen = answer === strI;
          return (
            <button key={i} onClick={() => onPick(strI)}
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
      <Textarea className={styles.openTextarea} placeholder="Type your answer…"
                value={answer} rows={5}
                onChange={e => onChange(e.target.value)} data-testid="open-textarea" />
    </div>
  );
}

function ExerciseInput({ answer, onChange }) {
  return (
    <div className={styles.openBlock}>
      <Input className={styles.openTextarea} placeholder="Your answer…"
             value={answer}
             onChange={e => onChange(e.target.value)}
             data-testid="exercise-input" />
    </div>
  );
}
