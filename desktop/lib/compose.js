// Pure: argument builders + output parsers for `docker compose`.
const path = require('path');
const C = require('./config');

function composeArgs(dir, { gpu = false } = {}, ...sub) {
  const args = ['compose', '-p', C.PROJECT, '-f', path.join(dir, 'compose.yml')];
  if (gpu) args.push('-f', path.join(dir, 'compose.gpu.yml'));
  args.push('--env-file', path.join(dir, '.env'), ...sub);
  return args;
}
const cmd = {
  pull: (d, o) => composeArgs(d, o, 'pull'),
  up: (d, o) => composeArgs(d, o, 'up', '-d'),
  stop: (d, o) => composeArgs(d, o, 'stop'),
  ps: (d, o) => composeArgs(d, o, 'ps', '--format', 'json'),
  // DB backup: pg_dump inside the postgres container (stdout captured by caller).
  // ASSUMPTION: service named `postgres`, db/user `obsidian` (root docker-compose.yml).
  dump: (d, o) => composeArgs(d, o, 'exec', '-T', 'postgres', 'pg_dump', '-U', 'obsidian', '-d', 'obsidian'),
};

// `ps --format json` is NDJSON on compose >=2.21, a JSON array on older v2. Accept both.
function parsePs(out) {
  const t = (out || '').trim();
  if (!t) return [];
  if (t.startsWith('[')) return JSON.parse(t);
  return t.split('\n').filter(Boolean).map((l) => JSON.parse(l));
}
const isRunning = (services) => services.length > 0 && services.some((s) => s.State === 'running');
const parseComposeVersion = (out) => { const m = /v?(\d+)\.(\d+)\.(\d+)/.exec(out || ''); return m ? m.slice(1, 4).map(Number) : null; };
const isComposeV2 = (out) => { const v = parseComposeVersion(out); return !!v && v[0] >= 2; };
const hasNvidia = (out) => /GPU \d+:/i.test(out || '');

module.exports = { composeArgs, cmd, parsePs, isRunning, isComposeV2, hasNvidia, parseComposeVersion };
