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
- **Server**: your site name (e.g. `alice` → `https://alice.obsidianoptimizer.uk`, looked up
  via the registry) or a full URL (same-machine dev: `http://localhost:8082`). There is no
  default server; the welcome tab asks for it.
- **Sign in** with your app credentials.

## Tests
`node --test extension/config.test.mjs` (URL normalization, registry resolve, Access detection).
Exclude `config.test.mjs` and `manifest.firefox.overlay.json` from store zips.

## Store-readiness checklist (nothing here is published yet)

### Both stores
- [ ] Bump `version` (`manifest.json` for Chrome; overlay `version` is the separate AMO counter).
- [ ] 128px icon present (`icons/`), 1280x800 screenshots (popup, onboarding, Learn result).
- [ ] Short + long description (below), support contact/URL, privacy policy URL (draft below; host it at e.g. `https://obsidianoptimizer.uk/privacy`).
- [ ] Verify against a live user subdomain behind Access: sign-in, capture, "Open <host> once" flow.

### AMO (Firefox, listed)
- [ ] Remove `browser_specific_settings.gecko.update_url` for the AMO build (AMO-hosted add-ons must not self-update; keep it only for the self-hosted channel). Keep `id` stable.
- [ ] Raise `strict_min_version` to >= 128 (`optional_host_permissions` support); currently 121 in the overlay.
- [ ] Submit source zip is NOT needed (no bundler/minification), but note "vanilla ES modules, no build" in reviewer notes; mention `jq` merge via `linux_scripts/build-firefox-extension.sh`.
- [ ] `data_collection_permissions` declaration (Mozilla's new manifest key): website content / authentication info, sent to the user's own server only.
- [ ] Reviewer note: a test server + credentials are required to exercise the extension; provide a demo name or custom-URL instructions.

### Chrome Web Store
- [ ] Developer account ($5), single-purpose statement: "Send web pages, links, text and files to the user's own self-hosted Obsidian Optimizer Learn queue."
- [ ] Privacy policy URL; data-use disclosures (website content, authentication info; not sold, not used for unrelated purposes, no remote code).
- [ ] Permission justifications (paste into the dashboard):
  - `activeTab` + `scripting`: read the visible text/video embeds of the page the user explicitly captures (toolbar click or context menu).
  - `tabs`: read the URL/title of the active tab to pre-fill capture, and match open tabs for the optional escalation agent.
  - `contextMenus`: "Send selection/link/page to Learn" right-click items.
  - `storage`: save the user's server address, optional remembered sign-in, and a short activity log locally.
  - Host `https://*.obsidianoptimizer.uk/*`: talk to the user's own server and the name registry (`registry.obsidianoptimizer.uk`).
  - Host `http://localhost/*`, `http://127.0.0.1/*`: self-hosters and developers running the server on their own machine.
  - Host `https://drive.usercontent.google.com/*`: download a Google Drive file the user chose to capture, with their own Google session.
  - Optional hosts `https://*/*`, `http://*/*`: requested at runtime only (a) for a custom server URL the user types, (b) if the user enables the optional escalation agent, which reads a tab the user already has open.

### Privacy policy draft
The extension sends only what you capture (page text, URL, selected text, links, uploaded files) and your sign-in credentials to the server address you configured, which is your own self-hosted instance. The only third-party request is a lookup of your site name at registry.obsidianoptimizer.uk (sends the name you typed) and, for Drive links, to drive.usercontent.google.com. Settings and, if you tick "Stay signed in", your credentials are stored in the browser's local extension storage and never leave your device except to your server. No analytics, advertising, or sale or sharing of data. Contact: <owner email>.

See `FLOWS.md` for architecture, routing, and the full constraints list.
