// Impure glue: data dir files, manifest download, docker compose calls. Logic is in the pure modules.
// state.json { name, hostname, installSecret, version, gpu }  (gpu is an extra field: detected at install)
const fs = require('fs');
const path = require('path');
const C = require('./config');
const { dataDir } = require('./paths');
const compose = require('./compose');
const docker = require('./docker');
const envgen = require('./envgen');
const registry = require('./registry');
const { runUpdate, waitHealthy } = require('./updater');

const dir = () => dataDir();
const f = (n) => path.join(dir(), n);
const prevDir = () => f('prev');

const readState = () => { try { return JSON.parse(fs.readFileSync(f('state.json'), 'utf8')); } catch { return null; } };
const writeState = (s) => fs.writeFileSync(f('state.json'), JSON.stringify(s, null, 2));
const isInstalled = () => !!readState() && fs.existsSync(f('.env')) && fs.existsSync(f('compose.yml'));
const readEnv = () => fs.readFileSync(f('.env'), 'utf8');
const port = () => envgen.getEnvVar(readEnv(), 'OO_LOCAL_PORT') || String(C.DEFAULT_PORT);
const localUrl = () => `http://127.0.0.1:${port()}`;
const opts = () => ({ gpu: !!(readState() || {}).gpu });

async function fetchManifest() {
  const r = await fetch(C.MANIFEST_URL);
  if (!r.ok) throw new Error(`manifest fetch failed (HTTP ${r.status})`);
  return r.json();
}
async function download(manifest, name, dest) {
  const url = /^https?:/.test(name) ? name : `${C.RELEASE_BASE(manifest.version)}/${name}`;
  const r = await fetch(url);
  if (!r.ok) throw new Error(`download ${name} failed (HTTP ${r.status})`);
  fs.writeFileSync(dest, Buffer.from(await r.arrayBuffer()));
}
async function writeComposeFiles(manifest, gpu) {
  await download(manifest, manifest.compose || 'compose.yml', f('compose.yml'));
  if (gpu) await download(manifest, manifest.composeGpu || 'compose.gpu.yml', f('compose.gpu.yml'));
}

// First-run install: claim -> manifest -> compose files -> .env -> state.json. Does not start.
async function install(input, log = () => {}) {
  const errors = envgen.validateInputs(input);
  if (Object.keys(errors).length) { const e = new Error('Invalid input'); e.fields = errors; throw e; }
  fs.mkdirSync(dir(), { recursive: true });
  log('Claiming your address...');
  const claim = await registry.claim(input.name, input.email);
  log('Fetching latest release...');
  const manifest = await fetchManifest();
  const gpu = await docker.detectNvidia();
  await writeComposeFiles(manifest, gpu);
  fs.mkdirSync(input.vaultPath, { recursive: true });
  fs.writeFileSync(f('.env'), envgen.buildEnv(input, claim, { version: manifest.version }), { mode: 0o600 });
  writeState({ name: claim.name, hostname: claim.hostname, installSecret: claim.installSecret, version: manifest.version, gpu });
  return { hostname: claim.hostname, gpu };
}

const up = (o) => docker.runCompose(compose.cmd.up(dir(), opts()), o);
const stop = (o) => docker.runCompose(compose.cmd.stop(dir(), opts()), o);
const pull = (o) => docker.runCompose(compose.cmd.pull(dir(), opts()), o);

async function isOnline() {
  const r = await docker.run('docker', compose.cmd.ps(dir(), opts()));
  return r.ok && compose.isRunning(compose.parsePs(r.stdout));
}
// Healthy = frontend answers on the local port (any non-5xx; /api/health or /login per plan).
async function probeHealth() {
  for (const p of ['/api/health', '/login']) {
    try { const r = await fetch(localUrl() + p, { signal: AbortSignal.timeout(4000) }); if (r.ok) return true; } catch { /* next */ }
  }
  return false;
}
const waitUntilHealthy = (o) => waitHealthy(probeHealth, o);

async function update(log = () => {}) {
  const deps = {
    log,
    currentVersion: async () => readState().version,
    fetchManifest,
    snapshot: async () => {
      fs.mkdirSync(prevDir(), { recursive: true });
      for (const n of ['compose.yml', 'compose.gpu.yml', '.env', 'state.json']) if (fs.existsSync(f(n))) fs.copyFileSync(f(n), path.join(prevDir(), n));
    },
    backup: async () => {
      fs.mkdirSync(f('backups'), { recursive: true });
      const out = f(`backups/db-${new Date().toISOString().replace(/[:.]/g, '-')}.sql`);
      await docker.runCompose(compose.cmd.dump(dir(), opts()), { outFile: out });
      if (fs.statSync(out).size < 100) throw new Error('database dump is empty'); // stack not running -> refuse
    },
    applyManifest: async (m) => {
      await writeComposeFiles(m, opts().gpu);
      fs.writeFileSync(f('.env'), envgen.setEnvVar(readEnv(), 'OO_VERSION', m.version), { mode: 0o600 });
    },
    pull: () => pull({ onLine: log }),
    up: () => up({ onLine: log }),
    health: () => waitUntilHealthy(),
    restoreSnapshot: async () => {
      for (const n of ['compose.yml', 'compose.gpu.yml', '.env', 'state.json']) if (fs.existsSync(path.join(prevDir(), n))) fs.copyFileSync(path.join(prevDir(), n), f(n));
    },
    saveVersion: async (v) => writeState({ ...readState(), version: v }),
  };
  return runUpdate(deps, C.LAUNCHER_VERSION);
}

module.exports = { dir, readState, isInstalled, localUrl, install, up, stop, isOnline, probeHealth, waitUntilHealthy, update };
