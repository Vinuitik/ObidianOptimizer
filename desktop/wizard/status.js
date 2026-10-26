const $ = (id) => document.getElementById(id);
document.querySelectorAll('[data-link]').forEach((a) => a.addEventListener('click', () => window.oo.openExternal(a.dataset.link)));
$('retry').addEventListener('click', () => window.oo.recheckDocker());
// m = { title, msg, docker?: bool, line?: string }
window.oo.onStatus((m) => {
  if (typeof m === 'string') m = { line: m };
  if (m.title) $('title').textContent = m.title;
  if (m.msg !== undefined) $('msg').textContent = m.msg;
  if (m.docker !== undefined) $('docker').hidden = !m.docker;
  if (m.line) { $('log').hidden = false; $('log').textContent += m.line + '\n'; $('log').scrollTop = 1e9; }
});
