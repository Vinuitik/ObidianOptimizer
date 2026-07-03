# Known issue: "Could not build a test for this note: HTTP 400"

Status: **ANALYZED, not fixed** — parked to finish the ingestion-extension work first.
Date: 2026-07-03.

## Symptom
Opening a due note in flashcards mode shows **"Could not build a test for this note: HTTP 400"**.
Broader complaint: notes that are due for review have **no flashcards**.

## What the 400 literally is
Frontend `buildAssignment(scope)` → `POST /assignments` → `AssignmentService.build()`.
`AssignmentService.java:80` throws `IllegalArgumentException("no active cards in scope: …")`
when `repo.notesInScope(scope)` is empty → controller maps it to **HTTP 400**.
→ **The note has zero flashcards, and on-demand generation declined to make any.**

## Root cause (the gate)
`build()` first calls `reviewPrep.prepare(scope)` (on-demand "bring this note up to date"),
which tries to generate cards NOW. But `ReviewPreparationService.prepare()` (line ~72) only
generates when `CardRepository.isReadyForCards(notePath)` is true, defined as:

```sql
n.ingest_pending = false
AND NOT EXISTS (SELECT 1 FROM pending_image_jobs j
                WHERE j.note_path = n.path AND j.status = 'PENDING')
```

The background worker's worklist (`findNotesNeedingCards`) has the same `ingest_pending = false`
gate. **So cards are deliberately NOT generated while a note is still preprocessing.** This is
intentional (comment in ReviewPreparationService): image text lives in `note_chunks`, not the
body, so a card generated before images are transcribed would be image-blind and never
regenerate.

## Ranked causes for a due note having no cards
1. **Pending image transcription (most likely for freshly-ingested notes).** v2 standalone
   notes carry keyframe/figure images (`![[frame.jpg]]`) → `pending_image_jobs` rows →
   `isReadyForCards=false` until `ImageProcessingWorker` captions them (batches every 30s,
   rate-limited by the vision provider). No cards until then.
2. **Pending ingest (`ingest_pending=true`).** In-place notes with a raw `![[video.mp4]]`/pdf
   embed still being ingested. (Standalone v2 notes demote A/V/PDF embeds → usually not this.)
3. **Generation failing / lagging.** `CardJobWorker` runs every 30 min, batch-limited, needs the
   host-wrapper LLM. Wrapper/embedder down or out of credits → `generateFor` returns null → no
   cards (transport failures are NOT ledgered, so they retry). A genuine **zero-yield** result
   (too-short/thin note) IS ledgered via `recordAttempt` → won't retry until body_hash changes.
4. **`flashcardsEnabled` toggled off** during the mutual-exclusion testing → `CardJobWorker`
   early-returns (added 2026-07-03, `b5569b7`). On-demand `prepare()` still runs but only if
   #1/#2 are clear. (On-demand prepare is NOT gated on flashcardsEnabled.)

## How to pinpoint which cause (runtime checks)
```sql
-- readiness gate?
SELECT path, ingest_pending FROM notes WHERE path LIKE '%<note>%';
SELECT * FROM pending_image_jobs WHERE note_path = '<full/path.md>' AND status = 'PENDING';
-- any cards at all?
SELECT COUNT(*) FROM cards WHERE note_path = '<full/path.md>';
```
Server logs:
- `[ReviewPrep] <note> not ready for cards (ingest/images pending) — deferring to worker` → #1/#2
- `[CardJobWorker] N note(s) need cards` present but no cards land → #3 (check host-wrapper
  `:5001/health` + LLM credits)

## Likely verdict
**#1** — due notes whose keyframe images haven't been captioned yet, so card generation is
correctly deferred. Not a regression; it's the ingest → images → cards ordering.

## Options when we come back
- **UX (small, safe):** `FlashcardSession` should surface the readiness state instead of a bare
  `HTTP 400`, e.g. *"Cards are still being prepared (images transcribing…)"*. Add a
  `GET /cards/readiness?notePath=` (wrapping `isReadyForCards`) or return a structured 409 from
  `build()` distinguishing "not ready" from "genuinely no cards".
- **Behavior:** decide whether a note with NO images should JIT-generate cards immediately on
  review even if `pending_image_jobs` is momentarily non-empty for unrelated reasons.
- **Ops:** verify the host-wrapper is up and has credits; check the zero-yield ledger isn't
  suppressing legitimately-generatable notes.

Related: `cards/FLOWS.md`, `ReviewPreparationService.java`, `CardRepository.isReadyForCards`,
`CardJobWorker`, ingestion pipeline (`embedder/ingest/FLOWS.md`).
