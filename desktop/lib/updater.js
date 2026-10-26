// Update / rollback state machine. Pure orchestration: every side effect is an injected dep,
// so tests run with no Docker/network.
//
// deps: fetchManifest(), currentVersion(), snapshot() (copy compose+.env aside),
//       backup() (DB dump), applyManifest(manifest) (write compose files + bump OO_VERSION),
//       pull(), up(), health() -> bool, restoreSnapshot(), saveVersion(v), log(msg)
// Returns { status, from, to, error? } status in:
//   up-to-date | launcher-too-old | updated | rolled-back | rollback-failed | backup-failed
const V = require('./version');

async function runUpdate(deps, launcherVersion) {
  const log = deps.log || (() => {});
  const from = await deps.currentVersion();
  const manifest = await deps.fetchManifest();
  const to = manifest.version;
  if (!V.isNewer(to, from)) return { status: 'up-to-date', from, to };
  if (!V.launcherOk(manifest, launcherVersion)) return { status: 'launcher-too-old', from, to };

  log('backing up database');
  try { await deps.snapshot(); await deps.backup(); }
  catch (e) { return { status: 'backup-failed', from, to, error: String(e.message || e) }; } // never update without a backup

  try {
    log(`applying ${to}`);
    await deps.applyManifest(manifest);
    await deps.pull();
    await deps.up();
    log('health check');
    if (!(await deps.health())) throw new Error('health check failed');
    await deps.saveVersion(to);
    return { status: 'updated', from, to };
  } catch (e) {
    log(`failed (${e.message}); rolling back to ${from}`);
    try {
      await deps.restoreSnapshot();
      await deps.up();
      const ok = await deps.health();
      return { status: ok ? 'rolled-back' : 'rollback-failed', from, to, error: String(e.message || e) };
    } catch (e2) {
      return { status: 'rollback-failed', from, to, error: `${e.message}; rollback: ${e2.message}` };
    }
  }
}

// Poll check() until true or tries exhausted. sleep injected for tests.
async function waitHealthy(check, { tries = 60, delayMs = 3000, sleep = (ms) => new Promise((r) => setTimeout(r, ms)) } = {}) {
  for (let i = 0; i < tries; i++) {
    try { if (await check()) return true; } catch { /* not up yet */ }
    await sleep(delayMs);
  }
  return false;
}
module.exports = { runUpdate, waitHealthy };
