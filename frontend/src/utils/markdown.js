import MarkdownIt from 'markdown-it';
import { fileTypeFor, resolveMediaSrc } from './media';

const md = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: true,
  breaks: true,
});

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
