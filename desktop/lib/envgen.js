// Pure: wizard input + registry claim -> .env text. No I/O (randomness injected for tests).
const crypto = require('crypto');
const C = require('./config');

const defaultRand = (bytes) => crypto.randomBytes(bytes).toString('hex');

function validateInputs(i) {
  const errors = {};
  if (!C.NAME_RE.test(i.name || '')) errors.name = 'Name: 3-31 chars, lowercase letters/digits/hyphens, start with a letter';
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(i.email || '')) errors.email = 'Enter a valid email';
  if ((i.password || '').length < C.MIN_PASSWORD) errors.password = `Password must be at least ${C.MIN_PASSWORD} characters`;
  else if (/[\r\n']/.test(i.password)) errors.password = "No line breaks or single quotes allowed";
  if (!i.vaultPath) errors.vaultPath = 'Choose a vault folder';
  else if (/[\r\n']/.test(i.vaultPath)) errors.vaultPath = "No line breaks or single quotes allowed";
  return errors;
}

// compose --env-file: quote values with spaces/#/$ in single quotes (no interpolation);
// single quotes inside are unsupported (validated above).
function quote(v) {
  v = String(v ?? '');
  if (v === '') return '';
  if (/^[A-Za-z0-9_./:@+,-]+$/.test(v)) return v;
  if (v.includes("'")) throw new Error('value contains a single quote, unsupported in .env');
  return `'${v}'`;
}

// Forward slashes even on Windows (compose bind path), same as .env.example.
const toComposePath = (p) => p.replace(/\\/g, '/');

function buildEnv(input, claim, { version, rand = defaultRand } = {}) {
  const v = {
    ...C.DEFAULTS,
    OO_VERSION: version,
    HOST_VAULT_PATH: toComposePath(input.vaultPath),
    APP_AUTH_PASSWORD: input.password,
    CLOUDFLARE_TUNNEL_TOKEN: claim.tunnelToken,
    MCP_ALLOWED_HOSTS: claim.hostname,
    GOOGLE_OAUTH_REDIRECT_URI: 'https://registry.obsidianoptimizer.uk/oauth/google/callback',
  };
  for (const [k, n] of Object.entries(C.GENERATED)) v[k] = rand(n);
  for (const k of Object.keys(C.KEY_LINKS)) v[k] = ((input.keys && input.keys[k]) || '').trim();
  return C.ENV_VARS.map((k) => `${k}=${quote(v[k])}`).join('\n') + '\n';
}

// Replace/append one KEY=value line (update flow bumps OO_VERSION).
function setEnvVar(text, key, value) {
  const line = `${key}=${quote(value)}`;
  const re = new RegExp(`^${key}=.*$`, 'm');
  return re.test(text) ? text.replace(re, line) : text.replace(/\n?$/, '\n') + line + '\n';
}
function getEnvVar(text, key) {
  const m = text.match(new RegExp(`^${key}=(.*)$`, 'm'));
  return m ? m[1].replace(/^'(.*)'$/, '$1') : undefined;
}
module.exports = { validateInputs, buildEnv, setEnvVar, getEnvVar, quote };
