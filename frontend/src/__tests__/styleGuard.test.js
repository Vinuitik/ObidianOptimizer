// Enforces "tokens.css is the only place a colour literal lives":
// every *.module.css outside src/styles must be free of hex / rgb(a) / hsl(a) literals and
// literal font-family declarations (font-family: var(--…) is fine), unless listed in
// styleGuard.allowlist.txt. The allowlist may only shrink — a listed file that is clean fails too.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, it, expect } from 'vitest';
import { violations } from './styleGuard.lib.js';

const SRC = join(process.cwd(), 'src');
const STYLES_DIR = join(SRC, 'styles');

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) { if (name !== 'node_modules') walk(p, out); }
    else if (name.endsWith('.module.css')) out.push(p);
  }
  return out;
}

const allowlist = new Set(
  readFileSync(join(SRC, '__tests__', 'styleGuard.allowlist.txt'), 'utf8')
    .split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#')),
);

const files = walk(SRC).filter(f => !f.startsWith(STYLES_DIR + sep));
const rel = f => relative(SRC, f).split(sep).join('/');

describe('styleGuard', () => {
  it('detector catches literals', () => {
    expect(violations('.a { color: #fff; }')).toContain('hex colour');
    expect(violations('.a { background: rgba(0,0,0,.4); }')).toContain('rgb/hsl colour');
    expect(violations(".a { font-family: 'DM Sans', sans-serif; }")).toContain('font-family literal');
    expect(violations('.a { color: var(--text); font-family: var(--font-ui); }')).toEqual([]);
    expect(violations('/* #fff */ .a { background: url("data:image/svg+xml,%23fff"); }')).toEqual([]);
  });

  it('no module.css outside styles/ uses colour or font literals (unless allowlisted)', () => {
    const bad = files
      .filter(f => !allowlist.has(rel(f)))
      .map(f => [rel(f), violations(readFileSync(f, 'utf8'))])
      .filter(([, v]) => v.length);
    expect(bad, 'Use var(--…) tokens from styles/tokens.css').toEqual([]);
  });

  it('allowlist only lists files that still offend', () => {
    const stale = [...allowlist].filter(p => {
      const f = join(SRC, p);
      let css;
      try { css = readFileSync(f, 'utf8'); } catch { return true; }
      return violations(css).length === 0;
    });
    expect(stale, 'Remove these from styleGuard.allowlist.txt').toEqual([]);
  });
});
