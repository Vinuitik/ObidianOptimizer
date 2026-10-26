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
// POST /v1/rotate -> { tunnelToken }. 403/401 = name owned by another install.
async function rotate(name, installSecret, { fetchImpl = fetch, base = C.REGISTRY_URL } = {}) {
  let r;
  try {
    r = await fetchImpl(`${base}/v1/rotate`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name, installSecret }) });
  } catch (e) { throw new Error('Cannot reach the registry - check your internet connection.'); }
  if (r.status === 403 || r.status === 401) throw new Error('That name is owned by another install - pick a different name.');
  if (r.status !== 200) throw new Error(`Registry error (HTTP ${r.status}).`);
  const j = await r.json();
  if (!j.tunnelToken) throw new Error('Registry response missing tunnelToken');
  return j.tunnelToken;
}

// Fresh claim, or resume via rotate when a previous run already claimed (state has name+installSecret).
// persist(partialState) runs right after a successful claim, BEFORE any docker step.
async function claimOrResume(input, state, persist, deps = {}) {
  if (state && state.name && state.installSecret) {
    const tunnelToken = await rotate(state.name, state.installSecret, deps);
    return { name: state.name, hostname: state.hostname, tunnelToken, installSecret: state.installSecret };
  }
  const c = await claim(input.name, input.email, deps);
  await persist({ name: c.name, hostname: c.hostname, installSecret: c.installSecret });
  return c;
}
module.exports = { claim, rotate, claimOrResume };
