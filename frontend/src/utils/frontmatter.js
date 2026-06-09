// frontmatter.js — strip/restore YAML frontmatter before passing to Milkdown.
//
// Contract:
//   splitFrontmatter(raw) → { frontmatter, body }
//     frontmatter: everything from the opening --- through the closing --- plus
//                  any trailing blank lines (the separator).  Empty string if no FM.
//     body:        pure content, no leading blank lines.
//   joinFrontmatter(frontmatter, body) → raw
//     Simple concat — separator lives inside frontmatter, so this is always correct.
//
// This means body passed to Milkdown is clean, and on save we just concat.

const FM_OPEN  = /^---[ \t]*\r?\n/;
const FM_CLOSE = /\n---[ \t]*(\r?\n|$)/;

export function splitFrontmatter(raw) {
  if (!FM_OPEN.test(raw)) return { frontmatter: '', body: raw };

  const afterOpen = raw.indexOf('\n') + 1;
  const rest = raw.slice(afterOpen);
  const closeMatch = FM_CLOSE.exec(rest);
  if (!closeMatch) return { frontmatter: '', body: raw };

  // End of the closing --- delimiter
  const closeEnd = afterOpen + closeMatch.index + closeMatch[0].length;

  // Absorb blank-line separator(s) after --- into frontmatter so body is clean
  const sepMatch = /^(\r?\n)+/.exec(raw.slice(closeEnd));
  const bodyStart = closeEnd + (sepMatch ? sepMatch[0].length : 0);

  return {
    frontmatter: raw.slice(0, bodyStart),
    body: raw.slice(bodyStart),
  };
}

/** Re-attach frontmatter. Body must have no leading blank lines (as returned by splitFrontmatter). */
export function joinFrontmatter(frontmatter, body) {
  if (!frontmatter) return body;
  return frontmatter + body;
}

/**
 * Parse frontmatter string into a [{key, value}] array for display.
 * Returns [] if frontmatter is empty.
 */
export function parseFrontmatterFields(frontmatter) {
  if (!frontmatter) return [];
  const inner = frontmatter
    .replace(/^---[ \t]*\r?\n/, '')
    .replace(/\n---[ \t]*(\r?\n|$)[\s\S]*$/, ''); // strip closing --- and everything after
  return inner
    .split('\n')
    .map(line => {
      const colon = line.indexOf(':');
      if (colon === -1) return null;
      const key = line.slice(0, colon).trim();
      const value = line.slice(colon + 1).trim();
      return key ? { key, value: value || '—' } : null;
    })
    .filter(Boolean);
}
