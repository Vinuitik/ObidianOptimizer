// Shared by styleGuard.test.js and the allowlist generator.
// Returns the list of rule violations found in a CSS string.
export function violations(css) {
  const text = css
    .replace(/\/\*[\s\S]*?\*\//g, '')       // comments
    .replace(/url\([^)]*\)/g, 'url()');      // data-URIs / ids
  const found = [];
  // Only declaration-ish lines (contain ':' and don't open a rule) so `#id {` selectors are ignored.
  const decls = text.split('\n').filter(l => l.includes(':') && !l.trim().endsWith('{')).join('\n');
  if (/(?:^|[^\w&-])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\b/.test(decls)) found.push('hex colour');
  if (/\b(?:rgb|rgba|hsl|hsla)\s*\(/i.test(text)) found.push('rgb/hsl colour');
  for (const m of text.matchAll(/font-family\s*:\s*([^;}]+)/g)) {
    if (!/^\s*var\(--[\w-]+\)\s*(,\s*[\w-]+\s*)*$/.test(m[1])) { found.push('font-family literal'); break; }
  }
  return found;
}
