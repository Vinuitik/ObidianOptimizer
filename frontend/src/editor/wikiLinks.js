// [[wiki-links]] for CM6: decorations (brackets hidden off the active line), `[[` autocomplete,
// click / Ctrl+click navigation. Unclosed `[[foo` stays plain text. `![[embed]]` is skipped (W2).
import { Decoration, ViewPlugin, EditorView } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import { syntaxTree } from '@codemirror/language';
import { autocompletion } from '@codemirror/autocomplete';
import useStore from '../store/useStore';
import { searchWithFallback } from '../utils/useSearch';
import { resolveWikiTarget } from '../utils/wikiResolve';

const WIKI_RE = /(?<!!)\[\[([^\[\]|\n]+)(?:\|([^\[\]\n]+))?\]\]/g;
const hide = Decoration.replace({});
const wikiMark = (target) => Decoration.mark({
  class: 'wiki-link', attributes: { 'data-wiki-link': target },
});

function inCode(state, pos) {
  for (let n = syntaxTree(state).resolveInner(pos, 1); n; n = n.parent) {
    if (/Code/.test(n.name)) return true;
  }
  return false;
}

function build(view) {
  const { state } = view;
  const doc = state.doc;
  const active = new Set();
  for (const r of state.selection.ranges) {
    const b = doc.lineAt(r.to).number;
    for (let n = doc.lineAt(r.from).number; n <= b; n++) active.add(n);
  }
  const all = [];
  const atomic = [];
  const seen = new Set();
  for (const { from, to } of view.visibleRanges) {
    let pos = from;
    while (pos <= to) {
      const line = doc.lineAt(pos);
      pos = line.to + 1;
      if (seen.has(line.number)) continue;
      seen.add(line.number);
      WIKI_RE.lastIndex = 0;
      let m;
      while ((m = WIKI_RE.exec(line.text))) {
        const s = line.from + m.index;
        const e = s + m[0].length;
        if (inCode(state, s)) continue;
        const target = m[1].trim();
        const isActive = active.has(line.number);
        const innerFrom = s + 2;
        const innerTo = e - 2;
        const textFrom = isActive || m[2] == null ? innerFrom : innerFrom + m[1].length + 1;
        all.push(wikiMark(target).range(textFrom, innerTo));
        if (!isActive) {
          for (const r of [hide.range(s, textFrom), hide.range(innerTo, e)]) { all.push(r); atomic.push(r); }
        }
      }
    }
  }
  return { deco: Decoration.set(all, true), atomic: Decoration.set(atomic, true) };
}

const plugin = ViewPlugin.fromClass(class {
  constructor(view) { this.refresh(view); }
  refresh(view) { const b = build(view); this.decorations = b.deco; this.atomic = b.atomic; }
  update(u) {
    if (u.docChanged || u.viewportChanged || u.selectionSet) this.refresh(u.view);
  }
}, {
  decorations: (v) => v.decorations,
  provide: (p) => EditorView.atomicRanges.of((view) => view.plugin(p)?.atomic ?? Decoration.none),
});

// ── navigation ───────────────────────────────────────────────────────────────
export function openWikiTarget(target, opts = {}) {
  if (opts.onOpenNote) { opts.onOpenNote(target); return true; }
  const { noteIndex, openTab } = useStore.getState();
  const path = resolveWikiTarget(noteIndex, target);
  if (path) openTab?.(path);
  return !!path;
}

function clickHandler(opts) {
  return (e) => {
    const el = e.target.closest?.('[data-wiki-link]');
    if (!el) return false;
    const editing = useStore.getState().isMutable;
    if (editing && !(e.ctrlKey || e.metaKey)) return false;
    e.preventDefault();
    openWikiTarget(el.getAttribute('data-wiki-link'), opts);
    return true;
  };
}

// ── `[[` completion ──────────────────────────────────────────────────────────
const TRIGGER = /\[\[([^\]|]{0,80})$/;
const DEBOUNCE_MS = 80;

export function wikiCompletionSource(search = searchWithFallback, debounceMs = DEBOUNCE_MS) {
  return async (ctx) => {
    const m = ctx.matchBefore(TRIGGER);
    if (!m) return null;
    const q = m.text.slice(2).trim();
    if (q.length < 1) return null;

    const ctrl = new AbortController();
    ctx.addEventListener('abort', () => ctrl.abort());
    await new Promise((r) => setTimeout(r, debounceMs));
    if (ctrl.signal.aborted) return null;
    let results;
    try { results = await search(q, { signal: ctrl.signal }); } catch { return null; }
    if (ctrl.signal.aborted || !results?.length) return null;

    return {
      from: m.from + 2,
      filter: false,
      options: results.map((r) => {
        const base = r.notePath.split(/[/\\]/).pop().replace(/\.md$/i, '');
        return {
          label: base,
          detail: r.snippet,
          apply: (view, _c, from, to) => {
            const end = view.state.doc.sliceString(to, to + 2) === ']]' ? to + 2 : to;
            const insert = `${base}]]`;
            view.dispatch({
              changes: { from, to: end, insert },
              selection: { anchor: from + insert.length },
              userEvent: 'input.complete',
            });
          },
        };
      }),
    };
  };
}

export function wikiLinksExtension(opts = {}) {
  return [
    plugin,
    EditorView.domEventHandlers({ click: clickHandler(opts) }),
    autocompletion({ icons: false }),
    EditorState.languageData.of(() => [{ autocomplete: wikiCompletionSource() }]),
  ];
}
