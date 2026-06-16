# Graph Report - .  (2026-06-16)

## Corpus Check
- Large corpus: 267 files · ~125,354 words. Semantic extraction will be expensive (many Claude tokens). Consider running on a subfolder, or use --no-semantic to run AST-only.

## Summary
- 1931 nodes · 3780 edges · 37 communities detected
- Extraction: 62% EXTRACTED · 38% INFERRED · 0% AMBIGUOUS · INFERRED: 1451 edges (avg confidence: 0.79)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_System Architecture (cross-cutting)|System Architecture (cross-cutting)]]
- [[_COMMUNITY_Vault File Repository|Vault File Repository]]
- [[_COMMUNITY_Ingest Bundle Chunking|Ingest Bundle Chunking]]
- [[_COMMUNITY_Flashcard Generation & App Entry|Flashcard Generation & App Entry]]
- [[_COMMUNITY_Device Identity & Drive Sync|Device Identity & Drive Sync]]
- [[_COMMUNITY_Capture & Download Controller|Capture & Download Controller]]
- [[_COMMUNITY_Bankruptcy Scheduler (FSRS)|Bankruptcy Scheduler (FSRS)]]
- [[_COMMUNITY_Host-Wrapper LLM Router|Host-Wrapper LLM Router]]
- [[_COMMUNITY_Embedding Service (search)|Embedding Service (search)]]
- [[_COMMUNITY_FLOWS Docs Index|FLOWS Docs Index]]
- [[_COMMUNITY_Browser Extension Client|Browser Extension Client]]
- [[_COMMUNITY_Bandit Scheduler|Bandit Scheduler]]
- [[_COMMUNITY_yt-dlp Downloader|yt-dlp Downloader]]
- [[_COMMUNITY_Flashcard Assignments|Flashcard Assignments]]
- [[_COMMUNITY_Chrono Controller|Chrono Controller]]
- [[_COMMUNITY_Open-Answer Judge|Open-Answer Judge]]
- [[_COMMUNITY_PWA Offline Data Layer|PWA Offline Data Layer]]
- [[_COMMUNITY_CLIP ONNX Encoder|CLIP ONNX Encoder]]
- [[_COMMUNITY_ExtensionFrontend Config|Extension/Frontend Config]]
- [[_COMMUNITY_Card Generation Pipeline|Card Generation Pipeline]]
- [[_COMMUNITY_Host-Wrapper Endpoint Tests|Host-Wrapper Endpoint Tests]]
- [[_COMMUNITY_FileRepository Patch Tests|FileRepository Patch Tests]]
- [[_COMMUNITY_Markdown PreprocessorChunker|Markdown Preprocessor/Chunker]]
- [[_COMMUNITY_Frontmatter & Editor Utils|Frontmatter & Editor Utils]]
- [[_COMMUNITY_Service Worker (PWA)|Service Worker (PWA)]]
- [[_COMMUNITY_Folder Tree (UI)|Folder Tree (UI)]]
- [[_COMMUNITY_Dashboard Charts|Dashboard Charts]]
- [[_COMMUNITY_Mobile Layout|Mobile Layout]]
- [[_COMMUNITY_Responsive App Switch|Responsive App Switch]]
- [[_COMMUNITY_Servlet Initializer|Servlet Initializer]]
- [[_COMMUNITY_Web Config (static resources)|Web Config (static resources)]]
- [[_COMMUNITY_Community 32|Community 32]]
- [[_COMMUNITY_Community 35|Community 35]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 65|Community 65]]
- [[_COMMUNITY_Community 91|Community 91]]
- [[_COMMUNITY_Community 92|Community 92]]

## God Nodes (most connected - your core abstractions)
1. `split()` - 48 edges
2. `MediaControllerTest` - 42 edges
3. `SandboxError` - 28 edges
4. `NoteLinkRepositoryTest` - 24 edges
5. `FileRepository` - 23 edges
6. `SyncServiceTest` - 22 edges
7. `Router` - 21 edges
8. `ApiKeyMiddleware` - 20 edges
9. `FrontmatterRewriterTest` - 20 edges
10. `NotesControllerTest` - 20 edges

## Surprising Connections (you probably didn't know these)
- `Five independent hash-diff pollers model` --semantically_similar_to--> `Hash-diff workers, not call-site hooks (design philosophy)`  [INFERRED] [semantically similar]
  architecture_plans/PIPELINE_HARDENING_PLAN.md → ARCHITECTURE_KNOWLEDGE_BASE.md
- `Readiness gates (ingest_pending + future merge_pending)` --semantically_similar_to--> `ingest_pending readiness gate`  [INFERRED] [semantically similar]
  architecture_plans/PIPELINE_HARDENING_PLAN.md → ARCHITECTURE_KNOWLEDGE_BASE.md
- `Embedding pipeline (preprocess -> chunk -> embed -> upsert)` --semantically_similar_to--> `NoteEmbeddingWorker (hash-diff embedding)`  [INFERRED] [semantically similar]
  architecture_plans/ML_ARCH.md → ARCHITECTURE_KNOWLEDGE_BASE.md
- `Cloud sync encryption: AES-256-GCM` --semantically_similar_to--> `Encrypted Google Drive sync (AES-256-GCM, PBKDF2)`  [INFERRED] [semantically similar]
  architecture_plans/ENCRYPTION_ARCH.md → ARCHITECTURE_KNOWLEDGE_BASE.md
- `Video & Audio Manager architecture [SUPERSEDED/salvaged]` --semantically_similar_to--> `VideoManager sibling app (yt-dlp + agentic fallback)`  [INFERRED] [semantically similar]
  architecture_plans/VIDEO_MANAGER_ARCH.md → ARCHITECTURE_KNOWLEDGE_BASE.md

## Hyperedges (group relationships)
- **Pipeline-first, agent-last pattern (LLM only at synthesis, schema+capped retry)** — ingest_pipeline_first_rationale, fc_generation_pipeline, kaggle_caged_loop, sniffer_ranking [INFERRED 0.85]
- **Independent hash-diff pollers over Postgres** — akb_hash_diff_philosophy, akb_note_embedding_worker, fc_generation_pipeline, akb_image_pipeline, harden_five_pipeline_model [INFERRED 0.85]
- **Host-wrapper LLM gateway consumers (free providers first, claude-cli last)** — akb_host_wrapper, fc_generation_pipeline, ingest_synthesis, sniffer_ranking, kaggle_caged_loop [INFERRED 0.80]
- **Hash-diff-as-work-list pattern across pipelines** — cardsflows_generation, mlflows_embedding_pipeline, mlflows_image_pipeline [INFERRED 0.80]
- **Image text extraction: worker → host wrapper → vision LLM** — mlflows_image_pipeline, hwflows_process_image, hwflows_llmrouter [INFERRED 0.85]
- **Review scheduling: assignment scoring → FSRS+bandit → frontmatter write-back** — cardsflows_assignment, cardsflows_review_fsrs_bandit, chronoflows_frontmatter_rewriter [INFERRED 0.75]

## Communities

### Community 0 - "System Architecture (cross-cutting)"
Cohesion: 0.02
Nodes (134): Backend (Spring Boot), body_hash (frontmatter-stripped SHA-256, drives card diff), Chrono nightly orchestrator (mover/fixer/bankruptcy/spread), content_hash (full-note SHA-256, drives embedding diff), Encrypted Google Drive sync (AES-256-GCM, PBKDF2), Embedder service (FastAPI + ONNX, GPU), Frontend (React SPA), Hash-diff workers, not call-site hooks (design philosophy) (+126 more)

### Community 1 - "Vault File Repository"
Cohesion: 0.03
Nodes (10): FileRepository, FrontmatterParser, FrontmatterParserTest, ImageScanService, MediaController, NoteIndexRepository, NoteLifecycleIT, NoteLinkRepository (+2 more)

### Community 2 - "Ingest Bundle Chunking"
Cohesion: 0.03
Nodes (107): _fmt_ts(), number_segments(), Bundle utilities — deterministic chunking along segment boundaries.  The LLM n, Stable ids: segment index in bundle order., Split into ~WINDOW_TOKENS windows, never breaking inside a segment., [id @ loc] text — the shape both synthesis prompts consume., render_segments(), windows() (+99 more)

### Community 3 - "Flashcard Generation & App Entry"
Cohesion: 0.04
Nodes (96): App(), BaseModel, CapturePage(), FileMoverService, _complete(), ensure_schema(), _extract_json(), _format_with_descriptions() (+88 more)

### Community 4 - "Device Identity & Drive Sync"
Cohesion: 0.04
Nodes (14): DeviceIdentityService, DriveService, lifespan(), createShortenedNamesMapping(), fetchReviewNotes(), populateReviewNotes(), SyncController, SyncQueueRepository (+6 more)

### Community 5 - "Capture & Download Controller"
Cohesion: 0.03
Nodes (7): CaptureController, providers(), Router introspection: configured providers, cooldowns, ok/fail counts., MediaControllerTest, NotesController, NotesControllerTest, SettingsControllerTest

### Community 6 - "Bankruptcy Scheduler (FSRS)"
Cohesion: 0.05
Nodes (11): BankruptcyService, BankruptcyServiceTest, FileCheckerService, FileCheckerServiceTest, FileMoverServiceTest, FrontmatterChecker, FrontmatterRewriter, FrontmatterRewriterTest (+3 more)

### Community 7 - "Host-Wrapper LLM Router"
Cohesion: 0.04
Nodes (77): b64_image(), call_text(), call_vision_multi(), cmd_batch(), cmd_probe(), cmd_prompts(), configured_providers(), media_type() (+69 more)

### Community 8 - "Embedding Service (search)"
Cohesion: 0.03
Nodes (12): EmbeddingService, EmbeddingServiceBatchTest, ImageProcessingWorker, NoteChunk, NoteChunkRepository, NoteChunkRowMapper, NoteEmbeddingWorker, PendingImageJob (+4 more)

### Community 9 - "FLOWS Docs Index"
Cohesion: 0.03
Nodes (91): API Layer Flows, ApiError (401 → showLogin), useSearch debounce + AbortController, ASCII banner (banner.txt), Backend Flows Navigation Index, docker-compose stack (4 services + tunnel), Assignment/attempt scoring flow, Cards Domain Flows (+83 more)

### Community 10 - "Browser Extension Client"
Cohesion: 0.04
Nodes (58): checkAuth(), createNote(), downloadStatus(), listFolders(), login(), obsidian(), sanitizeName(), startDownload() (+50 more)

### Community 11 - "Bandit Scheduler"
Cohesion: 0.05
Nodes (12): BanditService, BanditServiceTest, FlashcardSession(), questionOf(), FsrsService, FsrsServiceTest, NoteReviewRepository, ReviewController (+4 more)

### Community 12 - "yt-dlp Downloader"
Cohesion: 0.04
Nodes (62): _StubYoutubeDL, build_ydl_opts(), download_sync(), fetch_audio(), fetch_subs(), _parse_percent(), parse_progress(), yt-dlp download core — salvaged from the former VideoManager sister app.  This i (+54 more)

### Community 13 - "Flashcard Assignments"
Cohesion: 0.05
Nodes (8): AssignmentController, AssignmentRepository, AssignmentService, InternalAgentController, logout(), ResourceScanService, ResourceScanServiceTest, SecurityConfig

### Community 14 - "Chrono Controller"
Cohesion: 0.06
Nodes (6): ChronoController, ChronoControllerTest, ChronoService, ChronoServiceIT, SettingsController, SettingsRepository

### Community 15 - "Open-Answer Judge"
Cohesion: 0.05
Nodes (45): judge_open_answer(), Open-answer verification: banded cosine + LLM judge for the contested middle., Returns {"verdict": CORRECT|PARTIAL|WRONG, "similarity": float,, find_home_for_note(), get_note_content(), ingest_resource(), _query_db(), MCP server — real Model Context Protocol (JSON-RPC over streamable HTTP).  Rep (+37 more)

### Community 16 - "PWA Offline Data Layer"
Cohesion: 0.06
Nodes (33): initializePage(), isOnline(), addToOutbox(), deleteFromOutbox(), getAllReviewNotes(), getMeta(), getOutbox(), getReviewNote() (+25 more)

### Community 17 - "CLIP ONNX Encoder"
Cohesion: 0.07
Nodes (38): _embeds(), encode_image(), encode_text(), _feeds(), _l2(), _load(), _providers(), Pure-ONNX CLIP (ViT-L/14) zero-shot encoder — no torch, no open_clip.  Runs on (+30 more)

### Community 18 - "Extension/Frontend Config"
Cohesion: 0.07
Nodes (24): getConfig(), setConfig(), deriveFolders(), FolderDropdown(), LeafNodeSingleton, buildDecorations(), markExtent(), addPendingBlob() (+16 more)

### Community 19 - "Card Generation Pipeline"
Cohesion: 0.08
Nodes (5): CardController, CardGenerationService, CardJobWorker, CardRepository, ReadinessGateIT

### Community 20 - "Host-Wrapper Endpoint Tests"
Cohesion: 0.11
Nodes (5): Flask endpoint tests — /health, /providers, /process-image, /complete.  Router, Point the /vault translation at a tmp dir containing one PNG., test_health(), test_providers_returns_router_status(), vault_image()

### Community 21 - "FileRepository Patch Tests"
Cohesion: 0.31
Nodes (1): FileRepositoryPatchTest

### Community 22 - "Markdown Preprocessor/Chunker"
Cohesion: 0.2
Nodes (3): MarkdownPreprocessor, PreprocessedNote, MarkdownPreprocessorTest

### Community 23 - "Frontmatter & Editor Utils"
Cohesion: 0.14
Nodes (7): parseFrontmatterFields(), splitFrontmatter(), FrontmatterTable(), MilkdownEditor(), WikiLinkSuggest(), SearchBar(), useSearch()

### Community 24 - "Service Worker (PWA)"
Cohesion: 0.31
Nodes (4): enqueueOutbox(), extractUrl(), handleShareTarget(), openDB()

### Community 25 - "Folder Tree (UI)"
Cohesion: 0.7
Nodes (4): FolderTree(), hasMatch(), sortEntries(), TreeNode()

### Community 26 - "Dashboard Charts"
Cohesion: 0.5
Nodes (2): DashboardPage(), pct()

### Community 27 - "Mobile Layout"
Cohesion: 0.5
Nodes (2): MobileLayout(), useOffline()

### Community 28 - "Responsive App Switch"
Cohesion: 0.5
Nodes (2): ResponsiveApp(), useMediaQuery()

### Community 30 - "Servlet Initializer"
Cohesion: 0.67
Nodes (2): ServletInitializer, SpringBootServletInitializer

### Community 31 - "Web Config (static resources)"
Cohesion: 0.67
Nodes (2): WebConfig, WebMvcConfigurer

### Community 32 - "Community 32"
Cohesion: 0.67
Nodes (1): Generate FSRS-6 reference values from py-fsrs to pin the Java port's tests. Run

### Community 35 - "Community 35"
Cohesion: 1.0
Nodes (2): registerServiceWorker(), requestPersistentStorage()

### Community 39 - "Community 39"
Cohesion: 0.67
Nodes (1): ObsidianApplicationTests

### Community 65 - "Community 65"
Cohesion: 1.0
Nodes (2): PWA App Icon (rounded-square, purple triangle + dot), Maskable PWA Icon (purple triangle + dot, safe-zone)

### Community 91 - "Community 91"
Cohesion: 1.0
Nodes (1): Security: Spring session single-user, BCrypt, CSRF disabled

### Community 92 - "Community 92"
Cohesion: 1.0
Nodes (1): App Favicon (faceted purple crystal)

## Knowledge Gaps
- **177 isolated node(s):** `MCP server — real Model Context Protocol (JSON-RPC over streamable HTTP).  Rep`, `RRF over (note_path, chunk_index, text) rankings, deduped per note.`, `Resolve a vault-relative or absolute path, refusing anything outside /vault.`, `Hybrid search over the Obsidian vault: semantic (pgvector cosine) +     full-te`, `Read the full markdown content of a note. Accepts the notePath values     retur` (+172 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `FileRepository Patch Tests`** (16 nodes): `FileRepositoryPatchTest`, `.emptyHunkListIsNoOp()`, `.insertionAtEndOfFile()`, `.insertionAtStartOfFile()`, `.multiLineInsertionHunk()`, `.multipleHunksAppliedBackToFront()`, `.nonExistentFileThrowsIOException()`, `.nullHunkListIsNoOp()`, `.outOfRangeHunkThrowsIOException()`, `.preservesCrlfSeparator()`, `.singleLineDeletion()`, `.singleLineInsertion()`, `.singleLineReplacement()`, `.writeNote()`, `.patchNote()`, `FileRepositoryPatchTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Dashboard Charts`** (5 nodes): `DashboardPage()`, `Donut()`, `Legend()`, `pct()`, `DashboardPage.jsx`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Mobile Layout`** (4 nodes): `MobileLayout.jsx`, `useOffline.js`, `MobileLayout()`, `useOffline()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Responsive App Switch`** (4 nodes): `ResponsiveApp.jsx`, `useMediaQuery.js`, `ResponsiveApp()`, `useMediaQuery()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Servlet Initializer`** (4 nodes): `ServletInitializer.java`, `ServletInitializer`, `.configure()`, `SpringBootServletInitializer`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Web Config (static resources)`** (4 nodes): `WebConfig.java`, `WebConfig`, `.addResourceHandlers()`, `WebMvcConfigurer`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 32`** (3 nodes): `_fsrs_reference.py`, `first_review()`, `Generate FSRS-6 reference values from py-fsrs to pin the Java port's tests. Run`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 35`** (3 nodes): `registerSW.js`, `registerServiceWorker()`, `requestPersistentStorage()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 39`** (3 nodes): `ObsidianApplicationTests.java`, `ObsidianApplicationTests`, `.contextLoads()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 65`** (2 nodes): `PWA App Icon (rounded-square, purple triangle + dot)`, `Maskable PWA Icon (purple triangle + dot, safe-zone)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 91`** (1 nodes): `Security: Spring session single-user, BCrypt, CSRF disabled`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 92`** (1 nodes): `App Favicon (faceted purple crystal)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `split()` connect `Ingest Bundle Chunking` to `Vault File Repository`, `Flashcard Generation & App Entry`, `Device Identity & Drive Sync`, `Bankruptcy Scheduler (FSRS)`, `Host-Wrapper LLM Router`, `Open-Answer Judge`, `PWA Offline Data Layer`, `CLIP ONNX Encoder`, `Extension/Frontend Config`, `Markdown Preprocessor/Chunker`, `Frontmatter & Editor Utils`?**
  _High betweenness centrality (0.109) - this node is a cross-community bridge._
- **Why does `parseFrontmatterFields()` connect `Frontmatter & Editor Utils` to `Ingest Bundle Chunking`?**
  _High betweenness centrality (0.022) - this node is a cross-community bridge._
- **Why does `embed_texts()` connect `Open-Answer Judge` to `Flashcard Generation & App Entry`, `Bankruptcy Scheduler (FSRS)`?**
  _High betweenness centrality (0.021) - this node is a cross-community bridge._
- **Are the 45 inferred relationships involving `split()` (e.g. with `split_note()` and `split_note()`) actually correct?**
  _`split()` has 45 INFERRED edges - model-reasoned connections that need verification._
- **Are the 24 inferred relationships involving `SandboxError` (e.g. with `EmbedRequest` and `EmbedResponse`) actually correct?**
  _`SandboxError` has 24 INFERRED edges - model-reasoned connections that need verification._
- **What connects `MCP server — real Model Context Protocol (JSON-RPC over streamable HTTP).  Rep`, `RRF over (note_path, chunk_index, text) rankings, deduped per note.`, `Resolve a vault-relative or absolute path, refusing anything outside /vault.` to the rest of the system?**
  _177 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `System Architecture (cross-cutting)` be split into smaller, more focused modules?**
  _Cohesion score 0.02 - nodes in this community are weakly interconnected._