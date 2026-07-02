# Extension Media Capture — find the videos & URIs on the page [NOT IMPLEMENTED]

Files (planned): `extension/content-scan.js` (new), `extension/background.js` (scan message +
probe route), `extension/popup.js`/`popup.html` (candidate picker), Java
`capture/CaptureController.java` (`POST /probe` proxy), embedder `main.py` (`POST /probe`,
yt-dlp simulate around `download/downloader.py`).
Replaces the remaining scope of the deleted `LINK_SNIFFER_AGENT_ARCH.md` (network-sniffing
lane dropped — see Decisions); builds on the existing vanilla extension (`extension/FLOWS.md`)
and the already-implemented `/capture` + `/download` backend proxies.

> One sentence: today the extension only captures **the URL you hand it**; this adds a
> "what's on this page?" scanner that finds the media (videos, audio, PDFs, embeds) the
> page contains, lets you pick, and routes the pick through the existing capture/download
> pipeline — no new pipeline, just a better front door.

---

## What already exists (do not rebuild)

| Piece | Where | State |
|---|---|---|
| URL/text/file classify + route | `extension/background.js classify()/routeText()` | ✅ shipped |
| Context menu (selection/link/page) | `background.installMenus()` | ✅ shipped |
| `POST /api/capture {url|text}` → ingest | `CaptureController.capture()` | ✅ shipped |
| `POST /api/download` → embedder yt-dlp | `CaptureController.download()` proxy | ✅ shipped |
| yt-dlp download + captions | `embedder/download/downloader.py` | ✅ shipped |

The gap: a page whose **address bar URL is not the media** — lecture portals, blogs with
embedded players, course pages listing 10 PDFs. `classify()` sees "web page" and captures
the article text; the video inside is invisible.

---

## Flow

```
popup "Scan page" (or context menu "Scan for media")
  → background injects content-scan.js into the active tab   (activeTab permission)
  → DOM scan → Candidate list → popup renders it (grouped, labeled)
  → user picks one (or "capture article text" fallback)
      embed_page / media URL → existing routeText() paths:
         POST /capture (notes)  and/or  POST /download (offline copy)
  → fire-and-forget, results land in Learn (same as today)
```

User click stays the authorization boundary — **no auto-download, no auto-capture**.

## Stage 1 — DOM scan (`content-scan.js`, deterministic, zero LLM)

Injected on demand via `api.scripting.executeScript` (needs `scripting` + `activeTab`
permissions — `activeTab` avoids broad host injection warnings). Collects, in DOM order:

| Source | Candidate kind |
|---|---|
| `<video src>`, `<video><source>`, `<audio>` | `media_url` (direct file / blob flag) |
| iframe src of known embed hosts (youtube, vimeo, dailymotion, …) | `embed_page` (a page URL yt-dlp handles natively) |
| `og:video`/`og:audio` meta, JSON-LD `VideoObject.contentUrl/embedUrl` | `media_url` / `embed_page` |
| `<a href>` ending `.pdf .mp4 .mp3 .m4a .webm .ogg …` | `file_link` (with anchor text as label) |
| the page URL itself when host matches a video platform | `embed_page` (top-ranked) |

Each candidate: `{url, kind, label, mime_guess, origin: "video-tag|iframe|meta|link"}`.
Dedupe by URL; `blob:` URLs are flagged **not capturable** (MSE streams — the real
stream URL isn't in the DOM; show them greyed with "use the page URL instead" hint).

## Stage 2 — Ranking (client-side heuristics, no server round-trip)

Tiers, in the popup's display order:
1. `embed_page` (yt-dlp native) — including the page URL itself on video hosts
2. direct `media_url` (`.mp4/.webm/.mp3/...`)
3. `file_link` (PDFs first, then A/V, then other docs)
4. greyed: `blob:` sources, data URIs, tracking-ish domains (small blocklist)

No LLM tiebreak in v1 — the user is looking at the list; their click *is* the tiebreak.
(The old sniffer plan's LLM rank stage is dropped, not deferred: it solved auto-pick,
and we deliberately don't auto-pick.)

## Stage 3 — Probe (optional confidence, deterministic)

Popup "check" per candidate → `POST /api/probe {url}` (new thin proxy in
`CaptureController`, same pattern as `/download`) → embedder `POST /probe` →
`yt-dlp -J --simulate` (≈2–5 s) → `{supported, title, duration_s, formats_count}`;
unsupported → HEAD request → `{mime, size}`. Popup shows title/duration next to the
candidate so you know it's the right video **before** downloading a 2 GB file.
DRM detected (`drm` fields in yt-dlp output) → labeled not-downloadable, never worked
around.

## Stage 4 — Dispatch (existing paths, unchanged)

Selected candidate goes through today's routing table (`background.routeText()`):
video/embed → `/capture` + `/download`; PDF/media file → `/capture` + `/workspace/save`;
everything lands in Learn as it already does.

---

## Decisions (made here; revisit if reality disagrees)

1. **DOM scan only — no `webRequest` network log (v1).** The network lane finds
   tokenized HLS/DASH manifests DOM scanning misses, but: MV3 service worker sleep
   forces the ring buffer into `chrome.storage.session`, the captured URLs expire in
   minutes (CDN signatures), auth'd streams need cookie export (a real security
   decision), and Firefox/Chrome `webRequest` behavior diverges. High complexity,
   low hit-rate for the actual use case (YouTube/uni portals — yt-dlp handles those
   from the page URL alone). **If** a real page class shows up where DOM scan fails
   and yt-dlp can't extract, add the network lane as a separate phase — the candidate
   list contract already accommodates it (`origin: "network"`).
2. **No LLM anywhere in this flow.** Closed-choice ranking was for auto-pick; we show
   the list instead. Cheaper, debuggable, zero slop risk.
3. **Probe lives in the embedder**, not a new container — yt-dlp is already there
   (`download/downloader.py`), and `CaptureController` already proxies to it
   (loopback-only embedder stays unexposed).
4. **Stay vanilla.** No React/Vite build in the extension; `content-scan.js` is plain
   JS like the rest (`extension/FLOWS.md` explains why).

## Technology Notes (constraints / failure modes)

- **`activeTab` + `scripting`**: injection works only after a user gesture on the
  extension (popup open / context menu) — exactly our model. No persistent content
  script, no "reads all your pages" permission escalation.
- **`blob:` / MSE players are a hard wall for DOM scanning** — the manifest URL lives
  in JS state, not the DOM. For known hosts yt-dlp extracts server-side from the page
  URL anyway; for unknown custom players v1 honestly says "can't grab this".
- **Iframes are opaque cross-origin**: we read the iframe `src` (enough for yt-dlp),
  never its inner DOM.
- **Probe is synchronous-ish (2–5 s)**: fine per-click; do NOT auto-probe all
  candidates on scan (N × 5 s + hammering the embedder's thread-per-job model).
- **yt-dlp staleness** applies to probe too — a site-wide probe failure usually means
  "bump yt-dlp", not "the page has no video".
- **Firefox parity**: `api.scripting` exists in FF 121+ (already the minimum). The
  build script copies JS verbatim — keep `content-scan.js` browser-neutral (`api.*`).

## Phasing

1. **Scan + picker** — `content-scan.js`, popup candidate list, dispatch through
   existing routes. (Delivers the whole user-visible feature.)
2. **Probe** — embedder `/probe` + Java proxy + per-candidate "check" button.
3. *(only if proven needed)* network-log lane for custom HLS players.

## Change Index

| Thing to change | Where (planned) |
|---|---|
| DOM selectors / embed-host list | `extension/content-scan.js → DOM_SOURCES / EMBED_HOSTS` |
| Ranking tiers / blocklist | `extension/popup.js → TIERS / AD_BLOCKLIST` |
| Scan trigger + injection | `extension/background.js` (`scripting.executeScript`) |
| Probe endpoint | embedder `main.py → POST /probe` (around `downloader`) |
| Probe proxy | `CaptureController → POST /probe` (copy the `/download` proxy) |
| Dispatch routing | existing `background.routeText()` — unchanged |
| New permissions | `manifest.json` / `manifest.firefox.json` → `scripting`, `activeTab` |
