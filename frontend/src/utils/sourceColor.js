// Deterministic color per capture/source, so every note the ingest agent produced
// from ONE source shares a hue in the inbox queue (InboxReview). Curated to sit on the
// dark amethyst theme (styles/tokens.css) — distinct hues beat shades of one purple for
// telling many sources apart at a glance. Hashing captureId keeps a source's color
// stable across reloads. To change the palette / grouping: edit PALETTE below.
const PALETTE = [
  '#7c5cff', // violet (the accent)
  '#4cc9f0', // sky
  '#4cc38a', // green
  '#e0a458', // amber
  '#e05c9e', // magenta
  '#8a7bff', // periwinkle
  '#5ad1c8', // teal
  '#f2799e', // rose
];

// Stable hash → palette index. Same key always maps to the same color.
export function sourceColor(key) {
  if (!key) return null;
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) | 0;
  return PALETTE[Math.abs(h) % PALETTE.length];
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
      return a.i - b.i;
    })
    .map(x => x.it);
}

export { PALETTE };
