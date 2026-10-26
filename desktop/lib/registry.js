// POST /v1/claim. Maps registry errors to user-facing messages.
const C = require('./config');
async function claim(name, email, { fetchImpl = fetch, base = C.REGISTRY_URL } = {}) {
  let r;
  try {
    r = await fetchImpl(`${base}/v1/claim`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name, email }) });
  } catch (e) { throw new Error('Cannot reach the registry - check your internet connection.'); }
  if (r.status === 409) throw new Error('That name is taken - pick another.');
  if (r.status === 429) throw new Error('Too many claims from this network today - try tomorrow.');
  if (r.status === 400) throw new Error('The registry rejected that name (reserved or invalid).');
  if (r.status !== 201) throw new Error(`Registry error (HTTP ${r.status}).`);
  const j = await r.json();
  for (const k of ['name', 'hostname', 'tunnelToken', 'installSecret']) if (!j[k]) throw new Error(`Registry response missing ${k}`);
  return j;
}
module.exports = { claim };
