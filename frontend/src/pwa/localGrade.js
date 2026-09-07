// Client-side mirror of the two DETERMINISTIC branches of AssignmentService.submitAttempt
// (mcq, exercise) — MUST stay behaviorally identical to that Java method or an offline and
// an online grade of the same answer could silently disagree. "open" (free-text) cards need
// the server's LLM judge (POST /flashcards/judge) and can never be graded here — those stay
// RECORDED/deferred until sync, exactly like every card type used to be before this existed.
const normalize = (s) => (s == null ? '' : String(s).trim().toLowerCase().replace(/\s+/g, ' '));

// Mirrors AssignmentService.verifyExercise: numeric-with-tolerance if answer_kind is
// 'numeric', else a normalized string compare against the frozen variant's expected answer.
function verifyExercise(payload, variant, answer) {
  const expected = variant?.expected;
  if (expected === undefined || expected === null) return 'WRONG'; // no frozen variant — can't verify
  if (payload?.answer_kind === 'numeric') {
    const tolerance = Number(payload?.tolerance ?? 0);
    const given = parseFloat(String(answer ?? '').trim());
    if (Number.isNaN(given)) return 'WRONG';
    return Math.abs(given - Number(expected)) <= tolerance ? 'CORRECT' : 'WRONG';
  }
  return normalize(answer) === normalize(expected) ? 'CORRECT' : 'WRONG';
}

// Mirrors AssignmentService.submitAttempt's shape ({ verdict, pointsEarned, maxPoints }),
// plus `deferred` (true only for 'open' — mcq/exercise are always known immediately, no
// LLM needed). PARTIAL never occurs for mcq/exercise server-side either — only the judge
// can return it — so it's not a case here.
export function gradeLocally(card, variant, answer) {
  const difficulty = card?.difficulty ?? 0;
  if (card?.type === 'mcq') {
    const verdict = String(card.payload?.correct) === String(answer ?? '').trim() ? 'CORRECT' : 'WRONG';
    return { verdict, pointsEarned: verdict === 'CORRECT' ? difficulty : 0, maxPoints: difficulty, deferred: false };
  }
  if (card?.type === 'exercise') {
    const verdict = verifyExercise(card.payload, variant, answer);
    return { verdict, pointsEarned: verdict === 'CORRECT' ? difficulty : 0, maxPoints: difficulty, deferred: false };
  }
  return { verdict: 'RECORDED', pointsEarned: 0, maxPoints: difficulty, deferred: true };
}
