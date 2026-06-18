// Endpoint config — editable in the popup's Settings tab, persisted in
// chrome.storage.local. Shared by background.js and popup.js.
//
// obsidianApi: the ObsidianOptimizer backend, reached via nginx (the `/api` base,
//   same paths the web app uses). Default is the Cloudflare tunnel domain because
//   the local :8443 cert is self-signed and the extension can't bypass cert errors.
//   For same-machine dev against the Vite proxy, set it to http://localhost:8082.
//
// Both features (clip note AND download) go through this one backend: the yt-dlp
// downloader now lives in the embedder and is proxied at /download (the embedder
// is loopback-only, so the extension can't call it directly).

// Cross-browser WebExtension API handle. Firefox exposes the promise-based
// `browser.*` namespace; Chrome/Edge/Brave expose `chrome.*` (also promise-based
// under MV3). Picking `browser ?? chrome` means every `await api.*` call below
// returns a real promise in BOTH engines — no polyfill needed.
export const api = globalThis.browser ?? globalThis.chrome;

export const DEFAULTS = {
  obsidianApi: 'https://obsidianoptimizer.uk/api',
};

export async function getConfig() {
  const stored = await api.storage.local.get(['obsidianApi']);
  return { ...DEFAULTS, ...stored };
}

export async function setConfig(patch) {
  await api.storage.local.set(patch);
}
