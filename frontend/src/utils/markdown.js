import MarkdownIt from 'markdown-it';

const md = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: true,
});

function stripFrontmatter(content) {
  if (!content.trimStart().startsWith('---')) return content;
  const after = content.trimStart().slice(3);
  const end = after.indexOf('\n---');
  if (end === -1) return content;
  return after.slice(end + 4).trimStart();
}

export function renderMarkdown(content) {
  let processed = stripFrontmatter(content);

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

  return md.render(processed);
}
