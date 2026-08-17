# Web Acquisition — extension scanner + server-side agent (DESIGN / PLAN)

Files (shipped): extension/background.js, popup.js, config.js; embedder/ingest/download/downloader.py; obsidian .../capture/CaptureController.java
Files (planned): extension/content-scan.js `[NEW]`, extension/netcap.js `[NEW]`, popup additions;
  embedder main.py `POST /probe` + `POST /acquire` `[NEW]`; host-wrapper acquire-agent `[NEW]`;
  CaptureController `/probe` + `/acquire` proxies `[NEW]`
Related: EXTENSION_MEDIA_CAPTURE_ARCH.md (DOM-scan front door — kept), extension/FLOWS.md (shipped
  routing), INGESTION_V2_FLOWS.md (what happens after the bytes land)

> **Purpose:** get the *right source bytes* off any web page into the ingest pipeline,
> using the fact that the extension rides your logged-in session. Four escalating tiers,
> cheapest first; the LLM agent is the **last-resort fallback**, not the default path.

---

## 0. Why this revises a prior "no agent" decision

`EXTENSION_MEDIA_CAPTURE_ARCH.md` dropped the network-sniffing lane + any LLM agent.
Those objections assumed the agent lived **inside the MV3 extension**:

| Original objection | Still true? |
|---|---|
| SW sleep → captured URLs buffered in `storage.session`, expire in minutes | **Weakened** — we forward on your click *within the viewing session*; nothing is retained long-term |
| Authed streams need cookie export (security) | **Still true** — decided: forward cookies to backend, never to the LLM (§6) |
| Chrome/Firefox `webRequest` diverge | Manageable — observational listener only, `api.*` shim |
| Low hit-rate vs. complexity | **Addressed by design** — agent is Tier 3, runs only when Tiers 0–2 fail, by explicit user click (§1) |

The unlock: you now have a **host-wrapper LLM** and a **server-side embedder already
running yt-dlp**. Relocating the agent there removes the MV3 constraints and puts the
"creative problem-solving" where it belongs (server-side, debuggable, no SW sleep).

---

## 1. The acquisition ladder — escalate only on failure

```
Tier 0  address bar IS the media        background.classify() → route         ✅ SHIPPED
Tier 1  media embedded in the page       content-scan.js DOM scan → picker     ✗ planned (EXTENSION_MEDIA_CAPTURE)
Tier 2  confirm the pick before a 2 GB dl /probe → yt-dlp --simulate           ✗ planned
Tier 3  stream hidden (custom/HLS/auth)   netcap.js + server-side agent         ✗ NEW (this doc)
```

**Escalation is user-driven.** The popup shows what each cheaper tier found; you only
press **"Deep grab (agent)"** when Tiers 0–2 come up empty or wrong. So the expensive
lane runs rarely and by choice — which is exactly what defuses the old "low hit-rate"
cost. Every tier emits the *same* `Candidate` contract, so dispatch (§5) is uniform.

---

## 2. What's shipped vs. new (don't rebuild)

| Piece | Where | State |
|---|---|---|
| classify text/url/media/video-platform → route | `background.classify()/routeText()` | ✅ |
| session-cookie auth to your backend (`credentials:'include'`) | `background.js` fetch | ✅ |
| file drop → `/workspace/upload` → `/capture` | `background.uploadFile()` | ✅ |
| yt-dlp download + captions | `download/downloader.py` | ✅ |
| DOM scanner + candidate picker | `content-scan.js` | ✗ planned |
| probe (`yt-dlp --simulate`) | embedder `/probe` + proxy | ✗ planned |
| **network capture** | `netcap.js` | ✗ **NEW** |
| **server-side acquire agent** | host-wrapper | ✗ **NEW** |
| **site-cookie forwarding** | `netcap.js` + embedder cookiejar | ✗ **NEW** |

---

## 3. How the extension looks (the UI for pdf / html / txt / video)

The popup keeps today's one box and adds a scan affordance:

```
┌─ Obsidian Optimizer ───────────────┐
│ [ paste text / url / drop a file ] │  → Tier 0 (classify): txt→note, html→capture,
│                                    │     .pdf/.mp4→capture+save, youtube→capture+download
│  ( Capture )                       │
│ ─────────────────────────────────  │
│  🔍 Scan this page                 │  → Tier 1: content-scan.js
│   ▸ 🎬 Lecture 3 (embedded player) │     each Candidate: label + kind + [check] [grab]
│   ▸ 📄 slides.pdf                  │     [check] = Tier 2 probe (title/duration/size)
│   ▸ 📄 problem-set.pdf             │
│   …                                │
│  ⚡ Deep grab (agent)  ← shown when │  → Tier 3: netcap + server agent
│     scan is thin / player is blob: │
└────────────────────────────────────┘
```

- **txt / html / pdf / direct media**: already handled by Tier 0/1 — pdf & media links
  route to `/capture` (notes) **+** `/workspace/save` (a readable/watchable copy); html
  routes to `/capture` (article → synthesized note). Nothing new needed.
- **video on a normal host** (youtube/vimeo/uni portal): Tier 0/1 hands the *page URL*
  to yt-dlp — no scraping needed.
- **video behind a custom/blob player**: Tiers 0–2 fail (blob: URLs aren't grabbable,
  the manifest is in JS state) → **Deep grab** lights up → Tier 3.

`Candidate = {url, kind: media_url|embed_page|file_link|network|agent, label, mime_guess, origin}`.
*To change the picker UI:* `popup.js`. *To change what "thin scan" triggers Deep grab:*
`popup.js → DEEP_GRAB_HINT`.

---

## 4. Tier 3a — extension network capture (`netcap.js` `[NEW]`)

Observational only (no blocking), within the active viewing session:

```
manifest: permissions += "webRequest", "cookies";  host_permissions cover the target
api.webRequest.onBeforeRequest / onCompleted  (active tab)
  filter URL by MEDIA_PATTERNS (.m3u8 .mpd .mp4 .m4s .ts, mime audio/*|video/*)
  push {url, method, type, tabId, ts} → ring buffer (cap NETCAP_MAX, e.g. 200)
  persist buffer → storage.session on each push   (survives MV3 SW sleep; cleared on browser close)
on "Deep grab" click:
  gather = { pageUrl, title, domCandidates(content-scan), netlog(ring buffer for tabId) }
  cookies = api.cookies.getAll({domain: targetDomain})   (§6)
  POST /api/acquire { gather, cookies }   (authed channel, immediate — URLs still fresh)
```

**Why immediacy beats the expiry objection:** signed CDN URLs die in minutes, but you
press Deep grab *while watching*, and the server acts at once. The only lossy case —
browse, walk away hours, then grab — degrades to "re-open the tab," which is fine.

*To change what's captured:* `netcap.js → MEDIA_PATTERNS / NETCAP_MAX`.
**Firefox note:** MV3 observational `webRequest` exists in FF; keep listeners `api.*`.

## 4. Tier 3b — server-side acquire agent (host-wrapper `[NEW]`)

The "creative problem solving." Runs server-side, orchestrated by embedder `/acquire`:

```
embedder POST /acquire { gather, cookies }
  store cookies → transient yt-dlp cookiejar (Netscape format), TTL-scoped to this job (§6)
  build agent prompt: pageUrl, title, domCandidates, netlog (URLs + request headers:
       Referer/Origin/User-Agent), probe hints — but { cookies_present: true, domain }
       ONLY, never cookie values
  host-wrapper /complete (LLM) → structured AcquirePlan (JSON, schema-validated):
     { method: "ytdlp"|"direct",
       target_url,
       ytdlp_args: [ ... allow-listed flags only ... ],
       http_headers: { Referer, Origin, User-Agent },
       format_selector,            # e.g. "bv*+ba/b"
       needs_cookies: bool,
       drm: bool, notes }
  VALIDATE plan server-side (§7 trust boundary) → reject if it smells wrong
  drm==true → stop, report "protected, not downloadable" (never worked around)
  execute → download/downloader.py yt-dlp(target_url, args, headers, --cookies jar if needs_cookies)
  success → hand the file to ingest (INGESTION_V2 / v1) exactly like any other source
```

**Where the LLM earns its place** (things yt-dlp-from-page-URL can't do alone): pick the
real manifest among many variants; know site X needs a `Referer`; assemble a format
selector; recognize a DRM wall and bail; choose between the `.m3u8` and a progressive
`.mp4` in the netlog. **Where it does NOT decide:** it never runs a shell — it emits a
*plan* that the server validates and executes through the existing yt-dlp wrapper.

*To change the agent:* host-wrapper acquire-agent prompt; `ACQUIRE_MODEL` env.
*To change allowed yt-dlp flags:* embedder `acquire.YTDLP_ALLOWED_ARGS`.

---

## 5. Dispatch (uniform, mostly existing)

Every tier ends with a `Candidate`/`AcquirePlan` that resolves to either:
- **direct/media** → `POST /workspace/save` (copy) + `POST /capture` (ingest), or
- **yt-dlp** → `download/downloader.py` → file → ingest.

Then the INGESTION_V2 pipeline takes over (extract → segment → draft → review → commit).
The acquire lane's job ends when the bytes are on disk.

---

## 6. Cookie policy — **DECIDED: forward to backend, NOT to the LLM**

```
extension: api.cookies.getAll({domain})  → cookies for the target domain only
   → POST /api/acquire over the authed tunnel (HTTPS)
embedder:  write → transient cookiejar file (Netscape), used for THIS job's yt-dlp call,
   deleted on job end (success or fail); never persisted to the vault, never logged
agent prompt: sees ONLY { cookies_present: true, domain } — never a name or value
```

- The LLM can *reason that* the stream is authed and set `needs_cookies:true`, without
  ever seeing a secret. yt-dlp does the actual auth via `--cookies jar`.
- **Scope discipline:** only the target media domain's cookies, only on explicit Deep
  grab, only for one job. *To change:* `netcap.js → cookieDomainsFor()`; embedder
  `acquire.COOKIEJAR_TTL`.
- This is the residual risk you accepted: your authed session for that site transits
  your backend. Mitigated by transient storage + LLM redaction; it is your infra.

---

## 7. Technology Notes (constraints / failure modes / security)

- **LLM trust boundary — the load-bearing safety rule.** The agent's output is *data*,
  not a command. `ytdlp_args` are filtered against an **allow-list** (`--format`,
  `--referer`, `--user-agent`, `--cookies`, …); anything else is dropped. `target_url`
  must be http(s). No value from the LLM is ever interpolated into a shell string —
  `downloader.py` calls yt-dlp with an argv array. Treat a plan that fails validation as
  "agent failed," not "run it anyway."
- **blob: / MSE is still a wall for the DOM**, but Tier 3 is exactly the escape hatch —
  the netlog usually holds the underlying `.m3u8` the blob player fetched.
- **MV3 service-worker sleep** loses the in-memory ring buffer; `storage.session` is the
  backstop, and immediacy-on-click is the real design (§4a). Do **not** try to keep a
  persistent background scraper — that's the escalation the prior decision rightly feared.
- **CDN-signed URL expiry** — minutes. Act immediately; stale grabs degrade to "reopen
  tab," not silent corruption.
- **DRM** (`drm` in yt-dlp / agent detection) → reported, never circumvented.
- **yt-dlp staleness** — a site-wide failure usually means "bump yt-dlp," not "no video."
- **Cookie exposure** — see §6; transient, backend-only, LLM-redacted.
- **Double fetch** for video (ingest transcribes, `/workspace/save` keeps a copy) — the
  accepted cost from extension/FLOWS.md; unchanged.
- **Permissions escalation** — adding `webRequest` + `cookies` + broad host access is a
  real manifest change; gate it so casual users on the shipped build don't get it unless
  they opt into Deep grab. *To change:* `manifest*.json`.

---

## 8. Phasing

1. **Tier 1 scanner + Tier 2 probe** (EXTENSION_MEDIA_CAPTURE_ARCH) — the front door,
   no agent, no cookies. Biggest coverage for least risk.
2. **Tier 3 netcap + `/acquire` + agent** — the fallback lane; add `webRequest` behind
   Deep grab.
3. **Cookie forwarding** — last, since it's the security-sensitive bit; ship Tier 3
   public-media-only first, then add the cookiejar.

---

## Change Index

| Thing to change | Where |
|---|---|
| Tier 0 classify/route | `extension/background.js classify()/routeText()` (shipped) |
| DOM scan selectors / embed hosts | `extension/content-scan.js` `[NEW]` |
| Probe endpoint | embedder `main.py POST /probe` + `CaptureController` proxy `[NEW]` |
| Network capture patterns / buffer size | `extension/netcap.js MEDIA_PATTERNS / NETCAP_MAX` `[NEW]` |
| Deep-grab trigger hint | `extension/popup.js DEEP_GRAB_HINT` `[NEW]` |
| Acquire endpoint / orchestration | embedder `main.py POST /acquire` + `acquire.py` `[NEW]` |
| Agent prompt / model | host-wrapper acquire-agent; `ACQUIRE_MODEL` env `[NEW]` |
| Allowed yt-dlp args (trust boundary) | embedder `acquire.YTDLP_ALLOWED_ARGS` `[NEW]` |
| Cookie domain scope / jar TTL | `netcap.cookieDomainsFor()` / `acquire.COOKIEJAR_TTL` `[NEW]` |
| yt-dlp invocation | `download/downloader.py` (shipped; extend for headers/cookies) |
| New permissions | `manifest.json` (shared, both browsers) → `webRequest`, `cookies` |
| Dispatch routing | `background.routeText()` (shipped, unchanged) |
