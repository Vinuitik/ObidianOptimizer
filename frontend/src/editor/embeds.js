import { RangeSetBuilder, StateEffect } from '@codemirror/state';
import { Decoration, EditorView, ViewPlugin, WidgetType } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { inCode } from './inCode';
import useStore from '../store/useStore';
import {
  addPendingBlob, fileTypeFor, generateFilename, isWhitelisted, onPendingBlobsChange,
  resolveMediaSrc,
} from '../utils/media';

const EMBED_RE = /!\[\[([^\]\n]+?)\]\]/g;
const refreshEffect = StateEffect.define();

class EmbedWidget extends WidgetType {
  constructor(filename, src) { super(); this.filename = filename; this.src = src; }
  // Same file + same URL -> DOM reused, so a playing <video> survives unrelated edits.
  eq(o) { return o.filename === this.filename && o.src === this.src; }
  get estimatedHeight() {
    const t = fileTypeFor(this.filename);
    return t === 'image' ? 240 : t === 'video' ? 270 : -1;
  }
  ignoreEvent(e) { return e.type !== 'click'; } // let click reach domEventHandlers; mousedown etc. stay native (video controls)
  toDOM() {
    const { filename, src } = this;
    const type = fileTypeFor(filename);
    let el;
    if (type === 'video' || type === 'audio') {
      el = document.createElement(type);
      el.className = type === 'video' ? 'embedded-video' : 'embedded-audio';
      el.src = src;
      el.controls = true;
    } else if (type === 'pdf' || type === 'other') {
      el = document.createElement('a');
      el.className = 'embedded-file';
      el.href = src;
      el.target = '_blank';
      el.rel = 'noopener noreferrer';
      el.textContent = `${type === 'pdf' ? '📄' : '📎'} ${filename}`;
    } else {
      el = document.createElement('img');
      el.className = 'embedded-image';
      el.src = src;
      el.alt = filename;
    }
    el.setAttribute('data-obsidian-image', filename);
    return el;
  }
}

function build(view) {
  const b = new RangeSetBuilder();
  const editing = view.state.facet(EditorView.editable);
  const sel = view.state.selection.ranges;
  const { doc } = view.state;
  for (const { from, to } of view.visibleRanges) {
    let pos = from;
    while (pos <= to) {
      const line = doc.lineAt(pos);
      if (line.text.includes('![[')) {
        // Raw text while the cursor is on this line (edit mode only).
        const active = editing && sel.some(r => r.from <= line.to && r.to >= line.from);
        if (!active) {
          EMBED_RE.lastIndex = 0;
          let m;
          while ((m = EMBED_RE.exec(line.text)) !== null) {
            const filename = m[1].trim();
            if (!filename || inCode(view.state, line.from + m.index)) continue;
            b.add(line.from + m.index, line.from + m.index + m[0].length, Decoration.replace({
              widget: new EmbedWidget(filename, resolveMediaSrc(filename)),
            }));
          }
        }
      }
      if (line.to >= to) break;
      pos = line.to + 1;
    }
  }
  return b.finish();
}

const embedPlugin = ViewPlugin.fromClass(class {
  constructor(view) {
    this.decorations = build(view);
    // Blob registry changes (tab switch, new paste) -> rebuild so pending previews resolve.
    this.unsub = onPendingBlobsChange(() => queueMicrotask(() => {
      if (this.dead) return;
      try { view.dispatch({ effects: refreshEffect.of(null) }); } catch { /* view mid-update */ }
    }));
  }
  update(u) {
    if (u.docChanged || u.selectionSet || u.viewportChanged
      || syntaxTree(u.state) !== syntaxTree(u.startState)
      || u.startState.facet(EditorView.editable) !== u.state.facet(EditorView.editable)
      || u.transactions.some(t => t.effects.some(e => e.is(refreshEffect)))) {
      this.decorations = build(u.view);
    }
  }
  destroy() { this.dead = true; this.unsub(); }
}, {
  decorations: v => v.decorations,
  provide: p => EditorView.atomicRanges.of(v => v.plugin(p)?.decorations ?? Decoration.none),
});

// ── Attachments ──────────────────────────────────────────────────────────────

/** Whitelist, rename, blob-preview, queue upload in the store, insert `![[name]]` at selection. */
export function insertFiles(view, files) {
  const { addPendingFile, showToast } = useStore.getState();
  const accepted = files.filter(isWhitelisted);
  const rejected = files.length - accepted.length;
  if (rejected > 0) {
    showToast?.(`${rejected} file${rejected > 1 ? 's' : ''} skipped — accepted types: images, video, audio, PDF.`);
  }
  for (const file of accepted) {
    const filename = generateFilename(file);
    const blobURL = URL.createObjectURL(file);
    addPendingBlob(filename, blobURL);
    addPendingFile(filename, file, blobURL);
    view.dispatch(view.state.replaceSelection(`![[${filename}]]`));
  }
}

export function htmlLinksToMarkdown(html) {
  const div = document.createElement('div');
  div.innerHTML = html;
  div.querySelectorAll('a[href]').forEach(a => {
    const href = a.getAttribute('href');
    const text = a.textContent.trim();
    if (href && text && !href.startsWith('#')) a.replaceWith(`[${text}](${href})`);
  });
  return div.textContent ?? '';
}

/**
 * opts.onImageClick(src, alt): read-only lightbox; opts.onDragOver(bool): overlay toggle.
 */
export function embedsExtension(opts = {}) {
  const editing = (view) => view.state.facet(EditorView.editable);
  return [
    embedPlugin,
    EditorView.domEventHandlers({
      click: (e, view) => {
        const img = e.target.closest?.('img[data-obsidian-image]');
        if (!img || editing(view)) return false;
        e.preventDefault();
        opts.onImageClick?.(img.currentSrc || img.src, img.alt || '');
        return true;
      },
      paste: (e, view) => {
        if (!editing(view)) return false;
        const files = Array.from(e.clipboardData?.files ?? []);
        if (files.length) { e.preventDefault(); insertFiles(view, files); return true; }
        const html = e.clipboardData?.getData('text/html');
        if (!html || !html.includes('<a ')) return false;
        const converted = htmlLinksToMarkdown(html);
        if (!converted) return false;
        e.preventDefault();
        view.dispatch(view.state.replaceSelection(converted));
        return true;
      },
      dragover: (e, view) => {
        if (!editing(view)) return false;
        const hasFiles = Array.from(e.dataTransfer?.items ?? []).some(i => i.kind === 'file');
        if (!hasFiles) return false;
        e.preventDefault();
        opts.onDragOver?.(true);
        return true;
      },
      dragleave: (e, view) => {
        if (!view.dom.contains(e.relatedTarget)) opts.onDragOver?.(false);
        return false;
      },
      drop: (e, view) => {
        opts.onDragOver?.(false);
        if (!editing(view)) return false;
        const files = Array.from(e.dataTransfer?.files ?? []);
        if (!files.length) return false;
        e.preventDefault();
        insertFiles(view, files);
        return true;
      },
    }),
  ];
}
