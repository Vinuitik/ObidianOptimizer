// Impure: runs docker / nvidia-smi. Thin on purpose; logic lives in compose.js.
const { execFile, spawn } = require('child_process');
const fs = require('fs');
const compose = require('./compose');
const { extendedPath } = require('./paths');

const childEnv = () => ({ ...process.env, PATH: extendedPath() });

const run = (bin, args, opts = {}) => new Promise((resolve) => {
  execFile(bin, args, { env: childEnv(), timeout: 30000, maxBuffer: 64 * 1024 * 1024, ...opts }, (err, stdout, stderr) =>
    resolve({ ok: !err, stdout: stdout || '', stderr: stderr || '', code: err && err.code }));
});

// -> { state: 'ok' | 'no-docker' | 'no-compose' | 'no-daemon' }
async function detectDocker() {
  const v = await run('docker', ['--version']);
  if (!v.ok) return { state: 'no-docker' };
  const c = await run('docker', ['compose', 'version']);
  if (!c.ok || !compose.isComposeV2(c.stdout)) return { state: 'no-compose' };
  const i = await run('docker', ['info', '--format', '{{.ServerVersion}}']);
  return { state: i.ok ? 'ok' : 'no-daemon' };
}
async function detectNvidia() {
  if (process.platform === 'darwin') return false; // no NVIDIA on mac: never include the GPU overlay
  const r = await run('nvidia-smi', ['-L']); return r.ok && compose.hasNvidia(r.stdout); }

// Long-running compose call (pull can take minutes): stream lines to onLine, or stdout to outFile.
function runCompose(args, { onLine, outFile } = {}) {
  return new Promise((resolve, reject) => {
    const p = spawn('docker', args, { env: childEnv(), stdio: ['ignore', 'pipe', 'pipe'] });
    const out = outFile ? fs.createWriteStream(outFile) : null;
    let tail = '';
    const feed = (d) => { tail = (tail + d).slice(-4000); if (onLine) String(d).split('\n').filter(Boolean).forEach(onLine); };
    p.stdout.on('data', (d) => (out ? out.write(d) : feed(d)));
    p.stderr.on('data', feed);
    p.on('error', reject);
    p.on('close', (code) => { if (out) out.end(); code === 0 ? resolve() : reject(new Error(`docker ${args.slice(-3).join(' ')} exited ${code}: ${tail.slice(-300)}`)); });
  });
}
module.exports = { run, detectDocker, detectNvidia, runCompose };
