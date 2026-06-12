import { useEffect, useState } from 'react';
import { buildAssignment, completeAssignment, submitAttempt } from '../../api/notes';
import styles from './FlashcardSession.module.css';

const SESSION_POINTS = 10; // point budget per mini-test (points = card difficulty 1-5)

// question text: exercises render their frozen variant, others carry it in the payload
function questionOf(card, variants) {
  if (card.type === 'exercise') return variants?.[card.id]?.rendered ?? card.payload.template;
  return card.payload.question;
}

const TYPE_LABELS = { mcq: 'Multiple choice', open: 'Open ended', exercise: 'Exercise' };

export default function FlashcardSession({ notePath, onReviewNote, onClose }) {
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
        <button className={styles.reviewNoteBtn} onClick={() => onReviewNote(notePath)}>
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
    try {
      const result = await submitAttempt(assignment.id, cardId, value);
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
    } catch { /* result phase still renders per-card verdicts */ }
    setPhase('result');
  }

  // ── Quiz phase ────────────────────────────────────────────────────────────

  if (phase === 'quiz') {
    const verdict = verdicts[card.id];
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
                        onSelect={val => submitAnswer(card.id, val)} />
          )}
          {card.type === 'open' && (
            <OpenEndedInput answer={answers[card.id] ?? ''} locked={isLocked}
                            onChange={val => setAnswers(prev => ({ ...prev, [card.id]: val }))}
                            onSubmit={() => submitAnswer(card.id, answers[card.id] ?? '')} />
          )}
          {card.type === 'exercise' && (
            <ExerciseInput answer={answers[card.id] ?? ''} locked={isLocked}
                           onChange={val => setAnswers(prev => ({ ...prev, [card.id]: val }))}
                           onSubmit={() => submitAnswer(card.id, answers[card.id] ?? '')} />
          )}

          {verdict && (
            <p className={styles.lockedNote} data-testid="verdict">
              {verdict.verdict === 'CORRECT' ? '✓ Correct' :
               verdict.verdict === 'PARTIAL' ? '◐ Partial credit' : '✗ Incorrect'}
              {' '}· {verdict.pointsEarned}/{verdict.maxPoints} pts
              {verdict.feedback ? ` — ${verdict.feedback}` : ''}
            </p>
          )}
        </div>

        <div className={styles.nav}>
          <button className={styles.navBtn} onClick={() => setIdx(i => Math.max(0, i - 1))}
                  disabled={idx === 0}>← Prev</button>
          <button className={styles.navBtnPrimary} onClick={next} disabled={!isLocked}
                  data-testid="next-btn">
            {idx === cards.length - 1 ? 'Finish' : 'Next →'}
          </button>
        </div>
      </div>
    );
  }

  // ── Result phase ──────────────────────────────────────────────────────────

  const earned = cards.reduce((sum, c) => sum + (verdicts[c.id]?.pointsEarned ?? 0), 0);
  const max    = cards.reduce((sum, c) => sum + c.difficulty, 0);
  const noteResult = completion?.notes?.find(n => n.notePath === notePath) ?? completion?.notes?.[0];

  return (
    <div className={styles.session} data-testid="flashcard-result">
      <div className={styles.resultHeader}>
        <h2 className={styles.resultTitle}>Session complete</h2>
        <div className={styles.scoreBlock}>
          <span className={styles.scoreBig}>{earned}</span>
          <span className={styles.scoreOf}>/ {max} pts</span>
        </div>
      </div>

      {noteResult && (
        <p className={styles.lockedNote} data-testid="band-result">
          Graded <strong>{noteResult.band.replace('_', ' ')}</strong> — next review{' '}
          {new Date(noteResult.due).toLocaleDateString()}
        </p>
      )}

      <div className={styles.breakdown}>
        {cards.map(c => {
          const v = verdicts[c.id];
          const cls = v?.verdict === 'CORRECT' ? styles.resultCorrect
                    : v?.verdict === 'PARTIAL' ? styles.resultPending
                    : styles.resultWrong;
          return (
            <div key={c.id} className={`${styles.resultCard} ${cls}`}
                 data-testid={`result-card-${c.id}`}>
              <span className={styles.resultMark}>
                {v?.verdict === 'CORRECT' ? '✓' : v?.verdict === 'PARTIAL' ? '◐' : '✗'}
              </span>
              <div className={styles.resultCardBody}>
                <p className={styles.resultQuestion}>{questionOf(c, variants)}</p>
                <p className={styles.resultAnswer}>
                  Your answer: {c.type === 'mcq'
                    ? (c.payload.options[Number(answers[c.id])] ?? '—')
                    : (answers[c.id] || '—')}
                  {' '}· {v?.pointsEarned ?? 0}/{c.difficulty} pts
                  {v?.feedback ? ` — ${v.feedback}` : ''}
                </p>
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

function McqOptions({ card, answer, locked, onSelect }) {
  const correct = card.payload.correct;
  return (
    <div className={styles.options} data-testid="mcq-options">
      {card.payload.options.map((opt, i) => {
        const strI     = String(i);
        const chosen   = answer === strI;
        const isRight  = locked && i === correct;
        const isWrong  = locked && chosen && !isRight;
        return (
          <button key={i} disabled={locked} onClick={() => onSelect(strI)}
                  className={`${styles.option} ${
                    isRight ? styles.optionCorrect :
                    isWrong ? styles.optionWrong :
                    chosen  ? styles.optionChosen : ''}`}
                  data-testid={`option-${i}`}>
            <span className={styles.optionLetter}>{String.fromCharCode(65 + i)}</span>
            {opt}
          </button>
        );
      })}
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
