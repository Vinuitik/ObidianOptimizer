import MarkdownIt from 'markdown-it';
import { fileTypeFor, resolveMediaSrc } from './media';
import katex from 'katex';

const md = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: true,
  breaks: true,
});

// ── Math (KaTeX): $..$ inline, $$..$$ and \[..\] block. markdown-it never feeds code spans /
// fences to these rules, so `$` in code is untouched.
function mathHtml(src, displayMode) {
  try {
    return katex.renderToString(src, { throwOnError: true, displayMode });
  } catch {
    const tag = displayMode ? 'pre' : 'code';
    const raw = displayMode ? `$$\n${src}\n$$` : `$${src}$`;
    return `<${tag} class="math-error">${md.utils.escapeHtml(raw)}</${tag}>`;
  }
}
const INLINE_MATH = /^\$(?!\$)(?=[^\s`])([^$\n`]*?[^\s`])\$(?![$\d])/;
md.inline.ruler.before('escape', 'math_inline', (state, silent) => {
  if (state.src.charCodeAt(state.pos) !== 0x24) return false;
  const m = INLINE_MATH.exec(state.src.slice(state.pos));
  if (!m) return false;
  if (!silent) state.push('math_inline', '', 0).content = m[1];
  state.pos += m[0].length;
  return true;
});
md.block.ruler.before('fence', 'math_block', (state, startLine, endLine, silent) => {
  const lineText = (n) => state.src.slice(state.bMarks[n] + state.tShift[n], state.eMarks[n]).trimEnd();
  const first = lineText(startLine);
  const close = first.startsWith('$$') ? '$$' : first.startsWith('\\[') ? '\\]' : null;
  if (!close) return false;
  const rest = first.slice(2);
  const parts = [];
  let last = startLine;
  if (rest.endsWith(close) && rest.length > 2) {
    parts.push(rest.slice(0, -2));
  } else {
    if (rest.includes(close)) return false;
    parts.push(rest);
    let closed = false;
    for (let n = startLine + 1; n < endLine; n++) {
      const l = lineText(n);
      last = n;
      if (l.endsWith(close)) { parts.push(l.slice(0, -2)); closed = true; break; }
      if (l.includes(close)) return false;
      parts.push(l);
    }
    if (!closed) return false;
  }
  const body = parts.join('\n').trim();
  if (!body) return false;
  if (silent) return true;
  const t = state.push('math_block', '', 0);
  t.content = body;
  t.block = true;
  t.map = [startLine, last + 1];
  state.line = last + 1;
  return true;
});
md.renderer.rules.math_inline = (tokens, i) => mathHtml(tokens[i].content, false);
md.renderer.rules.math_block = (tokens, i) => `${mathHtml(tokens[i].content, true)}\n`;

function parseFrontmatter(content) {
  const trimmed = content.trimStart();
  if (!trimmed.startsWith('---')) return { table: '', body: content };
  const after = trimmed.slice(3);
  const end = after.indexOf('\n---');
  if (end === -1) return { table: '', body: content };

  const yamlBlock = after.slice(0, end);
  const body = after.slice(end + 4).trimStart();

  const rows = yamlBlock
    .split('\n')
    .map(line => {
      const colon = line.indexOf(':');
      if (colon === -1) return '';
      const key = line.slice(0, colon).trim();
      const value = line.slice(colon + 1).trim();
      return key ? `<tr><th scope="row">${key}</th><td>${value || '—'}</td></tr>` : '';
    })
    .filter(Boolean)
    .join('');

  if (!rows) return { table: '', body };
  return { table: `<table><tbody>${rows}</tbody></table>\n`, body };
}

const escAttr = (t) => t.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

// [[t]] / [[t|a]] -> <span class="wiki-link" data-wiki-link="t">a-or-t</span>; skips ![[embed]].
export function renderWikiLinks(text) {
  return text.replace(/(?<!!)\[\[([^\[\]|\n]+)(?:\|([^\[\]\n]+))?\]\]/g, (_, target, alias) => {
    const t = target.trim();
    return `<span class="wiki-link" data-wiki-link="${escAttr(t)}">${alias ?? t}</span>`;
  });
}

// ![[file]] -> element by extension; src identical to the editor (mediaUrl / pending blob).
function embedHtml(filename) {
  const src = escAttr(resolveMediaSrc(filename));
  const name = escAttr(filename);
  const type = fileTypeFor(filename);
  if (type === 'video') return `<video class="embedded-video" src="${src}" controls data-obsidian-image="${name}"></video>`;
  if (type === 'audio') return `<audio class="embedded-audio" src="${src}" controls data-obsidian-image="${name}"></audio>`;
  if (type === 'pdf' || type === 'other') {
    return `<a class="embedded-file" href="${src}" target="_blank" rel="noopener noreferrer" data-obsidian-image="${name}">${type === 'pdf' ? '📄' : '📎'} ${name}</a>`;
  }
  return `<img class="embedded-image" src="${src}" alt="${name}" data-obsidian-image="${name}" />`;
}

export function renderMarkdown(content) {
  const { table, body } = parseFrontmatter(content);
  let processed = body;

  // ![[image.png]] → <img>
  processed = processed.replace(/!\[\[(.*?)\]\]/gs, (_, p1) => embedHtml(p1.trim()));

  // [[link|alias]] or [[link]] → clickable wiki-link anchor
  processed = renderWikiLinks(processed);

  // #hashtag → styled span (skip #headings at line start)
  processed = processed.replace(/(?<![#\w])#([a-zA-Z]\w*)/g, (_, tag) =>
    `<span class="md-tag">#${tag}</span>`
  );

  return table + md.render(processed);
}
