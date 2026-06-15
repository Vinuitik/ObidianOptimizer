// Endpoint config — editable in the popup's Settings tab, persisted in
// chrome.storage.local. Shared by background.js and popup.js.
//
// obsidianApi: the ObsidianOptimizer backend, reached via nginx (the `/api` base,
//   same paths the web app uses). Default is the Cloudflare tunnel domain because
//   the local :8443 cert is self-signed and the extension can't bypass cert errors.
//   For same-machine dev against the Vite proxy, set it to http://localhost:8082.
// videoApi: the VideoManager LITE downloader (docker-compose.lite.yml → :8001).

export const DEFAULTS = {
  obsidianApi: 'https://obsidianoptimizer.uk/api',
  videoApi: 'http://localhost:8001',
};

export async function getConfig() {
  const stored = await chrome.storage.local.get(['obsidianApi', 'videoApi']);
  return { ...DEFAULTS, ...stored };
}

export async function setConfig(patch) {
  await chrome.storage.local.set(patch);
}
