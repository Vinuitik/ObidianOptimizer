import { useState } from 'react';
import styles from './FlashcardSession.module.css';

// Blank cards — mechanism is real, content is placeholder.
const BLANK_CARDS = [
  {
    id: 'c1',
    type: 'mcq',
    question: 'Placeholder question 1 — what is the main concept covered in this note?',
    options: ['Option A', 'Option B', 'Option C', 'Option D'],
    correct: 0,
  },
  {
    id: 'c2',
    type: 'open',
    question: 'Placeholder open-ended — summarise this note in your own words.',
  },
  {
    id: 'c3',
    type: 'mcq',
    question: 'Placeholder question 3 — which statement best describes the key idea?',
    options: ['Option A', 'Option B', 'Option C', 'Option D'],
    correct: 2,
  },
];

function scoreMcq(card, answer) {
  return answer === String(card.correct);
}

export default function FlashcardSession({ notePath, onReviewNote, onClose }) {
  const [phase,      setPhase]      = useState('quiz');   // 'quiz' | 'result'
  const [idx,        setIdx]        = useState(0);
  const [answers,    setAnswers]    = useState({});       // { [cardId]: string }
  const [locked,     setLocked]     = useState({});       // { [cardId]: true }
  const [selfMarks,  setSelfMarks]  = useState({});       // { [cardId]: 'correct'|'incorrect' }

  const cards    = BLANK_CARDS;
  const card     = cards[idx];
  const isLocked = Boolean(locked[card?.id]);

  function submitAnswer(cardId, value) {
    setAnswers(prev => ({ ...prev, [cardId]: value }));
    setLocked(prev =>  ({ ...prev, [cardId]: true }));
  }

  function next() {
    if (idx < cards.length - 1) setIdx(i => i + 1);
    else setPhase('result');
  }

  function prev() {
    if (idx > 0) setIdx(i => i - 1);
  }

  // ── Quiz phase ────────────────────────────────────────────────────────────

  if (phase === 'quiz') {
    return (
      <div className={styles.session} data-testid="flashcard-session">
        <div className={styles.progress}>
          <span className={styles.progressText}>{idx + 1} / {cards.length}</span>
          <div className={styles.progressBar}>
            <div
              className={styles.progressFill}
              style={{ width: `${((idx + 1) / cards.length) * 100}%` }}
            />
          </div>
        </div>

        <div className={styles.card} data-testid="flashcard-card">
          <span className={styles.cardType}>{card.type === 'mcq' ? 'Multiple choice' : 'Open ended'}</span>
          <p className={styles.question}>{card.question}</p>

          {card.type === 'mcq' && (
            <McqOptions
              card={card}
              answer={answers[card.id]}
              locked={isLocked}
              onSelect={val => submitAnswer(card.id, val)}
            />
          )}

          {card.type === 'open' && (
            <OpenEndedInput
              card={card}
              answer={answers[card.id] ?? ''}
              locked={isLocked}
              onChange={val => setAnswers(prev => ({ ...prev, [card.id]: val }))}
              onSubmit={() => submitAnswer(card.id, answers[card.id] ?? '')}
            />
          )}
        </div>

        <div className={styles.nav}>
          <button className={styles.navBtn} onClick={prev} disabled={idx === 0}>← Prev</button>
          <button
            className={styles.navBtnPrimary}
            onClick={next}
            disabled={!isLocked}
            data-testid="next-btn"
          >
            {idx === cards.length - 1 ? 'Finish' : 'Next →'}
          </button>
        </div>
      </div>
    );
  }

  // ── Result phase ──────────────────────────────────────────────────────────

  const mcqCards   = cards.filter(c => c.type === 'mcq');
  const autoScore  = mcqCards.filter(c => scoreMcq(c, answers[c.id])).length;
  const totalAuto  = mcqCards.length;
  const openCards  = cards.filter(c => c.type === 'open');
  const selfCorrect = openCards.filter(c => selfMarks[c.id] === 'correct').length;
  const selfTotal   = openCards.length;
  const grandTotal  = autoScore + selfCorrect;
  const grandMax    = totalAuto + selfTotal;

  return (
    <div className={styles.session} data-testid="flashcard-result">
      <div className={styles.resultHeader}>
        <h2 className={styles.resultTitle}>Session complete</h2>
        <div className={styles.scoreBlock}>
          <span className={styles.scoreBig}>{grandTotal}</span>
          <span className={styles.scoreOf}>/ {grandMax}</span>
        </div>
      </div>

      <div className={styles.breakdown}>
        {cards.map(c => {
          const isCorrect = c.type === 'mcq'
            ? scoreMcq(c, answers[c.id])
            : selfMarks[c.id] === 'correct';
          const isPending = c.type === 'open' && !selfMarks[c.id];

          return (
            <div
              key={c.id}
              className={`${styles.resultCard} ${
                isPending ? styles.resultPending :
                isCorrect ? styles.resultCorrect : styles.resultWrong
              }`}
              data-testid={`result-card-${c.id}`}
            >
              <span className={styles.resultMark}>
                {isPending ? '?' : isCorrect ? '✓' : '✗'}
              </span>
              <div className={styles.resultCardBody}>
                <p className={styles.resultQuestion}>{c.question}</p>
                {c.type === 'open' && answers[c.id] && (
                  <p className={styles.resultAnswer}>Your answer: {answers[c.id]}</p>
                )}
                {c.type === 'mcq' && (
                  <p className={styles.resultAnswer}>
                    Your answer: {c.options[Number(answers[c.id])] ?? '—'} ·
                    Correct: {c.options[c.correct]}
                  </p>
                )}
                {c.type === 'open' && !selfMarks[c.id] && (
                  <div className={styles.selfMarkRow}>
                    <span className={styles.selfMarkLabel}>Did you get it right?</span>
                    <button
                      className={styles.markCorrect}
                      onClick={() => setSelfMarks(prev => ({ ...prev, [c.id]: 'correct' }))}
                    >
                      Yes ✓
                    </button>
                    <button
                      className={styles.markWrong}
                      onClick={() => setSelfMarks(prev => ({ ...prev, [c.id]: 'incorrect' }))}
                    >
                      No ✗
                    </button>
                  </div>
                )}
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
  return (
    <div className={styles.options} data-testid="mcq-options">
      {card.options.map((opt, i) => {
        const strI    = String(i);
        const chosen  = answer === strI;
        const correct = locked && i === card.correct;
        const wrong   = locked && chosen && !correct;

        return (
          <button
            key={i}
            className={`${styles.option} ${
              correct ? styles.optionCorrect :
              wrong   ? styles.optionWrong   :
              chosen  ? styles.optionChosen  : ''
            }`}
            disabled={locked}
            onClick={() => onSelect(strI)}
            data-testid={`option-${i}`}
          >
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
      <textarea
        className={styles.openTextarea}
        placeholder="Type your answer…"
        value={answer}
        readOnly={locked}
        onChange={e => onChange(e.target.value)}
        rows={5}
        data-testid="open-textarea"
      />
      {!locked && (
        <button className={styles.navBtnPrimary} onClick={onSubmit} disabled={!answer.trim()}>
          Submit answer
        </button>
      )}
      {locked && <span className={styles.lockedNote}>Answer locked in.</span>}
    </div>
  );
}
