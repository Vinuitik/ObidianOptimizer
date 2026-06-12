# Link Sniffer Agent — Extension-Connected Resource Capture [NOT IMPLEMENTED]

Files: [NOT IMPLEMENTED] — planned: extension/src/background/netlog.ts, extension/src/background/capture.ts, embedder/sniffer/rank.py, embedder/sniffer/probe.py; depends on EXTENSION_ARCH.md (extension itself not built yet)

The third and final agent. Job: when the user is on a page with a video/audio/
document they want ingested, figure out **which URL to hand to yt-dlp (VideoManager)
or to a plain downloader** — from the page's network traffic and HTML — so the
resource lands in the vault and flows into the ingest agent (INGEST_AGENT_ARCH.md).

**Same philosophy as ingest: pipeline-first, agent-last.** Capture and candidate
extraction are deterministic browser-side code. Ranking is heuristics first; the
LLM is consulted only when heuristics are ambiguous, and it picks from a closed
candidate list — it never invents URLs.

```
page → extension capture (network log + DOM scan)   [deterministic, browser]
     → Candidate Bundle JSON → backend → sniffer agent
     → heuristic ranking → (only if ambiguous) LLM tiebreak
     → validation probe: yt-dlp --simulate via VideoManager
     → user confirms in extension popup → download → ingest pipeline
```

---

## Stage 1 — Capture (extension, deterministic)

Two independent sources, merged into one candidate list:

### Network log (background service worker)
`chrome.webRequest.onResponseStarted` listener (observational, MV3-compatible),
per-tab ring buffer (last ~200 media-ish responses). A response is media-ish if:

| Signal | Examples |
|---|---|
| URL extension | `.m3u8 .mpd .mp4 .webm .mp3 .m4a .aac .ogg .pdf` |
| Content-Type | `video/* audio/* application/vnd.apple.mpegurl application/dash+xml application/pdf` |
| Size heuristic | `Content-Length > 2MB` with ambiguous type |

Each entry: `{url, mime, size, tab_url, ts, initiator}`. Manifest/playlist URLs
(`.m3u8/.mpd`) outrank raw segment URLs (`.ts` chunks are noise — collapse runs of
segment requests to their manifest when the manifest was seen).

### DOM scan (content script, on capture click)
`<video src>`, `<video><source src>`, `<audio>`, `og:video` / `og:audio` meta,
`<link rel=preload as=video>`, JSON-LD `VideoObject.contentUrl`, iframe srcs of
known embed hosts (youtube/vimeo/etc — these are *page URLs* for yt-dlp, not file
URLs, tagged `kind: "embed_page"`).

### Candidate Bundle (the contract)
```json
{
  "page": { "url": "…", "title": "…" },
  "candidates": [
    { "url": "…master.m3u8", "source": "network", "mime": "application/vnd.apple.mpegurl",
      "size": null, "kind": "hls_manifest" },
    { "url": "https://vimeo.com/123", "source": "dom", "kind": "embed_page" }
  ],
  "cookies_domain": "example.com"     // flag only — see auth note below
}
```

---

## Stage 2 — Ranking (server-side, heuristics first)

`sniffer/rank.py`, pure rules, zero tokens:

1. `embed_page` of a yt-dlp-supported host → top (yt-dlp handles these natively;
   check against `yt-dlp --list-extractors` cache from VideoManager)
2. `hls_manifest` / `dash_manifest` → next (master > media playlist)
3. progressive `.mp4/.webm/.mp3/.m4a` by size desc
4. drop: tracking pixels, ad-server domains (blocklist), DRM manifests
   (`#EXT-X-KEY: METHOD=SAMPLE-AES` / `<ContentProtection>` detected at probe
   stage → marked `drm: true`, surfaced as "not downloadable", never worked around)

**LLM tiebreak** (host-wrapper `/complete`, text chain) ONLY when ≥2 candidates
survive in the same tier: model sees page title + candidate URLs/mimes/sizes and
returns the index of the most likely main-content resource + 1-line reason.
Closed choice — output schema `{"pick": int, "reason": str}`, never a new URL.

---

## Stage 3 — Validation probe (deterministic)

Before showing the user a confident answer:
```
VideoManager POST /api/v1/probe {url}        [new endpoint, yt-dlp -J --simulate]
  → supported? duration/formats/title        → confidence: high
  → unsupported → HEAD request: mime + size  → confidence: medium (plain download)
  → both fail → confidence: low (show raw candidate list)
```

## Stage 4 — Confirm & dispatch

Extension popup shows ranked candidates (title, duration, size, confidence).
User picks → `POST /api/v1/download` (VideoManager) or plain fetch to vault
`resources/` via Java backend → on completion, offer "ingest now" → ingest agent.
**No auto-download, ever** — the user click is the authorization boundary.

---

## Where the agent lives

- **Extension**: capture only (network log + DOM scan + popup UI). No LLM calls
  from the browser; bundle goes to the backend.
- **embedder container** `sniffer/` module: ranking + LLM tiebreak + probe
  orchestration, exposed as `POST /sniffer/rank {bundle}` (and MCP tool
  `rank_capture` for chat-driven use).
- **VideoManager**: gains `POST /api/v1/probe` (yt-dlp -J --simulate). Download
  endpoint already exists.

---

## Technology Notes

- **MV3 limits**: `chrome.webRequest` is observe-only in MV3 (blocking needs
  `declarativeNetRequest`) — fine, we only log. Response *bodies* are not
  readable; we never see media bytes, only URLs/headers. Service workers sleep —
  the ring buffer must live in `chrome.storage.session`, not a JS variable.
- **Tokenized stream URLs expire** (CDN signatures, often minutes). Probe and
  download must happen promptly after capture; a stale capture should re-trigger
  from the live tab rather than retry a dead URL.
- **Auth-gated streams**: yt-dlp may need cookies. v1: `cookies_domain` flag tells
  the user "this needs cookies"; passing actual cookies is deferred — it means
  exporting browser cookies to VideoManager, a real security decision, not v1.
- **DRM is out of scope by design**: detected → labeled not-downloadable. The
  agent must never suggest workarounds.
- **Scope/ethics**: personal tool for resources the user can legitimately access;
  the user-click authorization boundary and no-auto-download rule are load-bearing.
- **Why server-side ranking**: keeps LLM keys out of the extension, reuses the
  router's free-tier chain, and the candidate bundle is small JSON (no CORS pain —
  background worker → backend, per EXTENSION_ARCH).

---

## Change Index

| Thing to change | Where (planned) |
|---|---|
| Media-ish detection rules | `extension/src/background/netlog.ts → MEDIA_RULES` |
| Ring buffer size | `extension/src/background/netlog.ts → MAX_ENTRIES` |
| DOM selectors | `extension/src/content/capture.ts → DOM_SOURCES` |
| Ranking tiers / blocklist | `embedder/sniffer/rank.py → TIERS / AD_BLOCKLIST` |
| LLM tiebreak prompt | `embedder/sniffer/rank.py → TIEBREAK_PROMPT` |
| Probe endpoint | `VideoManager → POST /api/v1/probe` |
| Supported-extractor cache | VideoManager `--list-extractors` refresh |
