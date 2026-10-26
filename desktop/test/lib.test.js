const test = require('node:test');
const assert = require('node:assert/strict');
const { validateInputs, buildEnv, setEnvVar, getEnvVar, quote } = require('../lib/envgen');
const { composeArgs, cmd, parsePs, isRunning, isComposeV2, hasNvidia } = require('../lib/compose');
const V = require('../lib/version');
const { runUpdate, waitHealthy } = require('../lib/updater');
const { dataDir, extendedPath } = require('../lib/paths');

test('extendedPath adds mac docker dirs only on darwin, no duplicates', () => {
  assert.equal(extendedPath('linux', { PATH: '/usr/bin' }), '/usr/bin');
  const p = extendedPath('darwin', { PATH: '/usr/bin:/opt/homebrew/bin' }).split(':');
  assert.ok(p.includes('/usr/local/bin'));
  assert.equal(p.filter((x) => x === '/opt/homebrew/bin').length, 1);
});
const { claim, rotate, claimOrResume } = require('../lib/registry');

test('claimOrResume: fresh claim persists state before returning', async () => {
  const saved = [];
  const fetchImpl = async () => ({ status: 201, json: async () => clm });
  const r = await claimOrResume(good, null, async (s) => saved.push(s), { fetchImpl });
  assert.deepEqual(r, clm);
  assert.deepEqual(saved, [{ name: 'alice', hostname: clm.hostname, installSecret: 's' }]);
});

test('claimOrResume: resume via rotate skips claim, reuses installSecret', async () => {
  const urls = [];
  const fetchImpl = async (u, o) => { urls.push(u); assert.deepEqual(JSON.parse(o.body), { name: 'alice', installSecret: 's' }); return { status: 200, json: async () => ({ tunnelToken: 'fresh' }) }; };
  const state = { name: 'alice', hostname: clm.hostname, installSecret: 's' };
  const saved = [];
  const r = await claimOrResume({ ...good, name: 'ignored' }, state, async (s) => saved.push(s), { fetchImpl });
  assert.equal(r.tunnelToken, 'fresh');
  assert.equal(r.name, 'alice');
  assert.equal(urls.length, 1);
  assert.match(urls[0], /\/v1\/rotate$/);
  assert.deepEqual(saved, []);
});

test('rotate 403 -> owned by another install', async () => {
  const fetchImpl = async () => ({ status: 403, json: async () => ({}) });
  await assert.rejects(rotate('alice', 'bad', { fetchImpl }), /owned by another install/);
  await assert.rejects(claimOrResume(good, { name: 'alice', installSecret: 'bad' }, async () => {}, { fetchImpl }), /owned by another install/);
  await assert.rejects(rotate('a', 's', { fetchImpl: async () => ({ status: 500 }) }), /HTTP 500/);
});
const C = require('../lib/config');

const good = { name: 'alice', email: 'a@b.co', password: 'twelve-chars-ok', vaultPath: '/home/a/vault' };
const clm = { name: 'alice', hostname: 'alice.obsidianoptimizer.uk', tunnelToken: 'tok123', installSecret: 's' };

test('validateInputs', () => {
  assert.deepEqual(validateInputs(good), {});
  assert.ok(validateInputs({ ...good, name: 'Al' }).name);
  assert.ok(validateInputs({ ...good, name: '1abc' }).name);
  assert.ok(validateInputs({ ...good, email: 'nope' }).email);
  assert.ok(validateInputs({ ...good, password: 'short' }).password);
  assert.ok(validateInputs({ ...good, password: 'twelve-chars\nx' }).password);
  assert.ok(validateInputs({ ...good, vaultPath: '' }).vaultPath);
});

test('buildEnv: secrets generated, claim token used, keys optional', () => {
  let n = 0;
  const rand = (b) => `r${b}x${n++}`;
  const env = buildEnv({ ...good, keys: { GROQ_API_KEY: ' gsk_1 ' } }, clm, { version: '1.2.3', rand });
  for (const k of ['MCP_API_TOKEN', 'APP_AUTH_REMEMBER_KEY', 'POSTGRES_PASSWORD', 'SYNC_PASSPHRASE']) assert.match(env, new RegExp(`^${k}=r\\d+x\\d+$`, 'm'));
  assert.equal(getEnvVar(env, 'CLOUDFLARE_TUNNEL_TOKEN'), 'tok123');
  assert.equal(getEnvVar(env, 'OO_VERSION'), '1.2.3');
  assert.equal(getEnvVar(env, 'GROQ_API_KEY'), 'gsk_1');
  assert.equal(getEnvVar(env, 'GEMINI_API_KEY'), '');
  assert.equal(getEnvVar(env, 'MCP_ALLOWED_HOSTS'), 'alice.obsidianoptimizer.uk');
  assert.equal(getEnvVar(env, 'OO_LOCAL_PORT'), String(C.DEFAULT_PORT));
  // every declared var is present exactly once
  for (const k of C.ENV_VARS) assert.equal(env.match(new RegExp(`^${k}=`, 'mg')).length, 1, k);
});

test('buildEnv: real randomness differs per call; windows paths normalized; quoting', () => {
  const a = buildEnv(good, clm, { version: '1.0.0' }), b = buildEnv(good, clm, { version: '1.0.0' });
  assert.notEqual(getEnvVar(a, 'MCP_API_TOKEN'), getEnvVar(b, 'MCP_API_TOKEN'));
  assert.equal(getEnvVar(a, 'MCP_API_TOKEN').length, 64);
  const w = buildEnv({ ...good, vaultPath: 'C:\\Users\\Al Ice\\vault', password: 'has space #$ok!' }, clm, { version: '1.0.0' });
  assert.equal(getEnvVar(w, 'HOST_VAULT_PATH'), 'C:/Users/Al Ice/vault');
  assert.equal(getEnvVar(w, 'APP_AUTH_PASSWORD'), 'has space #$ok!');
  assert.equal(quote(''), '');
  assert.throws(() => quote("a'b"));
});

test('setEnvVar replaces or appends', () => {
  assert.equal(setEnvVar('A=1\nOO_VERSION=1.0.0\n', 'OO_VERSION', '2.0.0'), 'A=1\nOO_VERSION=2.0.0\n');
  assert.equal(setEnvVar('A=1\n', 'B', 'x'), 'A=1\nB=x\n');
});

test('composeArgs matches contract, gpu overlay optional', () => {
  assert.deepEqual(cmd.up('/d'), ['compose', '-p', 'obsopt', '-f', '/d/compose.yml', '--env-file', '/d/.env', 'up', '-d']);
  assert.deepEqual(cmd.stop('/d', { gpu: true }), ['compose', '-p', 'obsopt', '-f', '/d/compose.yml', '-f', '/d/compose.gpu.yml', '--env-file', '/d/.env', 'stop']);
  assert.deepEqual(composeArgs('/d', {}, 'pull').slice(-1), ['pull']);
  assert.deepEqual(cmd.ps('/d').slice(-3), ['ps', '--format', 'json']);
});

test('parsePs handles NDJSON, array, empty; isRunning', () => {
  assert.deepEqual(parsePs(''), []);
  assert.equal(parsePs('{"State":"running"}\n{"State":"exited"}\n').length, 2);
  assert.equal(parsePs('[{"State":"running"}]').length, 1);
  assert.ok(isRunning([{ State: 'running' }]));
  assert.ok(!isRunning([{ State: 'exited' }]));
  assert.ok(!isRunning([]));
});

test('docker/nvidia output parsing', () => {
  assert.ok(isComposeV2('Docker Compose version v2.29.1'));
  assert.ok(!isComposeV2('docker-compose version 1.29.2'));
  assert.ok(!isComposeV2('garbage'));
  assert.ok(hasNvidia('GPU 0: NVIDIA GeForce RTX 3060 (UUID: GPU-abc)'));
  assert.ok(!hasNvidia('NVIDIA-SMI has failed'));
});

test('version compare', () => {
  assert.equal(V.compare('1.2.3', 'v1.2.3'), 0);
  assert.equal(V.compare('1.10.0', '1.9.9'), 1);
  assert.equal(V.compare('0.9.0', '1.0.0'), -1);
  assert.equal(V.compare('1.0.0-rc1', '1.0.0'), -1);
  assert.ok(V.isNewer('1.0.1', '1.0.0'));
  assert.ok(!V.isNewer('1.0.0', '1.0.0'));
  assert.throws(() => V.compare('x', '1.0.0'));
  assert.ok(V.launcherOk({}, '1.0.0'));
  assert.ok(V.launcherOk({ minLauncher: '1.0.0' }, '1.0.0'));
  assert.ok(!V.launcherOk({ minLauncher: '1.1.0' }, '1.0.0'));
});

function mkDeps(over = {}) {
  const calls = [];
  const rec = (n, f = () => {}) => async (...a) => { calls.push(n); return f(...a); };
  const deps = {
    calls,
    currentVersion: async () => '1.0.0',
    fetchManifest: async () => ({ version: '1.1.0' }),
    snapshot: rec('snapshot'), backup: rec('backup'), applyManifest: rec('apply'),
    pull: rec('pull'), up: rec('up'), health: rec('health', () => true),
    restoreSnapshot: rec('restore'), saveVersion: rec('save'),
    ...over,
  };
  return deps;
}

test('update: happy path order', async () => {
  const d = mkDeps();
  const r = await runUpdate(d, '1.0.0');
  assert.deepEqual(r, { status: 'updated', from: '1.0.0', to: '1.1.0' });
  assert.deepEqual(d.calls, ['snapshot', 'backup', 'apply', 'pull', 'up', 'health', 'save']);
});

test('update: up-to-date / launcher too old do nothing', async () => {
  let d = mkDeps({ fetchManifest: async () => ({ version: '1.0.0' }) });
  assert.equal((await runUpdate(d, '1.0.0')).status, 'up-to-date');
  assert.deepEqual(d.calls, []);
  d = mkDeps({ fetchManifest: async () => ({ version: '2.0.0', minLauncher: '9.0.0' }) });
  assert.equal((await runUpdate(d, '1.0.0')).status, 'launcher-too-old');
  assert.deepEqual(d.calls, []);
});

test('update: backup failure aborts before touching anything', async () => {
  const d = mkDeps({ backup: async () => { throw new Error('dump failed'); } });
  const r = await runUpdate(d, '1.0.0');
  assert.equal(r.status, 'backup-failed');
  assert.ok(!d.calls.includes('apply'));
});

test('update: unhealthy -> rollback, version not saved', async () => {
  let h = 0;
  const d = mkDeps({ health: async () => (++h > 1) }); // fails after update, ok after rollback
  const r = await runUpdate(d, '1.0.0');
  assert.equal(r.status, 'rolled-back');
  assert.match(r.error, /health/);
  assert.ok(d.calls.includes('restore'));
  assert.ok(!d.calls.includes('save'));
});

test('update: pull failure -> rollback; rollback also unhealthy -> rollback-failed', async () => {
  let d = mkDeps({ pull: async () => { throw new Error('net'); } });
  assert.equal((await runUpdate(d, '1.0.0')).status, 'rolled-back');
  d = mkDeps({ health: async () => false });
  assert.equal((await runUpdate(d, '1.0.0')).status, 'rollback-failed');
  d = mkDeps({ up: async () => { throw new Error('boom'); } });
  const r = await runUpdate(d, '1.0.0');
  assert.equal(r.status, 'rollback-failed');
  assert.match(r.error, /rollback/);
});

test('waitHealthy retries then succeeds / times out', async () => {
  let n = 0;
  const sleep = async () => {};
  assert.ok(await waitHealthy(async () => { if (++n < 3) throw new Error('down'); return true; }, { tries: 5, sleep }));
  assert.equal(n, 3);
  assert.ok(!(await waitHealthy(async () => false, { tries: 3, sleep })));
});

test('dataDir per platform', () => {
  assert.equal(dataDir('linux', {}, '/home/u'), '/home/u/.config/ObsidianOptimizer');
  assert.equal(dataDir('linux', { XDG_CONFIG_HOME: '/x' }, '/home/u'), '/x/ObsidianOptimizer');
  assert.equal(dataDir('win32', { APPDATA: 'C:\\Users\\u\\AppData\\Roaming' }, 'C:\\Users\\u'), 'C:\\Users\\u\\AppData\\Roaming\\ObsidianOptimizer');
  assert.match(dataDir('darwin', {}, '/Users/u'), /Application Support\/ObsidianOptimizer$/);
});

test('registry claim: success + error mapping', async () => {
  const mk = (status, body) => async () => ({ status, json: async () => body });
  assert.deepEqual(await claim('alice', 'a@b.co', { fetchImpl: mk(201, clm) }), clm);
  await assert.rejects(claim('a', 'e', { fetchImpl: mk(409, {}) }), /taken/);
  await assert.rejects(claim('a', 'e', { fetchImpl: mk(429, {}) }), /Too many/);
  await assert.rejects(claim('a', 'e', { fetchImpl: mk(500, {}) }), /HTTP 500/);
  await assert.rejects(claim('a', 'e', { fetchImpl: mk(201, { name: 'x' }) }), /missing/);
  await assert.rejects(claim('a', 'e', { fetchImpl: async () => { throw new Error('x'); } }), /Cannot reach/);
});
