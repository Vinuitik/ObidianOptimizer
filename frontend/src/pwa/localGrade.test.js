import { describe, it, expect } from 'vitest';
import { gradeLocally } from './localGrade';

// Mirrors AssignmentService.submitAttempt (mcq index-compare, exercise numeric-tolerance /
// normalized-string-compare) — these cases are chosen to match what a Java-side unit test
// of that method would cover, so a future drift between the two shows up here first.

describe('gradeLocally — mcq', () => {
  const card = { type: 'mcq', difficulty: 2, payload: { correct: 1 } };

  it('matching index → CORRECT, full difficulty as points', () => {
    expect(gradeLocally(card, null, '1')).toEqual(
      { verdict: 'CORRECT', pointsEarned: 2, maxPoints: 2, deferred: false });
  });

  it('non-matching index → WRONG, zero points', () => {
    expect(gradeLocally(card, null, '0')).toEqual(
      { verdict: 'WRONG', pointsEarned: 0, maxPoints: 2, deferred: false });
  });

  it('missing/empty answer → WRONG, not a crash', () => {
    expect(gradeLocally(card, null, null)).toEqual(
      { verdict: 'WRONG', pointsEarned: 0, maxPoints: 2, deferred: false });
  });
});

describe('gradeLocally — exercise, numeric', () => {
  const card = { type: 'exercise', difficulty: 3,
    payload: { answer_kind: 'numeric', tolerance: 0.5 } };
  const variant = { expected: 10 };

  it('exact match → CORRECT', () => {
    expect(gradeLocally(card, variant, '10')).toMatchObject({ verdict: 'CORRECT', pointsEarned: 3 });
  });

  it('within tolerance → CORRECT', () => {
    expect(gradeLocally(card, variant, '10.4')).toMatchObject({ verdict: 'CORRECT', pointsEarned: 3 });
  });

  it('outside tolerance → WRONG', () => {
    expect(gradeLocally(card, variant, '11')).toMatchObject({ verdict: 'WRONG', pointsEarned: 0 });
  });

  it('non-numeric input → WRONG, not NaN-poisoned', () => {
    expect(gradeLocally(card, variant, 'not a number')).toMatchObject({ verdict: 'WRONG', pointsEarned: 0 });
  });

  it('no frozen variant → WRONG rather than throwing', () => {
    expect(gradeLocally(card, null, '10')).toMatchObject({ verdict: 'WRONG', pointsEarned: 0 });
  });
});

describe('gradeLocally — exercise, string', () => {
  const card = { type: 'exercise', difficulty: 1, payload: {} }; // no answer_kind → string branch
  const variant = { expected: 'Paris' };

  it('exact match → CORRECT', () => {
    expect(gradeLocally(card, variant, 'Paris')).toMatchObject({ verdict: 'CORRECT' });
  });

  it('case/whitespace-insensitive match → CORRECT', () => {
    expect(gradeLocally(card, variant, '  paris  ')).toMatchObject({ verdict: 'CORRECT' });
  });

  it('wrong text → WRONG', () => {
    expect(gradeLocally(card, variant, 'London')).toMatchObject({ verdict: 'WRONG' });
  });
});

describe('gradeLocally — open (cannot be graded locally)', () => {
  it('always RECORDED + deferred, regardless of answer', () => {
    const card = { type: 'open', difficulty: 4, payload: {} };
    expect(gradeLocally(card, null, 'anything')).toEqual(
      { verdict: 'RECORDED', pointsEarned: 0, maxPoints: 4, deferred: true });
  });
});
