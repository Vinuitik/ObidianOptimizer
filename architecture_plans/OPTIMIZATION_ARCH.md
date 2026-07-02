# Optimization & Performance Architecture

## Philosophy: "Make it work, make it right, make it fast"
Currently, caching is disabled to prevent stale data while developing. Once the core features are built and stable, treating performance as a first-class feature is critical. A note-taking system must feel instantaneous; any friction stops the user from capturing their thoughts.

## 1. Backend Optimizations (Spring Boot)
*   **Virtual Threads (Project Loom)**: If running Java 21+, we will use Virtual Threads for all I/O-heavy operations (e.g., calling Ollama, hitting the Anthropic API via the host wrapper, waiting for VideoManager to download). This prevents thread-blocking and keeps the web API snappy even under heavy background load.
*   **Spring Cache (`@Cacheable`)**:
    *   *Note Tree*: The file system tree rarely changes structure but is requested on every load. This will be cached in memory (Caffeine or Redis) and invalidated only when a file creation/deletion event occurs.
    *   *Parsed Markdown*: Heavy AST parsing (Milkdown/remark) results can be cached so read-only views are instant.
*   **Parallelism**: Indexing a large vault for the first time or running bankruptcy checks will use `java.util.concurrent.CompletableFuture` to process files in parallel.

## 2. Database Optimizations (PostgreSQL)
*   **Vector Searching (pgvector)**: A sequential scan of 768-dimensional vectors is slow. We will build an **HNSW (Hierarchical Navigable Small World)** or **IVFFlat** index on the `embedding` column. This makes nearest-neighbor vector search effectively instant, even with millions of chunks.
*   **Text Searching**: Ensure a `GIN` index is built on the `fts_vector` column to instantly resolve BM25 text queries.
*   **Connection Pooling**: Ensure HikariCP is configured to handle the concurrent connections smoothly, specifically during the parallel vault-indexing phase.

## 3. Frontend Optimizations (React / Web)
*   **Client-Side Caching (React Query / SWR)**: API responses will be cached in the browser. If you navigate away and back to a note, it loads from RAM instantly while silently validating with the server in the background.
*   **Virtualization**: If a folder has 5,000 notes, rendering 5,000 DOM nodes will freeze the browser. We will use `react-window` or `react-virtuoso` to only render the 30 items currently visible on the screen.
*   **Lazy Loading**: The heavy Milkdown editor and its plugins will be lazy-loaded (`React.lazy`). The initial page load will just be the structural UI, keeping Time-To-Interactive (TTI) under 200ms.

## 4. Mobile Optimizations
*Stale (native-app era) — superseded by the PWA approach.* Mobile perf now means: keep the
offline IndexedDB subset small (due + next N days), cache media explicitly not greedily,
and lazy-load the desktop-only chunks (Milkdown) out of the mobile shell's critical path.
See `PWA_MOBILE_ARCH.md` §15 for the offline lanes.

## 5. Parked Option: strict FSRS lapse mode could retire the chrono jobs

The flashcard system (see FLASHCARDS_ARCH) deliberately runs FSRS **without** the
"Again"/lapse band — a failed review never resets a note's stability to near-zero.
Rationale: skipping a few days must not cascade into mass interval resets, and overload
management already exists (BankruptcyService, SpreadService).

The parked optimisation: if we ever enable strict FSRS lapse semantics (fail → stability
reset), FSRS would self-manage review load — overdue notes would naturally re-enter with
short intervals, making the bankruptcy/spread/sr-due frontmatter machinery largely
redundant. Retiring those chrono jobs would cut:
*   the 2am scheduled run and its full-vault read pass,
*   the startup chrono check (blocking app start when `chronoLastRunDate` is stale),
*   the FrontmatterRewriter disk writes (and their Drive sync churn).

**Deliberately not doing this now** — too punitive for real usage patterns. Revisit only
if startup/chrono cost actually becomes a measured bottleneck (step 2 below).

## Execution Strategy
1. Build the feature with caching disabled to guarantee correctness.
2. Identify the bottlenecks (e.g., "loading the tree takes 2 seconds").
3. Apply the specific optimization from this document.