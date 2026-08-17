# Browser Extension — Packaging & Distribution [NOT IMPLEMENTED, packaging only]

> History: this file originally planned a React/Vite/Shadow-DOM extension. That approach
> is dead — the shipped extension is deliberately **vanilla JS** (loads unpacked, zero
> build) and is fully documented in `extension/FLOWS.md`. What remains here is the one
> unimplemented piece: producing user-friendly distributables.

The shipped extension is the vanilla one in `extension/`, and it already runs in both
Chromium and Firefox. The only per-browser divergence is the **manifest background
declaration**: Chrome MV3 requires `background.service_worker`, Firefox MV3 requires
`background.scripts`. One `manifest.json` cannot satisfy both, and Firefox always loads
the file literally named `manifest.json`.

Current state (dev): `extension/manifest.json` is the single canonical manifest (shared
fields — name, icons, permissions, etc); `extension/manifest.firefox.overlay.json` holds
only the 3 fields that must differ (`version`, `background`, `browser_specific_settings`).
Shared JS via the `browser ?? chrome` shim in `config.js`; `linux_scripts/build-firefox-
extension.sh` shallow-merges the overlay onto the base manifest to generate
`extension-firefox/` on demand — see `extension/FLOWS.md` for the merge details.

**Packaging requirement (the rule for whoever builds the distributable):**
- The end user must NEVER run a script or compile anything. Setup friction is a
  non-starter — they should download, unzip, and **point the browser at a ready
  folder** (or install a signed package).
- So the **build/release step** (CI or a release script — NOT the user) must emit
  two ready-to-load artifacts from the single `extension/` source:
  - `obsidian-optimizer-chrome/` — `manifest.json` = the service-worker manifest.
  - `obsidian-optimizer-firefox/` — `manifest.json` = the base manifest with
    `manifest.firefox.overlay.json` merged in, produced exactly as the build script does.
- Both bundles ship the identical shared JS. The generation is a copy + manifest
  rename — never a code compile.
- Proper end-state: publish to the **Chrome Web Store** and **AMO (addons.mozilla.org)**
  so users install with one click and the per-browser manifest is handled at upload
  time. The two-folder generation is the interim "load unpacked" path until then.
- Before distributing: tighten `host_permissions` (currently broad so the endpoint is
  configurable — see extension/FLOWS.md Technology Notes).

To change shared manifest fields: `extension/manifest.json` (both browsers pick it up).
To change a Firefox-only field: `extension/manifest.firefox.overlay.json`. To change the
generation: `linux_scripts/build-firefox-extension.sh` (generalize into the release build
to emit both folders).
