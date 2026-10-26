// Welcome tab: (1) connect a server, (2) inline sign-in. Reuses background.js's existing
// 'login' message (MV3 CSP forbids inline scripts, hence a separate file).
import { getConfig, isConfigured, baseOfApi, resolveServerInput, saveServer, setCreds, api } from './config.js';

const say = (id, m, k) => { const el = document.getElementById(id); el.textContent = m; el.className = k; };
const st = (m, k) => say('ob-status', m, k);

// Pre-fill when already configured (e.g. migrated installs reopening this page).
getConfig().then((cfg) => {
  if (isConfigured(cfg)) {
    document.getElementById('ob-name').value = baseOfApi(cfg.obsidianApi);
    say('ob-server-status', 'Connected. Change it above if needed.', 'ok');
  }
});

document.getElementById('ob-server').addEventListener('submit', async (e) => {
  e.preventDefault();
  say('ob-server-status', 'Looking up…', '');
  // Permission prompt (custom URLs) needs the submit gesture: keep awaits before it minimal.
  const srv = await resolveServerInput(document.getElementById('ob-name').value);
  if (!srv.ok) return say('ob-server-status', srv.error, 'err');
  const saved = await saveServer(srv.base);
  if (!saved.ok) return say('ob-server-status', saved.error, 'err');
  say('ob-server-status', `Connected to ${new URL(srv.base).host}.`, 'ok');
});

document.getElementById('ob-login').addEventListener('submit', async (e) => {
  e.preventDefault();
  const username = document.getElementById('ob-user').value.trim();
  const password = document.getElementById('ob-pass').value;
  if (!username || !password) return st('Enter username and password.', 'err');
  st('Signing in…', '');
  const res = await api.runtime.sendMessage({ type: 'login', payload: { username, password } });
  if (res?.ok) { await setCreds(username, password); st('Signed in. You can close this tab and use the popup.', 'ok'); }
  else if (res?.needsSetup) st('Connect your server first (step 2).', 'err');
  else if (res?.accessRequired) st(`Open ${res.host} in a tab once, enter the email code, then try again.`, 'err');
  else if (res?.status === 401) st('Wrong username or password.', 'err');
  else st('Can’t reach the server. Check your connection and try again.', 'err');
});
