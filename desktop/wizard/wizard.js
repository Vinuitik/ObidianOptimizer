const $ = (id) => document.getElementById(id);
const KEYS = ['GROQ_API_KEY', 'GEMINI_API_KEY', 'MISTRAL_API_KEY'];

document.querySelectorAll('[data-link]').forEach((a) => a.addEventListener('click', () => window.oo.openExternal(a.dataset.link)));
$('name').addEventListener('input', () => { $('host').textContent = `${$('name').value || 'yourname'}.obsidianoptimizer.uk`; });
$('pick').addEventListener('click', async () => { const p = await window.oo.pickFolder(); if (p) $('vaultPath').value = p; });
window.oo.onStatus((m) => { $('log').hidden = false; $('log').textContent += m + '\n'; });

(async () => {
  const d = await window.oo.defaults();
  $('vaultPath').value = d.vaultPath;
  $('macnote').hidden = d.platform !== 'darwin';
})();

$('f').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  ['name', 'email', 'password', 'vaultPath', 'form'].forEach((k) => { $('e-' + k).textContent = ''; });
  $('go').disabled = true;
  const keys = {}; KEYS.forEach((k) => { keys[k] = $(k).value; });
  const r = await window.oo.install({ name: $('name').value.trim().toLowerCase(), email: $('email').value.trim(), password: $('password').value, vaultPath: $('vaultPath').value, keys });
  if (!r.ok) {
    $('go').disabled = false;
    if (r.fields) Object.entries(r.fields).forEach(([k, v]) => { $('e-' + k).textContent = v; });
    else $('e-form').textContent = r.error;
  }
  // on success main process navigates the window to the status page / app
});
