// Run: node --test extension/config.test.mjs   (no deps; config.js is DOM-free)
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  normalizeServerUrl, baseOfApi, originPattern, isStaticallyCovered,
  isAccessRedirect, resolveName, apiOf,
} from './config.js';

test('normalizeServerUrl', () => {
  const n = normalizeServerUrl;
  assert.equal(n('https://alice.obsidianoptimizer.uk'), 'https://alice.obsidianoptimizer.uk');
  assert.equal(n('  alice.obsidianoptimizer.uk/ '), 'https://alice.obsidianoptimizer.uk');
  assert.equal(n('https://x.example.com/api/'), 'https://x.example.com');
  assert.equal(n('localhost:8082'), 'http://localhost:8082');
  assert.equal(n('http://127.0.0.1:8081/api'), 'http://127.0.0.1:8081');
  assert.equal(n('http://evil.example.com'), null);   // plain http only for loopback
  assert.equal(n('ftp://x.com'), null);
  assert.equal(n('https://u:p@x.com'), null);
  assert.equal(n(''), null);
  assert.equal(n(undefined), null);
});

test('api/base helpers', () => {
  assert.equal(apiOf('https://a.b'), 'https://a.b/api');
  assert.equal(baseOfApi('https://a.b/api/'), 'https://a.b');
  assert.equal(baseOfApi('https://a.b'), 'https://a.b');
  assert.equal(originPattern('http://localhost:8082'), 'http://localhost/*');
});

test('static coverage', () => {
  assert.ok(isStaticallyCovered('https://alice.obsidianoptimizer.uk'));
  assert.ok(isStaticallyCovered('http://localhost:8082'));
  assert.ok(!isStaticallyCovered('https://my.server.net'));
  assert.ok(!isStaticallyCovered('https://obsidianoptimizer.uk.evil.com'));
});

test('isAccessRedirect', () => {
  assert.ok(isAccessRedirect({ url: 'https://team.cloudflareaccess.com/cdn-cgi/access/login/x' }));
  assert.ok(!isAccessRedirect({ url: 'https://alice.obsidianoptimizer.uk/api/me' }));
  assert.ok(!isAccessRedirect(null));
});

test('resolveServerInput', async () => {
  const { resolveServerInput } = await import('./config.js');
  const f = async () => ({ ok: true, status: 200, json: async () => ({ hostname: 'alice.obsidianoptimizer.uk' }) });
  assert.deepEqual(await resolveServerInput('alice', f), { ok: true, base: 'https://alice.obsidianoptimizer.uk' });
  assert.deepEqual(await resolveServerInput('localhost:8082'), { ok: true, base: 'http://localhost:8082' });
  assert.equal((await resolveServerInput('http://evil.com')).ok, false);
  assert.equal((await resolveServerInput('')).ok, false);
});

test('resolveName', async () => {
  const ok = async (u) => { assert.match(u, /registry\.obsidianoptimizer\.uk\/v1\/resolve\?name=alice$/);
    return { ok: true, status: 200, json: async () => ({ hostname: 'alice.obsidianoptimizer.uk' }) }; };
  assert.equal(await resolveName(' Alice ', ok), 'https://alice.obsidianoptimizer.uk');
  await assert.rejects(resolveName('a', ok), { code: 'bad-name' });
  await assert.rejects(resolveName('bob', async () => ({ ok: false, status: 404 })), { code: 'not-found' });
  await assert.rejects(resolveName('bob', async () => ({ ok: false, status: 500 })), { code: 'registry' });
  await assert.rejects(resolveName('bob', async () => { throw new Error('net'); }), { code: 'registry' });
  await assert.rejects(resolveName('bob', async () => ({ ok: true, status: 200, json: async () => ({ hostname: 'evil.com' }) })), { code: 'registry' });
});
