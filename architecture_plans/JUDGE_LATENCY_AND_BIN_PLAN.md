# Judge latency + Unified Bin — plan (2026-10-25)

Two independent changes. Each = backend milestone → frontend milestone. A and B run in parallel.

---

## A. Open-answer grading: hard 1s wait, refine in background

**Measured now:** clear right/wrong = 0.3–0.7s (cosine only). Mid band (0.30–0.85) = **7.4s** (LLM judge via
generic text router, `SYNTH_MODEL`, 240s timeout, medium priority, groq first → falls through to slower providers).

**Target flow**
```
submit → AssignmentService.submitAttempt("open")
  → POST /flashcards/judge {llm:false}         ~0.3s  → cosine verdict + similarity
  → clear band?  → final, done
  → mid band     → fire POST /flashcards/judge {llm:true} async (CompletableFuture)
                   wait remaining budget up to JUDGE_WAIT_MS=1000 total
      → arrived  → final verdict + feedback
      → not yet  → insert attempt PARTIAL, provisional=true; return {provisional:true, referenceAnswer}
                   future completes → UPDATE attempt verdict/points/judge_used/feedback, provisional=false
complete(assignment) → wait ≤ JUDGE_COMPLETE_WAIT_MS=8000 for this assignment's pending futures
                     → grade FSRS with whatever is stored (provisional rows count as PARTIAL)
```
Scope: per open question, at the moment it's submitted — not per test. By the time the user finishes the
test most refinements have landed, so `complete()` usually waits 0s.

**Card order in a session** (gives refinements max head start + stress/relax rhythm):
alternate hard / easy / hard / easy … ("hard" = difficulty ≥3 or open; "easy" = the rest). Open cards take the
earliest hard slots so their background judges start early. If one pool runs out, append the rest by difficulty. Done in `AssignmentService`
when building the assignment; frontend renders in the given order.

Frontend: provisional result shows reference answer + embedding verdict + a small "provisional" mark. No polling
(user rarely returns); user moves straight to the next card.

**Fast judge model**
- host-wrapper `/complete` gets `tier:"fast"` → separate provider order `LLM_FAST_ORDER`
  (default: groq `llama-3.1-8b-instant`, then gemini `gemini-2.5-flash-lite`; agent verifies both
  are live via a real call before choosing defaults). Judge always sends `priority:"high"`.
- embedder judge LLM call: own timeout `JUDGE_LLM_TIMEOUT_S=15` (not the 240s synth one).

**Milestones**
- **A1 (backend, Sonnet):** host-wrapper fast tier; embedder `llm` flag + short timeout + `tier/priority`;
  `assignment_attempts.provisional` column (inline `ALTER … IF NOT EXISTS`, repo-style); Java async + budgets;
  tests via `linux_scripts/test.sh`. Re-run the 3-call timing probe → mid band must return ≤1.1s.
- A1 also: card order (hard/easy alternation, open cards in the earliest hard slots).
- **A2 (frontend, Sonnet):** provisional mark + reference answer in `FlashcardSession`; result payload
  `{verdict, provisional, referenceAnswer, feedback?}`.

**Known limits (go in FLOWS Technology Notes)**
- Pending refinements are in-memory futures → lost on backend restart; those attempts stay provisional PARTIAL.
- If `complete()` is hit before refinement lands (fast tapper), FSRS uses the provisional PARTIAL.

---

## B. Unified Bin in Settings

**What deletes today**
| Thing | Today | Restorable? |
|---|---|---|
| Notes, folders, inbox notes, source snapshots, media | moved to vault `_trash/` (flattened by filename) | no UI; **original path lost** |
| Feed items | `feed_items.status='binned'` | yes, in Sources › Bin tab |
| Tracks, track items | hard `DELETE` | no |
| Subscriptions (feed sources) | hard `DELETE` | no |
| Vaults | queued deletion job + Delete button in Vaults panel | **removed entirely** (see below) |

**Design**
- New `bin` backend package (aggregator, no new deps beyond the repos it reads):
  - Files: new `bin_entries` table `(id, vault_id, kind note|folder|file, trash_path, original_path, label, deleted_at)`
    written by `FileRepository.softDelete*`. Restore = move back to original path (suffix if taken) + re-index +
    `syncQueueRepo.markPending`. Pre-existing `_trash/` items with no entry → listed as "unknown origin", restore → `_inbox/`.
  - Tracks / track items / subscriptions: hard delete → `deleted_at` column; every repo read filters `deleted_at IS NULL`.
  - Feed: reuse `binned` status as-is.
- **Restore is always full + recursive:** folder → every note/file inside; track → its items + schedule state;
  subscription → its feed items. Deleting a parent records children under the parent's bin entry (one row in the
  Bin, restores as a unit).
- **Vault deletion removed:** delete `VaultDeletionController/Repository/Worker`, `deleteVault` API client + the
  Delete button/confirm dialog in `VaultsPanel.jsx`. Archive/unregister stays. Users delete folders on disk themselves.
- API: `GET /api/bin` (grouped by kind), `POST /api/bin/restore {kind,id}`, `DELETE /api/bin/{kind}/{id}` (forever),
  `DELETE /api/bin` (empty all).
- Retention: purge bin items older than `BIN_RETENTION_DAYS=30` nightly. Purging a file entry = real file delete
  (Drive tombstone already sent at soft-delete time).
- Frontend: Settings › **Bin** section — tabs/segments Notes · Folders · Files · Feed · Tracks · Subscriptions; each row
  Restore / Delete forever; "Empty bin". Remove the Bin tab from Sources `FeedView`. "Move to trash" copy → "Move to bin".

**Milestones**
- **B1 (backend, Sonnet):** table, soft-delete conversions, recursive restore, BinController, retention job,
  vault-deletion removal, tests.
- **B2 (frontend, Sonnet):** Settings Bin section, remove Sources bin tab, remove vault Delete UI, copy changes, tests.

---

## C. Flashcard question quality — more WHY / HOW

Today `GEN_PROMPT` (`embedder/flashcards/generate.py`) only constrains type + difficulty → model defaults to recall
("what is X?"). Change:
- Every card gets an `"angle"`: `what` (recall) | `why` (reason it's true / why it exists / why we need it) |
  `how` (perform or apply the procedure) | `where` (where/when you'd apply it, real situation).
- Target mix per note: ≤25% `what`; the rest split across why / how / where. Open cards are never `what`.
- Prompt gets 1 short example per angle and a rule: "a question answerable by quoting one sentence of the note is a
  `what` question".
- `angle` stored in card payload (no schema change); logged so we can check the actual mix.
- **C1 (Sonnet, small):** prompt + angle field + a test that a generated batch respects the mix (mocked LLM) +
  one live generation on a real note, mix printed for you to eyeball.
- Existing cards keep the old style until regenerated (see decision 1).

## Execution
A1 ∥ B1 ∥ C1 (worktrees) → A2 ∥ B2 → merge → test.sh → I report BUILT; deploy (git push) only on your OK.
FLOWS.md updated per milestone (cards/, bin/, host-wrapper).

## Decided (2026-10-25)
- Bin retention 30 days auto-purge. Restore is full + recursive. Vault deletion removed from the product.

## Open decisions
1. Existing cards: regenerate all with the new prompt (one LLM call per note, runs over night via the free providers),
   or only new/flagged cards get the new style?
