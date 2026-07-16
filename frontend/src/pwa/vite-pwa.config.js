// OPTIONAL upgrade path — not active until you install the plugin and merge this.
//
// The current setup ships a hand-written public/sw.js (no build dependency). When
// you want Workbox-generated precaching of hashed assets, do:
//
//   1.  cd frontend && npm i -D vite-plugin-pwa
//   2.  In vite.config.js:
//         import { VitePWA } from 'vite-plugin-pwa';
//         import { pwaOptions } from './src/pwa/vite-pwa.config.js';
//         plugins: [react(), VitePWA(pwaOptions)]
//   3.  Move the share-target logic from public/sw.js into a src/sw.js that the
//       plugin injects (injectManifest strategy below requires a custom SW source).
//
// Until then this file is inert documentation + config.

export const pwaOptions = {
  strategies: 'injectManifest', // custom SW needed for the share-target POST handler
  srcDir: 'src',
  filename: 'sw.js',
  registerType: 'autoUpdate',
  injectRegister: false, // we register manually via src/pwa/registerSW.js
  manifest: {
    name: 'Obsidian Optimizer',
    short_name: 'ObsOpt',
    description: 'Offline review + capture for your Obsidian vault.',
    id: '/',
    start_url: '/',
    scope: '/',
    display: 'standalone',
    orientation: 'any',
    background_color: '#0f1115',
    theme_color: '#0f1115',
    icons: [
      { src: '/icons/icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
      { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
      { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: '/icons/icon-maskable.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'maskable' },
      { src: '/icons/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
    share_target: {
      action: '/share-target',
      method: 'POST',
      enctype: 'multipart/form-data',
      params: { title: 'title', text: 'text', url: 'url' },
    },
  },
  injectManifest: {
    globPatterns: ['**/*.{js,css,html,svg,woff2}'],
  },
};
