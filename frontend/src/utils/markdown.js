import MarkdownIt from 'markdown-it';

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

export function renderMarkdown(content) {
  const { table, body } = parseFrontmatter(content);
  let processed = body;

  // ![[image.png]] → <img>
  processed = processed.replace(/!\[\[(.*?)\]\]/gs, (_, p1) =>
    `<img src="/api/images/${encodeURIComponent(p1)}" alt="${p1}" class="embedded-image" />`
  );

  // [[link|alias]] or [[link]] → clickable wiki-link anchor
  processed = processed.replace(/\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/gs, (_, target, alias) => {
    const label = alias ?? target.split(/[/\\]/).pop();
    return `<a class="wiki-link" data-wiki-link="${target.trim()}" href="#">${label}</a>`;
  });

  // #hashtag → styled span (skip #headings at line start)
  processed = processed.replace(/(?<![#\w])#([a-zA-Z]\w*)/g, (_, tag) =>
    `<span class="md-tag">#${tag}</span>`
  );

  return table + md.render(processed);
}
