# Browser Extension Architecture

## Overview
A Manifest V3 browser extension for Chrome and Firefox that injects a real-time note-taking UI natively onto any webpage. Designed to reduce friction between consuming information and storing it.

## Tech Stack
*   **Build Tool**: Vite + `@crxjs/vite-plugin` (Compiles React directly to browser extension formats).
*   **UI Framework**: React (Reusing existing components: `NewNoteForm`, `FolderTree`, `MilkdownEditor`).
*   **Isolation**: Shadow DOM (Ensures host webpage CSS doesn't break our UI).

## Core Architecture

### 1. The Content Script (The UI)
This script is injected into every webpage the user visits. 
*   It mounts a React application inside a custom HTML element (`<obsidian-optimizer-overlay>`).
*   A **Shadow DOM** is used to encapsulate the React components so that global styles from the current website (like Wikipedia or YouTube) do not bleed into our note-taking popup.
*   Provides a floating action button that expands into the full `NewNoteForm` UI.

### 2. The Background Script (Service Worker)
Runs invisibly in the browser background.
*   **API & CORS**: Content scripts are subject to the same CORS rules as the host webpage. To bypass this, the content script sends a message to the Background Script, which then securely communicates with the Spring Boot API (`http://localhost:8080` or production URL).
*   **State & Auth**: Holds JWT tokens or session state. Handles the actual `POST` requests to save notes.

### 3. Component Decoupling
*   To reuse components from `frontend/src/`, the API communication layer needs to be environment-aware.
*   When running as an extension, components will dispatch messages (`chrome.runtime.sendMessage`) to the Service Worker instead of making direct `fetch` calls, or the base URL must be absolute and the Background Script handles the proxying.

## Development Flow
1. Scaffold an `extension/` folder using Vite.
2. Link the shared UI components.
3. Build the Shadow DOM injector.
4. Wire up the messaging between the overlay UI and the Background Service Worker.
5. Send payloads to the main Backend API.

---

## Packaging & Distribution (cross-browser) — [NOT IMPLEMENTED, packaging only]

The shipped extension is the **vanilla** one in `extension/` (no React build), and it
already runs in both Chromium and Firefox. The only per-browser divergence is the
**manifest background declaration**: Chrome MV3 requires `background.service_worker`,
Firefox MV3 requires `background.scripts`. One `manifest.json` cannot satisfy both,
and Firefox always loads the file literally named `manifest.json`.

Current state (dev): `extension/manifest.json` (Chrome) + `extension/manifest.firefox.json`,
shared JS via the `browser ?? chrome` shim in `config.js`, and
`build-firefox-extension.ps1` generates `extension-firefox/` (gitignored) on demand.

**Packaging requirement (the rule for whoever builds the distributable):**
- The end user must NEVER run a script or compile anything. Setup friction is a
  non-starter — they should download, unzip, and **point the browser at a ready
  folder** (or install a signed package).
- So the **build/release step** (CI or a release script — NOT the user) must emit
  two ready-to-load artifacts from the single `extension/` source:
  - `obsidian-optimizer-chrome/` — `manifest.json` = the service-worker manifest.
  - `obsidian-optimizer-firefox/` — `manifest.json` = the event-page manifest
    (today's `manifest.firefox.json`), produced exactly as
    `build-firefox-extension.ps1` does the copy+swap.
- Both bundles ship the identical shared JS. The generation is a copy + manifest
  rename — never a code compile.
- Proper end-state: publish to the **Chrome Web Store** and **AMO (addons.mozilla.org)**
  so users install with one click and the per-browser manifest is handled at upload
  time. The two-folder generation is the interim "load unpacked" path until then.

To change the per-browser manifest: `extension/manifest.json` (Chrome) /
`extension/manifest.firefox.json` (Firefox). To change the generation:
`build-firefox-extension.ps1` (generalize it into the release build to emit both
folders).