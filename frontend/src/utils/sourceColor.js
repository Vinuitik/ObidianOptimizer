// Color-code inbox notes by their source (capture), so every note the ingest agent produced
// from ONE source shares a hue and reads as a band in the queue (InboxReview).
//
// Strategy: assign hues by the source's FIRST-APPEARANCE order using the golden angle
// (~137.5°). Consecutive sources land far apart on the wheel — no two adjacent groups look
// alike (the old hash-into-fixed-palette let near-duplicates like violet/periwinkle collide).
// Fixed S/L tuned to sit on the dark amethyst theme (styles/tokens.css). To retune spacing/
// vividness: GOLDEN_ANGLE / SAT / LIGHT below.
const GOLDEN_ANGLE = 137.508;
const SAT = 72;    // %
const LIGHT = 63;  // % — bright enough to read as a 3px bar + 8px dot on #0e0d15

// Map of source key → color, keyed on captureId (falls back to path for un-captured items).
// Built from an ordered list so the i-th distinct source gets hue = i * goldenAngle.
export function buildSourceColors(items) {
  const colors = new Map();
  let i = 0;
  for (const it of items) {
    const key = it.captureId || it.path;
    if (!colors.has(key)) {
      const hue = Math.round((i * GOLDEN_ANGLE) % 360);
      colors.set(key, `hsl(${hue} ${SAT}% ${LIGHT}%)`);
      i++;
    }
  }
  return colors;
}

// Reorder so notes from the same source are contiguous (color bands read as groups),
// preserving each source's first-appearance order and ordering within a source by
// captureSeq. Falls back to `path` for items without a captureId (e.g. in-place).
export function groupBySource(items) {
  const firstSeen = new Map();
  items.forEach((it, i) => {
    const k = it.captureId || it.path;
    if (!firstSeen.has(k)) firstSeen.set(k, i);
  });
  return items
    .map((it, i) => ({ it, i }))
    .sort((a, b) => {
      const ga = firstSeen.get(a.it.captureId || a.it.path);
      const gb = firstSeen.get(b.it.captureId || b.it.path);
      if (ga !== gb) return ga - gb;
      const sa = a.it.captureSeq ?? 0, sb = b.it.captureSeq ?? 0;
      if (sa !== sb) return sa - sb;
      // Same source order → sub-order (manual splits): #N before #N-1 before #N-2.
      const ma = a.it.captureSeqMinor ?? 0, mb = b.it.captureSeqMinor ?? 0;
      if (ma !== mb) return ma - mb;
      return a.i - b.i;
    })
    .map(x => x.it);
}

// The order badge for an inbox row: "#N" for an original note, "#N-M" for a note manually
// split off it (capture-seq-minor). captureSeq is 0-based on the wire, shown 1-based.
export function captureLabel(item) {
  if (item?.captureSeq == null) return null;
  const major = item.captureSeq + 1;
  const minor = item.captureSeqMinor ?? 0;
  return minor > 0 ? `#${major}-${minor}` : `#${major}`;
}
