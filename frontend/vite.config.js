import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import fs from 'node:fs';
import path from 'node:path';

// Stamp the hashed entry-bundle filename into dist/sw.js at build time. sw.js lives in
// public/ and is copied verbatim, so its bytes otherwise never change across deploys — and
// the browser only detects a "new" service worker when sw.js's bytes change. Without this,
// the in-app "Update available" prompt (registerSW.js controllerchange → RefreshButton)
// never fires for a normal JS-only deploy. Using the content-hashed entry name means the
// stamp changes exactly when the code changes (an identical rebuild stays identical → no
// spurious update prompts on a plain restart).
function swBuildStamp() {
  let buildId = '';
  return {
    name: 'sw-build-stamp',
    apply: 'build',
    generateBundle(_options, bundle) {
      const entry = Object.values(bundle).find((f) => f.type === 'chunk' && f.isEntry);
      buildId = entry?.fileName || String(Date.now());
    },
    closeBundle() {
      const swPath = path.resolve(process.cwd(), 'dist/sw.js');
      if (!fs.existsSync(swPath)) return;
      const src = fs.readFileSync(swPath, 'utf8');
      fs.writeFileSync(swPath, src.replaceAll('__BUILD_ID__', buildId));
    },
  };
}

export default defineConfig({
  plugins: [react(), swBuildStamp()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
        rewrite: path => path.replace(/^\/api/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.js'],
    globals: true,
  },
});
