// RSVP ("Rapid Serial Visual Presentation") reader helpers — INGESTION_V2_FLOWS §7.
// One word at a time at a fixed point, so the eyes don't saccade. Pure functions here;
// the RsvpReader component drives them on a timer. All logic is unit-testable.

const SCAFFOLD = /^##\s+(Source|Sequence|Related)\b/;

// Note markdown → plain readable prose: drop frontmatter, the appended scaffolding
// sections (Source/Sequence/Related), media embeds, and inline markdown markers; keep
// wikilink labels as words.
export function toReadableText(md) {
  let src = md || '';
  if (src.startsWith('---')) {
    const end = src.indexOf('\n---', 3);
    if (end !== -1) src = src.slice(end + 4);
  }
  const kept = [];
  for (const line of src.split('\n')) {
    if (SCAFFOLD.test(line)) break;   // scaffolding is always appended at the end
    if (/^#{1,6}\s/.test(line)) continue;  // headings are structure, not prose
    kept.push(line);
  }
  return kept.join('\n')
    .replace(/<!--[\s\S]*?-->/g, ' ')                                   // ingest markers
    .replace(/!\[\[[^\]]*\]\]/g, ' ')                                   // media embeds
    .replace(/\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g, (_, a, b) => b || a.split(/[/\\]/).pop())
    .replace(/[*_`>#]/g, ' ')                                           // md markers
    .replace(/\s+/g, ' ')                                               // single-space prose
    .trim();
}

export function tokenize(md) {
  return toReadableText(md).split(/\s+/).map(w => w.trim()).filter(Boolean);
}

// Optimal Recognition Point: the letter the eye should fix on — just left of centre, not
// the middle. Short words pivot early, long words a little later.
export function orpIndex(word) {
  const len = word.length;
  if (len <= 1) return 0;
  if (len <= 5) return 1;
  if (len <= 9) return 2;
  if (len <= 13) return 3;
  return 4;
}

// How long to hold a word: base from WPM, lengthened for long words and for punctuation
// (a full stop gets the longest pause). Returns milliseconds.
export function dwellMs(word, wpm) {
  const base = 60000 / Math.max(60, Math.min(1200, wpm));
  let ms = base;
  if (word.length > 8) ms *= 1 + (word.length - 8) * 0.04;
  if (/[.!?]["')\]]?$/.test(word)) ms *= 2.2;          // sentence end
  else if (/[,;:)"”\]]$/.test(word)) ms *= 1.6;        // clause pause
  return Math.round(ms);
}

// Split a word for ORP rendering: [before, pivot, after].
export function splitAtOrp(word) {
  const i = orpIndex(word);
  return [word.slice(0, i), word.slice(i, i + 1), word.slice(i + 1)];
}
