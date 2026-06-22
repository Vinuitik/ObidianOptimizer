# ObsidianOptimizer — Full Architecture Knowledge Base

> Reconstructed from every `FLOWS.md` + key source files. Purpose: a single dump you can
> hand to Claude for diagramming + QA. Excruciating detail, organized from domain → services
> → orchestration → cross-cutting concerns → failure modes.

---

## 0. What this app is, in one breath

A **self-hosted, single-user "second brain"** built around an Obsidian markdown vault. It:
1. Lets you **browse/edit notes** (React SPA, Milkdown WYSIWYG, wiki-links, paste-upload).
2. Runs a **spaced-repetition review system** (FSRS-6 + a Thompson-sampling bandit) over notes and auto-generated flashcards.
3. **Embeds notes + images + A/V/PDF resources** into pgvector for **hybrid semantic search** and an **MCP server** Claude can query.
4. Maintains the vault automatically via **nightly chrono jobs** (file mover, frontmatter fixer, bankruptcy, spread).
5. **Syncs the whole vault to Google Drive**, per-file AES-256-GCM encrypted.
6. Routes **all LLM calls through one host-side gateway** (free providers first, Claude credits last).
7. Has a **sibling app, VideoManager**, an agentic yt-dlp downloader that the ingest pipeline calls for YouTube subtitles.

The unifying design philosophy: **hash-diff workers, not call-site hooks.** Several subsystems
(note embedding, flashcard generation, chrono sync) discover their own work by comparing a
content hash against a "last processed" hash in Postgres. This means *every* edit path —
app edits, sync downloads, chrono rewrites, raw external Obsidian edits — is covered with zero
wiring at the write site.

---

## 1. Process / service topology

Started by `start.ps1` (Windows host). It generates self-signed certs, conditionally enables the
Cloudflare tunnel, launches the **host-wrapper outside Docker**, then `docker compose up --build`.

```
                         ┌────────────────────────── Windows host ──────────────────────────┐
                         │                                                                   │
   browser ─https:8443─► │  nginx (frontend container)  ──/api/*──►  backend (Spring Boot)   │
                         │   :443 TLS edge, :80→redirect            :8084 (loopback only)    │
                         │   :8081 tunnel ingress (docker-net only)        │                 │
                         │        │  serves React SPA                      │                 │
                         │        │                                        ▼                 │
                         │        │                              postgres (paradedb)         │
                         │        │                               :5435 (loopback)           │
                         │        │                            pgvector + pg_search/BM25      │
                         │        │                                        ▲                 │
                         │        │                                        │                 │
                         │   embedder (FastAPI + ONNX, GPU)  ──────────────┘                 │
                         │    :8000 (loopback)  /embed /health /mcp /ingest /flashcards/*     │
                         │        │  reads /vault:ro                                          │
                         │        │                                                           │
                         │        └──host.docker.internal:5001──►  host-wrapper (Flask)       │
                         │                                          NOT in docker, :5001      │
                         │                                          THE LLM gateway           │
                         │                                              │                     │
                         └──────────────────────────────────────────────┼─────────────────────┘
                                                                         ▼
                                       gemini · github · mistral · groq · deepseek · anthropic · claude-cli

   (opt-in)  cloudflared  ──tunnel──► frontend:8081     [docker compose --profile tunnel]
```

### Container/process inventory

| Service | Where | Port (host) | Built from | Role |
|---|---|---|---|---|
| **frontend** | docker | 8443 https, 8083 http→https | `frontend/` (Node build → nginx alpine) | TLS edge, SPA host, `/api/*` reverse proxy, security headers, rate limits |
| **backend** | docker | 127.0.0.1:8084 | `obsidian_optimizer/obsidian/` (Spring Boot WAR) | The brain: notes CRUD, review, cards, chrono, sync, stats, internal agent API |
| **postgres** | docker | 127.0.0.1:5435 | `db/` (paradedb image) | pgvector cosine `<=>` + pg_search/Tantivy BM25; all app state |
| **embedder** | docker (GPU) | 127.0.0.1:8000 | `embedder/` | ONNX text embeddings, MCP server, ingest agent, flashcard agent |
| **host-wrapper** | host (venv) | 5001 | `host-wrapper/` | Single LLM gateway: vision + text routing, failover, rate-limit sharding |
| **cloudflared** | docker (opt-in) | — | official image | Public ingress via Cloudflare Zero Trust → `frontend:8081` |

**VideoManager** is a *separate* compose stack (its own backend/nginx/ollama/chromadb/prometheus/grafana).
ObsidianOptimizer's embedder calls it only for `POST /api/v1/subs` (YouTube captions fast-path) when `VIDEOMANAGER_URL` is set.

### Why host-wrapper lives outside Docker
It needs the host `claude` CLI auth context (subscription credits, not API), and centralizing all
outbound LLM traffic in one process means provider keys, fallback, and rate limiting live in exactly
one place regardless of how many containers want a model. If it's down, image captioning + note
synthesis + flashcard generation *silently skip* ("host wrapper unreachable") — graceful degradation,
text search still works.

---

## 2. Data stores (Postgres tables)

All in the `obsidian` DB. Schema is created lazily by `@PostConstruct` repository `initSchema()`
methods (and `generate.py ensure_schema` for cards — **dual ownership, keep in sync**).

| Table | Owner | Key columns | Purpose |
|---|---|---|---|
| `app_settings` | SettingsRepository | `key PK, value` | All settings + internal state (`chronoLastRunDate`, `sync.device_id`) |
| `notes` | NoteIndexRepository | `path PK, title, sr_due, sr_interval, sr_ease, modified_at, content_hash, embedded_hash, ingest_pending` | The note index. `content_hash` drives embedding diff; `ingest_pending` is the readiness gate |
| `note_links` | NoteLinkRepository | `(source_path, target_name) PK`, idx on target | Wiki-link graph; powers backlink rewrite on rename |
| `note_chunks` | NoteChunkRepository | `(note_path, source, chunk_index)` unique, `embedding vector(1024)`, `fts_vector`, `content_hash` | Embedded chunks. `source ∈ {text, image}` have independent index ranges |
| `pending_image_jobs` | PendingImageJobRepository | `note_path, image_path, status` (PENDING/DONE/SKIPPED), `chunk_index` | Image captioning queue (multi-day, rate-limited) |
| `cards` | CardRepository | `note_path, card_hash, source_hash, payload JSONB, status` (ACTIVE/ARCHIVED) | Generated flashcards. `source_hash == notes.body_hash` |
| `card_gen_attempts` | CardRepository | `(path, body_hash)` | Retry ledger — bounds generation attempts so zero-yield notes don't loop |
| `note_reviews` | NoteReviewRepository | `note_path, stability, difficulty, due, pending_bucket, pending_arm` | FSRS state + bandit pending reward |
| `assignments` / attempts | AssignmentRepository | `scope, points, variants JSONB` | Built review sessions (flashcard test mode) |
| `sync_queue` | SyncQueueRepository | `path PK, content_hash, status, last_synced_at, drive_file_id, retry_count` | Per-file Drive upload queue |

### Two hashes you must not confuse
- **`content_hash`** = SHA-256 of the *full* note (frontmatter + body). Drives the **embedding** diff (`content_hash ≠ embedded_hash` ⇒ re-embed) and chrono's external-edit detection. Maintained by `ImageScanService.registerImages()`.
- **`body_hash`** = SHA-256 of the *frontmatter-stripped* note. Drives the **flashcard** diff (`cards.source_hash ≠ notes.body_hash`). Keyed on body so that the `sr-due` rewrite on every review and chrono's date fixes (frontmatter-only) do **not** re-trigger card generation.

---

## 3. The universal write chokepoint

Almost every interesting side effect hangs off one method. **Memorize this:**

```
ANY note write (create / update / patch / rename / chrono rewrite / sync download)
  → FileRepository.<writeMethod>()
      → Files.writeString(...)                       (disk)
      → FrontmatterParser.parse() → NoteIndexRepository.upsert()   (index row)
      → NoteLinkRepository.updateLinks()             (wiki-link graph)
      → ImageScanService.registerImages(path, content)   ◄── THE CHOKEPOINT
            ├─ recompute content_hash + body_hash → notes
            ├─ enqueue new ![[image.png]] refs into pending_image_jobs
            └─ ResourceScanService.scan(absPath, content)   (tail call)
                  ├─ set notes.ingest_pending (sync, before POSTs)
                  └─ POST embedder /ingest for un-marked ![[*.mp4|pdf|…]]  (off-thread)
      → syncQueueRepo.markPending(relPath, sha256)   (Drive queue)
```

Because every write funnels through `registerImages` + `markPending`, the four background workers
(embedding, image, cards, sync) and the ingest agent all "find out" about changes without explicit calls.

---

## 4. Backend domains (Spring Boot, `com.obsidian.obsidian.*`)

### 4.1 `notes` — file CRUD + index
Files: `NotesController, FileRepository, NoteIndexRepository, NoteLinkRepository, FrontmatterParser`

- **Startup sync** (`FileRepository.@PostConstruct init()`): `bfsDiskFiles()` → `NoteIndexRepository.syncWithDisk()`. Delta algorithm: new files INSERT, `file.lastModified() > db.modified_at` UPDATE, db-only paths DELETE. Mode `blocking` (default, app waits) or `async` (CompletableFuture, partial results during sync). Excludes `_trash/`, `resources/`.
- **Endpoints**: `GET /names` (DB only, no I/O), `GET /children?folder=` (disk listFiles), `GET /review?offset=&limit=` (sr_due ≤ today, limit+1 trick avoids COUNT), `GET /text?noteName=`, `POST /notes` (create with frontmatter skeleton `sr-due: today+3d, interval 3, ease 200, #review`), `PUT /notes` (full replace), `PATCH /notes/content` (hunk-based diff, applied back-to-front), `PATCH /notes/rename` (renames file + **rewrites `[[old]]`→`[[new]]` in every backlink source**, preserving `#anchors` and `|aliases`), `PATCH /notes/move` (atomic move), `DELETE /notes` (soft delete → `_trash/`).
- **Security boundary**: `requireInsideVault(rawPath)` (normalize, must resolve under vault root) + `requireSimpleName(name)` (single path segment, rejects `/ \ ..`). Without these, any authed request could read/write arbitrary container files.
- **inotify caveat**: Docker volume mounts on Windows don't propagate file-change events → external Obsidian edits are caught only at next startup sync **or** by the chrono hash loop.

### 4.2 `settings` — config + internal state
Files: `SettingsController, SettingsRepository`. Table `app_settings(key, value)`, seeded from env via `ON CONFLICT DO NOTHING`.
Keys: `vaultPath, resourcePath, reviewPageSize(20), startupSyncMode(blocking), maxDailyReviews(30), bankruptcyLimit(200), chronoLastRunDate, ollamaEmbedModel(display-only), flashcardsEnabled(true)`.
`PUT /settings` is partial; changing `vaultPath` triggers `FileRepository.updateVaultPath()` → **full re-index** (TRUNCATE notes + note_links → forceResync). Per-instance, no multi-node sharing.

### 4.3 `chrono` — the nightly orchestrator (CHRONO JOBS)
Files: `ChronoService, ChronoController, FileMoverService, BankruptcyService, SpreadService, FrontmatterRewriter`

**Triggers**: `@PostConstruct onStartup()` (runs if `chronoLastRunDate != today`), `@Scheduled(cron="0 0 2 * * *")` (2 AM daily), `POST /api/chrono/run` (manual). `@EnableScheduling` is on `ObsidianApplication`.

**`runAllJobs()` execution order** — `listMdPaths()` once, result passed to all:
```
1. FileMoverService.run()      — non-recursive vault-root scan; moves images→resources/images,
                                 pdf→resources/pdf, video→resources/videos (creates subdirs)
2. BankruptcyService.run(mdFiles, bankruptcyLimit, chronicNeglectDays) — two passes:
                                 Pass 1 (always): notes overdue > chronicNeglectDays → FsrsService.forget
                                 lapse individually (no threshold). Replaces the old per-review late-lapse.
                                 Pass 2 (gate): if total overdue ≥ bankruptcyLimit → lapse all remaining
                                 overdue too. Writes via FsrsStateWriter (DB + frontmatter). NO bandit
                                 reward — lapses are exogenous, not a memory signal.
3. SpreadService.run()         — group notes by day-delta; cascade overflow forward until no day
                                 exceeds maxDailyReviews; within a day, highest FSRS-difficulty stay,
                                 overflow → day+1. Works for future AND overdue (negative delta).
4. FileRepository.triggerDeltaSync()  — re-sync DB to disk after rewrites
5. Hash loop: for every file where sha256(file) ≠ notes.content_hash (chrono's own rewrites AND
   external Obsidian edits): imageScanService.registerImages() + syncQueueRepo.markPending()
   — WITHOUT this, 2 AM changes never reached Drive until restart
6. SettingsRepository.set("chronoLastRunDate", today)
```
`FrontmatterRewriter` is frontmatter-scoped: only touches lines strictly between `---…---`. Files with no frontmatter block are skipped. The old FileChecker step and the tiered interval/ease reduction are gone — lapse math is now `FsrsService.forget()`.

### 4.4 `cards` — flashcard agent + review engine (FSRS + bandit)
Files: `CardRepository, CardGenerationService, CardJobWorker, CardController, FsrsService, BanditService, ReviewService, ReviewController, NoteReviewRepository, AssignmentService/Controller/Repository`
Python side: `embedder/flashcards/generate.py, validate.py, judge.py, solver_sandbox.py`

**Generation (hash-diff worker)** — `CardJobWorker @Scheduled` (default every 30 min, 2 min after startup):
```
CardRepository.findNotesNeedingCards(batchLimit=10):
  notes WHERE sr_due IS NOT NULL                       ← only spaced-rep notes eligible
    AND no ACTIVE card with source_hash == notes.body_hash
    AND no card_gen_attempts row for (path, body_hash)  ← retry ledger
    AND ingest_pending = false                          ← readiness gate
    AND no PENDING pending_image_jobs for the note      ← images-before-cards ordering
  → per note: CardGenerationService.generateFor() then recordAttempt() — ONLY if embedder
    answered (zero-yield won't retry & burn credits; transport failure DOES retry next cycle)
  → POST embedder /flashcards/generate {note_path, source_hash}
      generate.py: PASS 1 GEN_PROMPT via host-wrapper /complete (free providers first,
                   claude-cli credits LAST) → PASS 2 blind self-check (model re-answers its
                   own MCQ/exercise without seeing answers; mismatches dropped) → validate.py
                   (schema + solver sandbox; failures re-prompted, MAX_RETRIES=2) → store
  → cards never auto-archived; old-version cards stay ACTIVE in the draw pool; removal is user-only
```
No queue table — the `notes.body_hash ↔ cards.source_hash` diff IS the work list.

**Card types** (JSONB payload): `mcq` (options + correct index), `open` (≥2 reference answers + key_points), `exercise` (template + param domains + sandboxed solver + named conditions). `code` cards `[NOT IMPLEMENTED]`.

**Review (FSRS-6 + bandit)** — `POST /api/reviews/grade {notePath, band}` (band ∈ HARD/GOOD/EASY/VERY_EASY):
```
ReviewService.grade():
  1. delayed bandit reward: previous pending (bucket, arm) gets α+1 if band ≥ GOOD else β+1
  2. pure FSRS-6 update (recall path only — no Again/lapse; VERY_EASY maps to FSRS Easy)
  3. bandit (Thompson Sampling, Beta per bucket×arm) samples arm m ∈ {0.7,0.85,1.0,1.2,1.5};
     due = now + round(fsrsInterval × m)   ← Option A: arm scales the DATE only, never stored S/D
  → upsert note_reviews
  → writeSrFrontmatter: rewrite note sr-due/sr-interval (ease untouched) + reindexAfterExternalWrite
     ← the bridge that keeps chrono/Obsidian-SR queue in sync with FSRS
```
Buckets: difficulty {<4, 4-7, >7} × stability {<7d, 7-30d, >30d} = 9 × 5 arms. `FsrsService` is pinned against py-fsrs outputs (regenerate refs via `embedder/_fsrs_reference.py` if weights change).

**Two review UI modes** (`flashcardsEnabled` setting):
- ON → `FlashcardSession.jsx`: builds a 10-point assignment, verifies each answer server-side, completes → per-note score `Σ earned / Σ difficulty` → `Band.fromScore()` (thresholds 40/70/90) → grade().
- OFF → SlideshowReview: four band buttons → grade() directly.

**Assignment scoring**: mcq=index compare; exercise=frozen expected (numeric tolerance / normalized string); open=embedder `/flashcards/judge` (cosine bands 0.70/0.85; middle band escalates to wrapper CLI judge with key_points rubric). Verdict CORRECT (full pts) / PARTIAL (half) / WRONG (0).

### 4.5 `ml` — embeddings, search, image pipeline, resource ingest trigger
Files: `SearchController, SearchService, EmbeddingService, MarkdownPreprocessor, NoteChunk(Repository), PendingImageJob(Repository), ImageScanService, ImageProcessingWorker, NoteEmbeddingWorker, ResourceScanService, SearchResult`

**Note embedding (hash-diff worker)** — `NoteEmbeddingWorker @Scheduled every 30s, batch 20`:
```
NoteIndexRepository.findNotesNeedingEmbedding(20):  content_hash ≠ embedded_hash
                                                     AND ingest_pending = false   ← readiness gate
  → EmbeddingService.indexNote(path):
      MarkdownPreprocessor.chunkNote() → List<NoteChunk>
      pass 1: keep only chunks whose SHA-256 ≠ note_chunks.content_hash (source='text')
      pass 2: embed changed set in slices of EMBED_BATCH=64 → POST embedder /embed → float[1024]
              → upsertChunk per chunk; deleteStaleChunks(path,'text',newCount)
  → markEmbedded(path, hash)  guarded UPDATE (path AND content_hash=hash) so a mid-index edit
    stays in the work list. Embedder down ⇒ stays in diff, retried next cycle.
purgeOrphanChunks() daily — drop chunks whose note_path no longer exists.
```
`MarkdownPreprocessor.chunkNote()`: strip frontmatter / HTML comments / #tags → extract image refs → split on `## / ###` → sections >1000 chars sliding-windowed (~1000/overlap ~200) → drop <50 chars.

**Hybrid search (RRF)** — `GET /search?q=&limit=10` (session auth, used by the SPA):
```
SearchController: DeferredResult(timeout=5000ms) + CompletableFuture + AtomicBoolean cancelled
  (frees the Tomcat thread; if 5s elapses, cancelled flips and SearchService skips the next step)
SearchService.search():
  embedQuery → vector match (embedding <=> ?::vector LIMIT 60)
  [checkpoint: if cancelled, return early — skip BM25]
  text match (fts_vector @@ plainto_tsquery LIMIT 60)
  RRF merge: score = 1/(60+vectorRank) + 1/(60+bm25Rank); dedupe MAX per note_path → top-limit
```

**Image pipeline** — table `pending_image_jobs`. Populated by `ImageScanService.scanAll()` (startup), `registerImages()` (every write), chrono hash loop. Drained by `ImageProcessingWorker @Scheduled every 30s`:
```
batch PENDING → group by note_path → groups run in PARALLEL (pool = image.worker.parallelism=4)
  so the wrapper shards images across providers (image A→Gemini while image B→Groq).
  Same-note images stay sequential (getNextChunkIndex collision).
per job: POST host-wrapper /process-image {image_path}
  200 → {text, provider} → chunk if >1000 → embed → upsert note_chunks → DONE
  404 (file gone) → SKIPPED        503/network (providers exhausted) → stays PENDING
SKIPPED rows get one retry/day (requeueSkipped()).
```

**Resource ingest trigger** — `ResourceScanService.scan()` (tail of `registerImages`): for each `![[*.mp4|mkv|webm|mov|avi|mp3|m4a|wav|ogg|flac|pdf]]` lacking a `<!-- ingest:… -->` marker, POST embedder `/ingest {ref, note_path}` off-thread. Also sets `notes.ingest_pending` synchronously (true while any resource embed lacks its marker) — **this is the readiness gate** read by both the embedding and card worklists, so downstream processing waits until content is finalized.

**MCP server** lives in Python (`embedder/mcp_server.py`) — see §6.

### 4.6 `media` — resource serving
Files: `MediaController`. `POST /upload` (multipart, max 100MB) → `subdirFor(ext)` routing → `resources/<subdir>/`. `GET /images/{filename}` iterates `SEARCH_SUBDIRS=[images,videos,pdf,audio,files]`, path-traversal guarded, returns inline with `mimeFor(ext)`. No dedup (same filename overwrites; frontend appends timestamp+hex).

### 4.7 `sync` — encrypted Google Drive sync
Files: `SyncController, SyncService, SyncWorker, SyncQueueRepository, VaultEncryptionService, DriveService, DeviceIdentityService`

**Queue population** — all roads call `syncQueueRepo.markPending(relPath, sha256)` (idempotent upsert): every FileRepository write, rename backlink rewrites, MediaController upload, `SyncService.initialScan()` (startup), chrono hash loop. Soft-delete/rename old path → `delete(relPath)`.

**Upload** — `SyncWorker @Scheduled(cron, default "0 0 */6 * * *")` → `uploadPending()`:
```
findByStatus(PENDING) → per entry:
  readFile → actualHash = sha256(plaintext)   ← hash of what's ACTUALLY uploaded
  VaultEncryptionService.encrypt: gzip → random 12B IV → AES-256-GCM → [IV][ciphertext+tag]
  DriveService.uploadFile(path.enc, bytes, actualHash, deviceId, existingFileId)
    ensureFolderPath (cached) → update if fileId known else create
    appProperties {vault_path, content_hash, device_id, uploaded_at}
  markDoneIfHashMatches(path, fileId, entry.contentHash)  ← conditional UPDATE; if note was
    edited mid-upload (re-marked PENDING with new hash), DONE refused → newer content next pass
```

**Download** — `POST /api/sync/download` → `downloadAll()`: list Drive files → validate vault_path (reject `../`) → if local hash matches skip → **if sync_queue row is PENDING skip (LOCAL WINS)** → decrypt (AES-GCM → gunzip) → write + reindex. Conflict rule: **pending local edits always win until uploaded; clean files are Drive-wins.** No auto-download (manual only).

**Encryption**: key = PBKDF2WithHmacSHA256(passphrase, fixed salt "ObsidianSyncSalt", 310k iter) → 256-bit AES. Fixed salt ⇒ same passphrase on any device derives same key (multi-device). Rotating `SYNC_PASSPHRASE` makes all existing Drive files unreadable until re-uploaded.

**Device identity**: first non-loopback MAC → SHA-256 → first 16 hex, stored in `app_settings.sync.device_id`.

### 4.8 `config` — auth, CORS, bootstrap
Files: `SecurityConfig, WebConfig, ObsidianApplication, ServletInitializer`
- **Auth**: Spring Security session-based single-user. Creds from `APP_AUTH_USERNAME/PASSWORD` env (BCrypt at startup). `POST /login` (form), `POST /logout`, `GET /me`. **Only `/login` + `/logout` are public; everything else needs session.** CSRF disabled (cookie-based). `server.forward-headers-strategy=framework` honors `X-Forwarded-Proto` from nginx so cookie is `Secure`. Session fixation: ID rotates on login. BCrypt cost 10.
- **Internal agent API** (`/api/internal/*`, `InternalAgentController`): service-to-service write API for the embedder's ingest agent. Auth = `X-Internal-Token` header, constant-time compare against `MCP_API_TOKEN` (empty ⇒ fail closed). Endpoints: `POST /notes` (create), `PUT /notes` (overwrite), `POST /folders`, `POST /media` (base64). Vault writes MUST go through here so index + embedding diff + sync queue stay consistent — same invariant that keeps `create_note` out of MCP.
- **Bean init order**: SettingsRepository → NoteLinkRepository → NoteIndexRepository → FileRepository.init() → ChronoService.onStartup().

### 4.9 `stats` — dashboard aggregates
Files: `StatsController`. `GET /api/stats` returns five blocks, all indexed COUNTs (polled every 3s):
`embedding` (notesTotal, notesEmbedded where embedded_hash=content_hash, chunksTotal), `images` (pending/done/skipped), `flashcards` (eligibleNotes, notesWithCards, active/archived), `resources` (proxied from embedder `GET /ingest`), `wrapper` (proxied from host-wrapper `GET /providers`). HTTP client pinned to **HTTP/1.1** (uvicorn can't do the JDK client's h2c upgrade, which corrupts POST bodies — same reason as ResourceScanService).

---

## 5. Frontend (React SPA, `frontend/src`)

**Stack**: Vite + React + react-router + Zustand + Milkdown (ProseMirror WYSIWYG) + Framer Motion.
`src/env.js` is the single source for API base (`/api`), ports, feature flags.

**Routes**: `/` MainPage (3-panel editor), `/learn` LearnPage (resource + note split), `/review` ReviewPage (flashcard test or slideshow per `flashcardsEnabled`), `/dashboard` DashboardPage (polls `/api/stats` every 3s), `/settings` SettingsPage.

**State (`store/useStore.js`, Zustand, in-memory — lost on refresh)**: note state (currentNotePath, currentNoteRaw=disk, pendingRaw=live editor, editorResetKey), tabs[] (each carries its own hunks + pendingFiles), tree, noteIndex (basename→path), reviewNotes, settings, auth, pendingFiles (paste-upload blobs), toast.
- **Tab model**: `openTab/switchTab/closeTab` snapshot dirty state as **hunks** (computed diff), re-fetch from disk on every switch (picks up external changes; stale hunks fall back to disk). `closeTab` resets `activeTabIndex:-1` before switchTab (load-bearing — avoids early-return + dirty-state bleed).
- **Save (`syncNote`)**: upload pendingFiles → rename if title changed → `computeHunks` → PATCH `/notes/content` → refresh tree/names/review.
- **`logout()`** spreads `initialDataState()` (single factory; wipe list can't drift from field list) + revokes blob URLs.

**Milkdown editor** (`organisms/MilkdownEditor.jsx`): key on `MilkdownProvider` = `{path}-{editorResetKey}` (full teardown on note change/cancel — keying inner only causes `doc` node collision). Plugin chain order matters: `commonmark+gfm` (both required or "missing doc node") → history → listener → prism → **mathPlugin → obsidianImagePlugin → wikiLinkPlugin → hashtagPlugin** (math + `![[]]` consume before `[[` scanner; hashtag after so `[[#heading]]` already consumed) → livePreviewPlugin. Custom plugins in `utils/` (`wikiLinkPlugin, obsidianImagePlugin, hashtagPlugin, mathPlugin, livePreviewPlugin`). Paste/drag/file-picker all call `insertFiles()` → blob registry + store. `[[` autocomplete via `useSearch` (500ms debounce, AbortController).

**FolderTree**: lazy per-folder load, drag-drop move (namespaced `application/obsidian-note`), local substring filter + semantic search dropdown.

**Frontmatter** is never passed to Milkdown — split out (`frontmatter.js`), displayed read-only in `FrontmatterTable`, re-joined on every keystroke.

---

## 6. The embedder service (`embedder/`, FastAPI + ONNX, GPU)

One container, several hats. `main.py` mounts everything.

- **Text embeddings** (`model_runtime.py`): pure `onnxruntime.InferenceSession` (no torch/optimum) running pre-exported `mxbai-embed-large-v1` ONNX. `GET /health` → device GPU/CPU; `POST /embed {texts}` → `[[float×1024]]` (mean pool + L2 norm). 1024-dim in both GPU and CPU paths (no schema migration if GPU absent).
- **MCP server** (`mcp_server.py`): real Model Context Protocol over streamable HTTP at `:8000/mcp` (FastMCP, stateless). Auth `X-API-Key` constant-time vs `MCP_API_TOKEN`; DNS-rebinding protection (localhost/127.0.0.1 Host only). Tools: `search_notes(query, limit)` (hybrid RRF, direct Postgres), `get_note_content(note_path)` (read from `/vault:ro`, path-validated), `find_home_for_note(proposed_title)` (embed → pgvector similarity → folder suggestion). **No write tools** — writes go through the Java internal API. Add to Claude Code: `claude mcp add --transport http obsidian http://localhost:8000/mcp --header "X-API-Key: <token>"`.
- **Ingest agent** (`embedder/ingest/`): resource → notes. Two modes share one extract+synthesize core:
  - **in-place** (common): note has `![[lecture.mp4]]` → synthesize a block injected **directly below the embed in the same file**, wrapped in `<!-- ingest:<base> sha=… -->…<!-- /ingest -->` (HTML comment = chunker-stripped). Fired by Java `ResourceScanService`.
  - **standalone**: bare `{ref}` (URL) → create new note(s) via `find_home`.
  - `POST /ingest {ref, note_path?}` → job id; single worker thread (MAX 1 concurrent — one model in VRAM). `GET /ingest/{id}` status; `GET /ingest` all jobs (dashboard reads this). Jobs are in-memory (restart loses status; bundle files survive).
  - Extractors (zero LLM): `extract_av` (ffmpeg 16kHz wav → faster-whisper int8; YouTube → VideoManager `/subs` VTT), `extract_pdf` (PyMuPDF; <20 words/page → Tesseract OCR; figures kept via CLIP), `extract_web` (trafilatura), `keyframes` (scene-cut + CLIP keep/drop/dedupe). CLIP runs through `clip_onnx.py` (pure onnxruntime ViT-L/14).
  - `synthesize.py` — the ONLY LLM in the ingest pipeline; all `/complete` calls go through host-wrapper.
  - `publish.py` — write-through: all vault writes via Java `/api/internal/*` with `X-Internal-Token`.
- **Flashcard agent** (`embedder/flashcards/`): see §4.4. `/flashcards/generate`, `/roll`, `/judge`.

**Dockerfile gotcha**: faster-whisper pulls CPU onnxruntime (VAD) which clobbers onnxruntime-gpu → Dockerfile force-reinstalls `onnxruntime-gpu` last; `ctranslate2<4.5`. Pure-ONNX (no torch CUDA wheel) deliberately cuts the image build from ~1h to ~10min.

---

## 7. The host-wrapper (`host-wrapper/`, Flask, host-only :5001) — THE LLM GATEWAY

Single place the whole app talks to any LLM. `main.py` loads root `.env` then optional local `.env` (override).

**Router** (`llm_router.py`), one `Router` instance, free tiers first, Claude dead last:
```
Vision chain (LLM_VISION_PRIORITY): gemini → github → mistral → groq → anthropic
Text chain   (LLM_TEXT_PRIORITY):   groq → github → mistral → deepseek → gemini → claude-cli
```
- Gemini is LATE in text chain — its 1500/day quota is reserved for the image backlog.
- **Sharding not racing**: each provider `in_flight ≤ 1`, so concurrent requests lease *different* providers (this is what makes the image worker's parallelism translate to throughput). Each provider is rate-spaced (`min_interval ≈ 60/RPM`).
- **Failover**: `_acquire()` leases highest-priority free provider (blocks ≤ `LLM_ACQUIRE_DEADLINE_S`=150s); on 429 the provider is benched (Retry-After else 30s·2^failures cap 1h) and retries next provider; all failed ⇒ `RouterError` ⇒ HTTP 503 (Java marks job SKIPPED / retries).
- Providers without a key are skipped silently; `claude-cli` needs no key (subscription credits via headless `claude -p`, **NOT API credits**).

| Provider | Key | Vision | Free limits |
|---|---|---|---|
| gemini | GEMINI_API_KEY | ✓ | 15 RPM, 1500/day |
| github | GITHUB_MODELS_TOKEN | ✓ | ~15 RPM + daily cap |
| mistral | MISTRAL_API_KEY | ✓ | ~1 req/s |
| groq | GROQ_API_KEY | ✓ | ~30 RPM (vision hard cap 5 images, 429 above 2) |
| deepseek | DEEPSEEK_API_KEY | ✗ | paid pennies |
| anthropic | ANTHROPIC_API_KEY | ✓ | paid API |
| claude-cli | (CLI auth) | ✗ | subscription credits |

All except anthropic/claude-cli speak OpenAI chat-completions (Gemini via `/v1beta/openai/`).
**Endpoints**: `GET /health`, `GET /providers` (dashboard introspection), `POST /process-image {image_path}` (vision chain; `/vault` → host path), `POST /complete {prompt, system?, model?}` (text chain). In-memory router state resets on restart.

---

## 8. VideoManager (sibling app, separate stack)

Personal yt-dlp frontend with an agentic fallback. Only touchpoint with ObsidianOptimizer:
the embedder ingest agent calls `POST /api/v1/subs {url, lang}` for YouTube captions (a fast-path
that skips whisper transcription).

Internally: `POST /api/v1/download` → yt-dlp in threadpool → on failure, `AgentLoop` (ReAct: think
via Ollama qwen2.5:3b → act via tools → observe). Tools: `query_rag` (ChromaDB + nomic-embed-text),
`inspect_page_network` (Playwright), `authenticate_headless` (Playwright + Fernet credential store),
`try_ytdlp`, `write_case` (records success/failure CoT back to RAG). Also exposes `/mcp` (FastMCP)
for Claude Code. Stack: backend (FastAPI) + nginx + ollama + chromadb + prometheus + grafana.

---

## 9. Scheduled jobs — the full chrono picture

| Job | Where | Schedule | What |
|---|---|---|---|
| Chrono maintenance | `ChronoService` | 2 AM daily + startup-if-not-today + manual | mover, frontmatter fix, bankruptcy, spread, hash-resync |
| Note embedding | `NoteEmbeddingWorker` | every 30s, batch 20 | content_hash≠embedded_hash diff |
| Orphan chunk purge | `NoteEmbeddingWorker` | daily | drop chunks for deleted notes |
| Image captioning | `ImageProcessingWorker` | every 30s, parallel 4 | drain pending_image_jobs |
| Skipped-image requeue | `ImageProcessingWorker` | daily | retry SKIPPED once/day |
| Flashcard generation | `CardJobWorker` | every 30 min, +2 min startup | body_hash≠source_hash diff |
| Drive upload | `SyncWorker` | cron `0 0 */6 * * *` | drain sync_queue |
| Ingest agent | embedder single worker | event-driven (off-thread POST) | resource → in-place note |

**Readiness-gate ordering** that ties them together for a single new note with an A/V embed:
`note write → ingest_pending=true` blocks embedding+cards → ingest agent transcribes + injects text + writes marker → next write clears `ingest_pending` → image worker captions injected keyframes → only once no PENDING images remain do cards generate → embedding indexes the finalized text. This avoids embedding stale content and double-spending LLM credits on cards.

---

## 10. Cross-cutting invariants & "gotchas" (likely sources of your bugs)

1. **HTTP/1.1 pin**: any Java→Python POST must use HTTP/1.1; the JDK client's default h2c upgrade corrupts uvicorn POST bodies. (StatsController, ResourceScanService, EmbeddingService.) If you see garbled/empty embedder requests, check this.
2. **Two hashes**: confusing `content_hash` (embedding) vs `body_hash` (cards) breaks the diff workers silently — they just stop finding work or loop forever.
3. **Readiness gate (`ingest_pending`)**: if it gets stuck `true` (ingest agent never writes its marker — embedder down, VideoManager unreachable, whisper crash), the note is **invisible to both embedding and card workers forever**. Check `notes.ingest_pending` when "a note won't embed."
4. **inotify dead on Windows volumes**: external Obsidian edits only get picked up at startup sync or the 2 AM chrono hash loop — not live.
5. **In-memory everywhere**: Zustand store (refresh wipes tabs/edits), embedder ingest job status, host-wrapper router cooldowns/counters, DriveService folder cache. All reset on respective restarts.
6. **Schema dual-ownership**: `cards` DDL exists in both `CardRepository.initSchema` (Java) and `generate.py ensure_schema` (Python). Alter both.
7. **Drive has no delete**: renamed/soft-deleted files leave orphan `.enc` on Drive (harmless, wastes space).
8. **Fixed PBKDF2 salt**: fine for single-user; would need random salt if multi-tenant.
9. **start_period 600s on embedder**: first run downloads ~1.3GB ONNX weights; the backend's `service_healthy` wait won't abort mid-download. A "backend won't start" right after a clean build is usually just this.
10. **MCP session manager**: `mcp.session_manager.run()` must be in the FastAPI lifespan or `/mcp` 500s; can only start once per process (matters for tests).
11. **claude-cli credits**: the text chain's last resort bills your Claude *subscription* credits, not API. Bulk card generation that falls all the way through the chain will eat them.
12. **CardJobWorker requires `sr_due`**: a vault note without spaced-rep frontmatter is invisible to card generation (and to the review queue).

---

## 11. Environment variables (the full surface)

**Root `.env` (compose + host-wrapper share it):**
`HOST_VAULT_PATH` (required), `POSTGRES_PASSWORD`, `EMBED_MODEL`, `MCP_API_TOKEN` (required; also the internal-agent + service-to-service secret), `MCP_ALLOWED_HOSTS`, `WRAPPER_URL`, `SYNTH_MODEL` (haiku), `WHISPER_MODEL` (distil-large-v3), `VIDEOMANAGER_URL`, `BACKEND_URL`, `APP_AUTH_USERNAME/PASSWORD` (password required), `CARDS_ENABLED`, `IMAGE_WORKER_PARALLELISM` (≈ #vision providers), `IMAGE_BATCH_SIZE`, `SYNC_PASSPHRASE`, `GOOGLE_DRIVE_FOLDER_ID`, `GOOGLE_SERVICE_ACCOUNT_JSON`, `SYNC_UPLOAD_CRON`, `CLOUDFLARE_TUNNEL_TOKEN`.
**LLM provider keys**: `GEMINI_API_KEY, GITHUB_MODELS_TOKEN, MISTRAL_API_KEY, GROQ_API_KEY, DEEPSEEK_API_KEY, ANTHROPIC_API_KEY` + chains `LLM_VISION_PRIORITY / LLM_TEXT_PRIORITY` + per-model overrides + `LLM_ACQUIRE_DEADLINE_S, LLM_REQUEST_TIMEOUT_S, CLI_TIMEOUT_S`.

---

## 12. Quick "where do I change X" cheat-sheet

| Want to change | Go to |
|---|---|
| Chrono schedule | `ChronoService @Scheduled` |
| Bankruptcy/spread tuning | `BankruptcyService` constants / `SpreadService` + settings |
| Embedding model | `EMBED_MODEL` env → rebuild embedder |
| Chunk size/overlap | `MarkdownPreprocessor` constants |
| RRF weights / result limit | `SearchService` |
| Image prompt | `host-wrapper/main.py IMAGE_PROMPT` |
| Provider order/keys | root `.env` `LLM_*_PRIORITY` + keys |
| Card mix / prompts | `embedder/flashcards/generate.py N_MCQ/N_OPEN/N_EX, GEN_PROMPT` |
| FSRS weights | `FsrsService.W` (regen test refs) |
| Bandit arms / buckets | `BanditService.ARMS / bucket()` |
| Sync schedule / passphrase | `SYNC_UPLOAD_CRON` / `SYNC_PASSPHRASE` |
| Protected vs public endpoints | `SecurityConfig.filterChain()` |
| Add MCP tool | `embedder/mcp_server.py @mcp.tool()` |
| Dashboard counters | `stats/StatsController.java` |

---

*End of knowledge base. Each subsystem also has a co-located `FLOWS.md` with a per-method
Change Index if you need finer granularity than this document.*
