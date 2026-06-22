# Obsidian Optimizer — Browser Extension

A tiny Manifest V3 extension with **one job**: capture anything worth reviewing into
your Learn queue without opening the app.

Paste plain text / markdown, an article link, a YouTube video, or a PDF link — or drop
a PDF / audio / video file, or right-click a selection / link / page. The extension
detects the type and routes it:

- 📝 **Plain text / markdown** → saved as a note for review
- 🔗 **A web page link** → read & turned into notes (Learn → Inbox)
- 🎬 **A YouTube / video link** → downloaded to watch offline **+** notes generated
- 📎 **A PDF / audio / video link, or a dropped file** → saved to your workspace **+** notes

Everything lands in the **Learn** page: media to watch/read, and generated notes in the
**Inbox** to review, edit, and file into a folder (which enters them into FSRS review).

## Install (unpacked)

**Chrome / Edge / Brave** (Chromium, `manifest.json`, service-worker background):
1. `chrome://extensions` → enable **Developer mode**.
2. **Load unpacked** → select this `extension/` folder.
3. Reload the extension (↻) after editing files.

**Firefox** (MV3 uses an event-page background, so a different manifest):
1. From the repo root run `bash linux_scripts/build-firefox-extension.sh` → generates
   `extension-firefox/`.
2. `about:debugging#/runtime/this-firefox` → **Load Temporary Add-on** → select
   `extension-firefox/manifest.json`. (Temporary = removed on restart; re-run the
   script + reload after edits.) Needs Firefox 121+.

The JavaScript is identical for both — it uses a `browser ?? chrome` shim (`config.js`).
Only the manifest's background declaration differs.

On first install a welcome tab explains how to **pin the icon** and **sign in**. Then
click the toolbar icon → **⚙ Settings**:
- The **ObsidianOptimizer API base** defaults to the tunnel domain
  (`https://obsidianoptimizer.uk/api`) — leave it unless you're doing same-machine dev
  (then `http://localhost:8082`).
- **Sign in** with your app credentials.

## Why the tunnel domain by default
Service workers / extension fetches can't accept the local stack's **self-signed**
`:8443` cert, so the default points at the Cloudflare tunnel (real cert).

See `FLOWS.md` for architecture, routing, and the full constraints list.
