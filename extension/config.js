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

export const DEFAULTS = {
  obsidianApi: 'https://obsidianoptimizer.uk/api',
};

export async function getConfig() {
  const stored = await chrome.storage.local.get(['obsidianApi']);
  return { ...DEFAULTS, ...stored };
}

export async function setConfig(patch) {
  await chrome.storage.local.set(patch);
}
