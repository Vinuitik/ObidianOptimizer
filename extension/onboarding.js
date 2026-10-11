// Inline sign-in for the welcome tab. Reuses background.js's existing 'login' message
// (MV3 CSP forbids inline scripts, hence a separate file).
import { setCreds, api } from './config.js';

const st = document.getElementById('ob-status');
const say = (m, k) => { st.textContent = m; st.className = k; };

document.getElementById('ob-login').addEventListener('submit', async (e) => {
  e.preventDefault();
  const username = document.getElementById('ob-user').value.trim();
  const password = document.getElementById('ob-pass').value;
  if (!username || !password) return say('Enter username and password.', 'err');
  say('Signing in…', '');
  const res = await api.runtime.sendMessage({ type: 'login', payload: { username, password } });
  if (res?.ok) { await setCreds(username, password); say('Signed in. You can close this tab and use the popup.', 'ok'); }
  else if (res?.status === 401) say('Wrong username or password.', 'err');
  else say('Can’t reach the server. Check your connection and try again.', 'err');
});
