// Parse the deterministic scaffolding the ingest pipeline injects into a proposed note
// (embedder: synthesize.chronology_block → "## Sequence", linking.related_block →
// "## Related", synthesize._source_section → "## Source"). The review UI reads these back
// instead of needing a new API: the links + coarse source region already live in the note.

// Body of a `## <name>` section: lines after the heading until the next `## ` or EOF.
export function sectionBody(content, name) {
  const lines = (content || '').split('\n');
  const start = lines.findIndex(l => new RegExp(`^##\\s+${name}\\b`).test(l));
  if (start === -1) return '';
  const out = [];
  for (let j = start + 1; j < lines.length; j++) {
    if (/^##\s/.test(lines[j])) break;
    out.push(lines[j]);
  }
  return out.join('\n').trim();
}

const WIKI = /\[\[([^\]|]+)(?:\|[^\]]*)?\]\]/g;

// { sequence: { prev, next }, related: [...] } — the ordered chronological neighbours and
// the semantic neighbours, as note-name stems. Order is meaningful: prev → this → next.
export function parseLinks(content) {
  const seq = sectionBody(content, 'Sequence');
  const rel = sectionBody(content, 'Related');
  const prev = /Previous:\s*\[\[([^\]|]+)/.exec(seq);
  const next = /Next:\s*\[\[([^\]|]+)/.exec(seq);
  const related = rel ? [...rel.matchAll(WIKI)].map(m => m[1].trim()) : [];
  return {
    sequence: { prev: prev ? prev[1].trim() : null, next: next ? next[1].trim() : null },
    related,
  };
}

// Coarse source region for the splice viewer: which pages / what start time the note's
// source footer points at. Precise bbox/char splice needs the backend to serve
// locator.SpliceView; until then this page/timestamp granularity IS "the relevant part".
export function parseSourceRegion(content, source) {
  const body = sectionBody(content, 'Source');
  const ref = source || (body.match(/https?:\/\/\S+/) || [''])[0];

  // Local copy the ingest downloaded into the vault (resources/… or _workspace/…), so the
  // splice viewer plays the file itself instead of an external embed. Absent → external ref.
  const localM = /^\s*local:\s*(\S.*)$/im.exec(body);
  const local = localM ? localM[1].trim() : null;

  const pagesM = /pages:\s*([0-9,\s]+)/i.exec(body);
  const pages = pagesM
    ? pagesM[1].split(',').map(s => parseInt(s, 10)).filter(Number.isFinite)
    : [];

  let startSeconds = null;
  const tM = /[?&](?:t|start)=(\d+)s?/.exec(body) || /[?&](?:t|start)=(\d+)s?/.exec(ref || '');
  if (tM) {
    startSeconds = parseInt(tM[1], 10);
  } else {
    const mm = /\bfrom\s+(\d+):(\d{2})/.exec(body);
    if (mm) startSeconds = parseInt(mm[1], 10) * 60 + parseInt(mm[2], 10);
  }
  return { ref, pages, startSeconds, local };
}
